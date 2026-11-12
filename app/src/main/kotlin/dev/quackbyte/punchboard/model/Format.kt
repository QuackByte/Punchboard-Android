package dev.quackbyte.punchboard.model

import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

/*
 * Parsing and formatting shared with the desktop (`utils.ts`). Display text
 * uses en-GB conventions, as the desktop does.
 */

/** JavaScript's Math.round: halves go towards +∞. */
fun jsRound(value: Double): Long = floor(value + 0.5).toLong()

fun clampHours(hours: Double): Double = if (!hours.isFinite()) 0.0 else hours.coerceIn(0.0, 24.0)

fun clampPercent(percent: Double): Double = if (!percent.isFinite()) 0.0 else percent.coerceIn(0.0, 100.0)

/** 9.5 → "9h 30m", 8 → "8h", 0.25 → "15m". */
fun formatDuration(hours: Double): String {
    val totalMinutes = jsRound(maxOf(0.0, hours) * 60)
    val whole = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        minutes == 0L -> "${whole}h"
        whole == 0L -> "${minutes}m"
        else -> "${whole}h %02dm".format(minutes)
    }
}

fun formatMinutes(minutes: Int): String = formatDuration(minutes / 60.0)

/** Signed duration: "+2h 05m" / "−1h" (with a real minus sign). */
fun formatSignedDuration(hours: Double): String =
    (if (hours > 0) "+" else "−") + formatDuration(kotlin.math.abs(hours))

fun formatMoney(amount: Double, currencyCode: String): String {
    val format = NumberFormat.getCurrencyInstance(Locale.UK)
    runCatching { format.currency = Currency.getInstance(currencyCode) }
    return format.format(amount)
}

object Currencies {
    val codes = listOf("GBP", "EUR", "USD")
    fun symbol(code: String) = when (code) {
        "GBP" -> "£"
        "EUR" -> "€"
        "USD" -> "$"
        else -> code
    }
}

private val timeRange = Regex("""^(\d{1,2})(?::(\d{2}))?\s*(?:-|to|–|—)\s*(\d{1,2})(?::(\d{2}))?$""", RegexOption.IGNORE_CASE)
private val duration = Regex("""^(?:(\d{1,2}(?:\.\d+)?)\s*h)?\s*(?:(\d{1,2})\s*m)?$""", RegexOption.IGNORE_CASE)
private val sessionRange = Regex("""^(.+?)\s*(?:-|to|–|—)\s*(.+)$""", RegexOption.IGNORE_CASE)
private val partSplit = Regex("""[,;]|\s+and\s+""", RegexOption.IGNORE_CASE)

/** "7.5", "9h 40m", "40m" or "08:30 to 18:45" → hours, or null. */
fun parseHoursInput(raw: String): Double? {
    val input = raw.trim()
    if (input.isEmpty()) return null

    timeRange.matchEntire(input)?.let { match ->
        val (sh, sm, eh, em) = match.destructured
        val startHour = sh.toInt()
        val startMinute = sm.ifEmpty { "0" }.toInt()
        val endHour = eh.toInt()
        val endMinute = em.ifEmpty { "0" }.toInt()
        if (startHour > 23 || endHour > 23 || startMinute > 59 || endMinute > 59) return null
        val diff = endHour * 60 + endMinute - (startHour * 60 + startMinute)
        return if (diff <= 0) null else diff / 60.0
    }

    duration.matchEntire(input)?.let { match ->
        val hoursPart = match.groupValues[1]
        val minutesPart = match.groupValues[2]
        if (hoursPart.isNotEmpty() || minutesPart.isNotEmpty()) {
            val minutes = minutesPart.ifEmpty { "0" }.toInt()
            if (minutes > 59) return null
            return hoursPart.ifEmpty { "0" }.toDouble() + minutes / 60.0
        }
    }

    return input.toDoubleOrNull()?.takeIf { it.isFinite() }
}

sealed interface DayInput {
    data class Sessions(val entries: List<TimeEntry>) : DayInput
    data class Total(val hours: Double) : DayInput
}

/**
 * A quick-entry line: sessions ("9-13, 14:30-18", "8:45 to 12:15; 13-17:30")
 * or a single total ("7.5", "7h 30m"). Null when it can't be understood.
 */
