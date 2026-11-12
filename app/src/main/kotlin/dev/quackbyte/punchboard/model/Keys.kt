package dev.quackbyte.punchboard.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** Day keys as the desktop writes them: "YYYY-M-D", zero-based month, no padding. */
object DayKeys {
    fun of(date: LocalDate): String = "${date.year}-${date.monthValue - 1}-${date.dayOfMonth}"

    fun parse(key: String): LocalDate? {
        val parts = key.split("-")
        if (parts.size != 3) return null
        val (year, monthIndex, day) = parts.map { it.toIntOrNull() ?: return null }
        return runCatching { LocalDate.of(year, monthIndex + 1, day) }.getOrNull()
    }
}

/** Month keys: "YYYY-MM", one-based month. */
object MonthKeys {
    private val pattern = Regex("""^(\d{4})-(\d{2})$""")

    fun of(month: YearMonth): String = "%04d-%02d".format(month.year, month.monthValue)

    fun parse(key: String): YearMonth? {
        val match = pattern.matchEntire(key) ?: return null
        val (year, month) = match.destructured
        return runCatching { YearMonth.of(year.toInt(), month.toInt()) }.getOrNull()
    }
}

/** Weekday keys used in [TrackerData.selectedDays]. */
object WeekdayKeys {
    fun of(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "mon"
        DayOfWeek.TUESDAY -> "tue"
        DayOfWeek.WEDNESDAY -> "wed"
        DayOfWeek.THURSDAY -> "thu"
        DayOfWeek.FRIDAY -> "fri"
        DayOfWeek.SATURDAY -> "sat"
        DayOfWeek.SUNDAY -> "sun"
    }
}

/**
 * Payslip periods. A period is named after the month it ends in and runs
 * from [startDay] of the previous month to the day before [startDay] of
 * that month (clamped to short months), matching `getPayslipRange`.
 */
object PayslipPeriods {
    fun clampStartDay(day: Int): Int = day.coerceIn(1, 31)

    /** The period [today] falls in, as its end month. */
    fun endMonthFor(today: LocalDate, startDay: Int): YearMonth {
        val current = YearMonth.from(today)
        return if (today.dayOfMonth >= clampStartDay(startDay)) current.plusMonths(1) else current
    }

    fun range(endMonth: YearMonth, startDay: Int): ClosedRange<LocalDate> {
        val safe = clampStartDay(startDay)
        val startMonth = endMonth.minusMonths(1)
        val start = startMonth.atDay(minOf(safe, startMonth.lengthOfMonth()))
        val end = endMonth.atDay(minOf(safe, endMonth.lengthOfMonth())).minusDays(1)
        return start..end
    }
}
