package dev.quackbyte.punchboard.model

import kotlin.math.roundToInt

/** Wall-clock parsing and formatting, matching the desktop's `parseClock`/`formatClock`. */
object Clock {
    private val pattern = Regex("""^(\d{1,2})(?:[:.h]?(\d{2}))?\s*(am|pm)?$""", RegexOption.IGNORE_CASE)

    /** "9", "9:30", "0930", "9.30" or "5pm" → minutes since midnight, or null. */
    fun parse(raw: String): Int? {
        val match = pattern.matchEntire(raw.trim().lowercase()) ?: return null
        var hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
        val meridiem = match.groupValues[3]
        if (meridiem.isNotEmpty()) {
            if (hour !in 1..12) return null
            hour = hour % 12 + if (meridiem == "pm") 12 else 0
        }
        if (hour > 23 || minute > 59) return null
        return hour * 60 + minute
    }

    /** Minutes since midnight → "HH:MM", wrapping past midnight. */
    fun format(minutes: Int): String {
        val normalized = ((minutes % 1440) + 1440) % 1440
        return "%02d:%02d".format(normalized / 60, normalized % 60)
    }
}

/**
 * How clocked time turns into paid time, normalized with the desktop's
 * defaults and limits (`normalizeWorkRules`).
 */
data class WorkRules(
    val breakMinutes: Double = 0.0,
    val breakAfterHours: Double = 6.0,
    val roundingMinutes: Int = 0,
    val overtimeMultiplier: Double = 1.0,
    val overtimeIncludesLongDays: Boolean = false,
    val balanceWeek: Boolean = true,
    val carryBalance: Boolean = true,
    val balanceResetsYearly: Boolean = false,
    val balanceCapHours: Double = 0.0,
    val balanceStartDate: String = "",
) {
    companion object {
        private val roundingSteps = setOf(0, 5, 6, 10, 15, 30)
        private val isoDate = Regex("""^\d{4}-\d{2}-\d{2}$""")

        fun from(settings: TrackerSettings): WorkRules {
            fun Double?.clamp(fallback: Double, min: Double, max: Double) =
                this?.takeIf { it.isFinite() }?.coerceIn(min, max) ?: fallback

            return WorkRules(
                breakMinutes = settings.breakMinutes.clamp(0.0, 0.0, 240.0),
                breakAfterHours = settings.breakAfterHours.clamp(6.0, 0.0, 24.0),
                roundingMinutes = settings.roundingMinutes?.takeIf { it in roundingSteps } ?: 0,
                overtimeMultiplier = settings.overtimeMultiplier.clamp(1.0, 1.0, 5.0),
                overtimeIncludesLongDays = settings.overtimeIncludesLongDays == true,
                balanceWeek = settings.balanceWeek != false,
                carryBalance = settings.carryBalance != false,
                balanceResetsYearly = settings.balanceResetsYearly == true,
                balanceCapHours = settings.balanceCapHours.clamp(0.0, 0.0, 1000.0),
                balanceStartDate = settings.balanceStartDate?.takeIf { isoDate.matches(it) } ?: "",
            )
        }
    }
}

data class WorkedTime(
    /** Minutes on the clock after rounding, before any break deduction. */
    val clockedMinutes: Int,
    /** Unpaid break deducted automatically. */
    val breakMinutes: Int,
    /** Paid minutes: clocked minus the automatic break. */
    val workedMinutes: Int,
)

/**
 * Applies the rounding and automatic-break rules to a day's sessions, as the
 * desktop's `workedTime` does. Running sessions count up to [nowMinutes] when
 * given, otherwise not at all. Gaps between sessions already count towards
 * the automatic break, so a lunch clocked out isn't deducted twice.
 */
fun workedTime(entries: List<TimeEntry>, rules: WorkRules, nowMinutes: Int? = null): WorkedTime {
    val spans = entries.mapNotNull { entry ->
        val start = Clock.parse(entry.start) ?: return@mapNotNull null
        val end = entry.end?.let(Clock::parse) ?: if (entry.end == null) nowMinutes else null
        if (end == null) return@mapNotNull null
        val roundedStart = roundMinutes(start, rules.roundingMinutes)
        var roundedEnd = roundMinutes(end, rules.roundingMinutes)
        if (end < start) roundedEnd += 1440
        if (roundedEnd <= roundedStart) null else roundedStart to roundedEnd
    }.sortedBy { it.first }

    val clocked = spans.sumOf { it.second - it.first }
    val gaps = spans.zipWithNext().sumOf { (previous, next) -> maxOf(0, next.first - previous.second) }
    val breakMinutes =
        if (rules.breakMinutes > 0 && clocked >= rules.breakAfterHours * 60) {
            minOf(clocked, maxOf(0, rules.breakMinutes.roundToInt() - gaps))
        } else {
            0
        }
    return WorkedTime(clocked, breakMinutes, clocked - breakMinutes)
}

fun roundMinutes(minutes: Int, step: Int): Int =
    if (step == 0) minutes else (minutes.toDouble() / step).roundToJsInt() * step

/** JavaScript's Math.round: halves round up (towards +∞), unlike Kotlin's roundToInt on negatives. */
private fun Double.roundToJsInt(): Int = kotlin.math.floor(this + 0.5).toInt()