fun parseDayInput(raw: String): DayInput? {
    val input = raw.trim()
    if (input.isEmpty()) return null
    val parts = input.split(partSplit).map { it.trim() }.filter { it.isNotEmpty() }

    if (parts.all { sessionRange.matches(it) }) {
        val entries = parts.map { part ->
            val match = sessionRange.matchEntire(part)!!
            val start = Clock.parse(match.groupValues[1])
            val end = Clock.parse(match.groupValues[2])
            if (start == null || end == null || start == end) return null
            TimeEntry(Clock.format(start), Clock.format(end))
        }
        return DayInput.Sessions(sortEntries(entries))
    }

    if (parts.size == 1) {
        val hours = parseHoursInput(input)
        if (hours != null && hours in 0.0..24.0) return DayInput.Total(hours)
    }
    return null
}

/** Session length in minutes; running sessions count up to [nowMinutes] or as zero. */
fun entryMinutes(entry: TimeEntry, nowMinutes: Int? = null): Int {
    val start = Clock.parse(entry.start) ?: return 0
    val end = when {
        entry.end != null -> Clock.parse(entry.end)
        nowMinutes != null -> Clock.parse(Clock.format(nowMinutes))
        else -> null
    } ?: return 0
    return if (end >= start) end - start else end + 1440 - start
}

fun sumEntriesHours(entries: List<TimeEntry>, nowMinutes: Int? = null): Double =
    entries.sumOf { entryMinutes(it, nowMinutes) } / 60.0

fun sortEntries(entries: List<TimeEntry>): List<TimeEntry> = entries.sortedBy { Clock.parse(it.start) ?: 0 }

private val enGb = Locale.UK

object DateText {
    private val weekdayShort = DateTimeFormatter.ofPattern("EEE", enGb)
    private val dayMonthShort = DateTimeFormatter.ofPattern("d MMM", enGb)
    private val dayMonthShortYear = DateTimeFormatter.ofPattern("d MMM yyyy", enGb)
    private val weekdayDayMonth = DateTimeFormatter.ofPattern("EEE d MMM", enGb)
    private val longDate = DateTimeFormatter.ofPattern("d MMMM yyyy", enGb)
    private val weekdayLong = DateTimeFormatter.ofPattern("EEEE", enGb)
    private val monthLongYear = DateTimeFormatter.ofPattern("MMMM yyyy", enGb)
    private val monthShort = DateTimeFormatter.ofPattern("MMM", enGb)
    private val monthLong = DateTimeFormatter.ofPattern("MMMM", enGb)
    private val iso = DateTimeFormatter.ISO_LOCAL_DATE

    fun weekdayShort(date: LocalDate) = weekdayShort.format(date)
    fun dayMonth(date: LocalDate) = dayMonthShort.format(date)
    fun dayMonthYear(date: LocalDate) = dayMonthShortYear.format(date)
    fun weekdayDayMonth(date: LocalDate) = weekdayDayMonth.format(date)
    fun long(date: LocalDate) = longDate.format(date)
    fun weekday(date: LocalDate) = weekdayLong.format(date)
    fun monthYear(month: java.time.YearMonth) = monthLongYear.format(month.atDay(1))
    fun monthShort(month: java.time.YearMonth) = monthShort.format(month.atDay(1))
    fun monthLong(month: java.time.YearMonth) = monthLong.format(month.atDay(1))
    fun iso(date: LocalDate) = iso.format(date)
}

/** "21 Sept – 20 Oct" for a payslip period. */
fun periodLabel(endMonth: java.time.YearMonth, startDay: Int, withYear: Boolean = false): String {
    val range = PayslipPeriods.range(endMonth, startDay)
    val format: (LocalDate) -> String = if (withYear) DateText::dayMonthYear else DateText::dayMonth
    return "${format(range.start)} – ${format(range.endInclusive)}"
}

/** How JavaScript prints a number: 8 not 8.0. */
fun Double.js(): String = if (this == kotlin.math.floor(this) && kotlin.math.abs(this) < 1e15) toLong().toString() else toString()

/** Rounds to 2 decimals, the way the CSV does. */
fun round2(value: Double): Double = (value * 100).roundToLong() / 100.0
