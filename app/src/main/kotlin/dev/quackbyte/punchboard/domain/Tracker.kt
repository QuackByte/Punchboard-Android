package dev.quackbyte.punchboard.domain

import dev.quackbyte.punchboard.model.ActivityLogEntry
import dev.quackbyte.punchboard.model.BalanceSource
import dev.quackbyte.punchboard.model.Clock
import dev.quackbyte.punchboard.model.DataFile
import dev.quackbyte.punchboard.model.DayException
import dev.quackbyte.punchboard.model.DayKeys
import dev.quackbyte.punchboard.model.DayResult
import dev.quackbyte.punchboard.model.MonthKeys
import dev.quackbyte.punchboard.model.PayslipPeriods
import dev.quackbyte.punchboard.model.PeriodResult
import dev.quackbyte.punchboard.model.SalarySettings
import dev.quackbyte.punchboard.model.TimeEntry
import dev.quackbyte.punchboard.model.TrackerData
import dev.quackbyte.punchboard.model.TrackerSettings
import dev.quackbyte.punchboard.model.WorkRules
import dev.quackbyte.punchboard.model.balanceSource
import dev.quackbyte.punchboard.model.carriedBalance
import dev.quackbyte.punchboard.model.clampHours
import dev.quackbyte.punchboard.model.clampPercent
import dev.quackbyte.punchboard.model.computePeriod
import dev.quackbyte.punchboard.model.isWorkday
import dev.quackbyte.punchboard.model.js
import dev.quackbyte.punchboard.model.mondayOf
import dev.quackbyte.punchboard.model.normalizeSalary
import dev.quackbyte.punchboard.model.weeklyDayTarget
import dev.quackbyte.punchboard.model.weeklyHoursOf
import dev.quackbyte.punchboard.model.workedTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Reads a time card the way the desktop's `useWorkTracker` does. Months the
 * desktop hasn't saved yet start from the default schedule with the pay
 * settings carried forward from the latest earlier month.
 */
class Tracker(val data: DataFile) {
    val payslipStartDay: Int = PayslipPeriods.clampStartDay(data.settings.payslipStartDay)
    val rules: WorkRules = WorkRules.from(data.settings)
    val vacationDaysPerYear: Double =
        data.settings.vacationDaysPerYear?.takeIf { it.isFinite() }?.coerceIn(0.0, 366.0) ?: 0.0
    val leaveYearStartMonth: Int = data.settings.leaveYearStartMonth?.coerceIn(1, 12) ?: 1

    fun isSaved(month: YearMonth) = data.months.containsKey(MonthKeys.of(month))

    fun currentPeriod(today: LocalDate): YearMonth = PayslipPeriods.endMonthFor(today, payslipStartDay)

    /** The month's record, or a fresh one: default schedule, carried-forward pay. */
    fun monthData(month: YearMonth): TrackerData {
        val key = MonthKeys.of(month)
        data.months[key]?.let { return it }
        return freshMonth(key)
    }

    private fun freshMonth(key: String): TrackerData {
        val prior = data.months.entries
            .filter { it.key <= key }
            .sortedByDescending { it.key }
            .map { it.value }
            .firstOrNull()
            ?: return TrackerData()
        return TrackerData(
            hourlyRate = maxOf(0.0, prior.hourlyRate),
            taxPercent = clampPercent(prior.taxPercent),
            defaultHours = maxOf(0.0, prior.defaultHours),
            currency = prior.currency,
            secondaryCurrency = prior.secondaryCurrency,
            conversionRate = maxOf(0.0, prior.conversionRate),
            currencyConversionEnabled = prior.currencyConversionEnabled,
            salary = normalizeSalary(prior.salary),
        )
    }

    fun period(month: YearMonth, live: Pair<String, Int>? = null): PeriodResult =
        computePeriod(monthData(month), month, payslipStartDay, rules, live)

    /** The running session anywhere in [month], if any. */
    fun openSession(month: YearMonth): OpenSession? {
        val entries = monthData(month).timeEntries.orEmpty()
        for ((key, list) in entries) {
            val index = list.indexOfFirst { it.isRunning }
            if (index >= 0) return OpenSession(key, index, list[index])
        }
        return null
    }

    /** Payslip periods that can bank hours (salaried ones), for the carried balance. */
    fun balanceSources(extraMonth: YearMonth? = null): List<BalanceSource> {
        if (!rules.balanceWeek || !rules.carryBalance) return emptyList()
        val months = data.months.keys.mapNotNull(MonthKeys::parse).toMutableSet()
        extraMonth?.let(months::add)
        return months.sorted().mapNotNull { month ->
            val monthData = monthData(month)
            if (!normalizeSalary(monthData.salary).enabled) return@mapNotNull null
            balanceSource(monthData, period(month).days)
        }
    }

    /**
     * Today's target hours. With the week balanced, it's what's left of the
     * weekly hours less any balance carried in, shared over the workdays
     * still to come; the week may span two payslip periods.
     */
    fun todayTarget(today: LocalDate): TodayTarget {
        val month = currentPeriod(today)
        val todayData = monthData(month)
        val isWorkday = todayData.isWorkday(today) && todayData.exceptions[DayKeys.of(today)] == null
        if (!rules.balanceWeek) {
            return TodayTarget(if (isWorkday) todayData.hoursPerDay else 0.0, 0.0, isWorkday)
        }

        val monday = mondayOf(today)
        val sources = balanceSources(month)
        val carried = carriedBalance(sources, monday, rules)

        val periods = mutableMapOf<YearMonth, Pair<Map<String, DayResult>, Boolean>>()
        val week = mutableListOf<DayResult>()
        val settled = mutableSetOf<String>()
        val todaySalaried = normalizeSalary(todayData.salary).enabled
        val startDate = rules.balanceStartDate.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        for (index in 0 until 7) {
            val date = monday.plusDays(index.toLong())
            val periodMonth = currentPeriod(date)
            val (byKey, salaried) = periods.getOrPut(periodMonth) {
                period(periodMonth).byKey to normalizeSalary(monthData(periodMonth).salary).enabled
            }
            val day = byKey[DayKeys.of(date)] ?: continue
            week += day
            if (todaySalaried && (!salaried || (startDate != null && date < startDate))) settled += day.key
        }

        val hours = weeklyDayTarget(
            week,
            today,
            weeklyHoursOf(todayData),
            todayData.selectedDays.size,
            carried,
        ) { it.key in settled }
        return TodayTarget(hours, carried, isWorkday)
    }

    /** Today's sessions and the running one, from the current period. */
    fun todayEntries(today: LocalDate): List<TimeEntry> =
        monthData(currentPeriod(today)).timeEntries?.get(DayKeys.of(today)).orEmpty()

    companion object {
        const val ACTIVITY_LIMIT = 200
    }
}

data class OpenSession(val key: String, val index: Int, val entry: TimeEntry) {
    val date: LocalDate? get() = DayKeys.parse(key)
}

data class TodayTarget(val hours: Double, val carried: Double, val isWorkday: Boolean)

enum class ActivityType(val wire: String) {
    MonthChange("month-change"),
    WeekdayToggle("weekday-toggle"),
    ExceptionChange("exception-change"),
    ValueChange("value-change"),
    HoursChange("hours-change"),
}

/** One day's stored state, for undo. */
data class DaySnapshot(
    val month: YearMonth,
    val key: String,
    val entries: List<TimeEntry>?,
    val daily: Double?,
    val extra: Double?,
    val exception: String?,
) {
    val isEmpty get() = entries == null && daily == null && extra == null && exception == null
}

/**
 * Edits to a time card, mirroring the desktop's: each returns the new file.
 * [now] stamps activity entries; the clock is passed in so tests can pin it.
 */
class TrackerEditor(private val data: DataFile, private val now: () -> LocalDateTime = LocalDateTime::now) {
    private val tracker = Tracker(data)

    private fun withMonth(month: YearMonth, transform: (TrackerData) -> TrackerData): DataFile {
        val key = MonthKeys.of(month)
        val updated = transform(tracker.monthData(month))
        val saved = if (key in data.savedMonths) data.savedMonths else (data.savedMonths + key).sorted()
        return data.copy(months = data.months + (key to updated), savedMonths = saved)
    }

    private fun DataFile.log(type: ActivityType, message: String, month: YearMonth): DataFile {
        val instant = now().atZone(java.time.ZoneId.systemDefault()).toInstant()
        val entry = ActivityLogEntry(
            id = "${instant.toEpochMilli()}-${randomSuffix()}",
            monthKey = MonthKeys.of(month),
            timestamp = isoTimestamp(instant),
            type = type.wire,
            message = message,
        )
        return copy(activityLog = (listOf(entry) + activityLog).take(Tracker.ACTIVITY_LIMIT))
    }

    private fun inPeriod(month: YearMonth, date: LocalDate) = date in PayslipPeriods.range(month, tracker.payslipStartDay)

    private fun isWorkingDay(month: YearMonth, date: LocalDate) = inPeriod(month, date) && tracker.monthData(month).isWorkday(date)

    fun snapshot(month: YearMonth, key: String): DaySnapshot {
        val monthData = tracker.monthData(month)
        return DaySnapshot(
            month = month,
            key = key,
            entries = monthData.timeEntries?.get(key),
            daily = monthData.dailyHours[key],
            extra = monthData.extraHours[key],
            exception = monthData.exceptions[key],
        )
    }

    fun restore(snapshot: DaySnapshot): DataFile = withMonth(snapshot.month) { month ->
        month.copy(
            timeEntries = month.timeEntries.orEmpty().with(snapshot.key, snapshot.entries).ifEmptyNullIf(month.timeEntries == null),
            dailyHours = month.dailyHours.with(snapshot.key, snapshot.daily),
            extraHours = month.extraHours.with(snapshot.key, snapshot.extra),
            exceptions = month.exceptions.with(snapshot.key, snapshot.exception),
        )
    }.log(ActivityType.HoursChange, "Undid change on ${snapshot.key}", snapshot.month)

    /**
     * Replaces a day's sessions and re-derives its hour total, so files stay
     * readable by versions that only know hour totals.
     */
    fun setDayEntries(month: YearMonth, date: LocalDate, entries: List<TimeEntry>): DataFile {
        if (!inPeriod(month, date)) return data
        val key = DayKeys.of(date)
        val hours = clampHours(workedTime(entries, tracker.rules).workedMinutes / 60.0)
        val working = isWorkingDay(month, date)
        return withMonth(month) { current ->
            val timeEntries = if (entries.isEmpty()) current.timeEntries.orEmpty() - key else current.timeEntries.orEmpty() + (key to entries)
            if (working) {
                current.copy(
                    timeEntries = timeEntries,
                    dailyHours = if (entries.isEmpty()) current.dailyHours - key else current.dailyHours + (key to hours),
                )
            } else {
                current.copy(
                    timeEntries = timeEntries,
                    extraHours = if (hours == 0.0) current.extraHours - key else current.extraHours + (key to hours),
                )
            }
        }.log(
            ActivityType.HoursChange,
            if (entries.isEmpty()) "Cleared sessions on $key" else "Logged ${entries.size} session${if (entries.size == 1) "" else "s"} on $key",
            month,
        )
    }

    /** Sets a plain hour total for a day, dropping any clocked sessions. */
    fun setDayTotal(month: YearMonth, date: LocalDate, hours: Double): DataFile {
        if (!inPeriod(month, date)) return data
        val key = DayKeys.of(date)
        val value = clampHours(hours)
        val working = isWorkingDay(month, date)
        return withMonth(month) { current ->
            val timeEntries = current.timeEntries?.let { it - key }
            if (working) {
                current.copy(timeEntries = timeEntries, dailyHours = current.dailyHours + (key to value))
            } else {
                current.copy(
                    timeEntries = timeEntries,
                    extraHours = if (value == 0.0) current.extraHours - key else current.extraHours + (key to value),
                )
            }
        }.log(
            ActivityType.HoursChange,
            if (working) "Updated $key hours to ${value.js()}" else "Logged ${value.js()} extra hours on $key",
            month,
        )
    }

    /** Drops any override so a workday falls back to its scheduled hours. */
    fun resetDay(month: YearMonth, date: LocalDate): DataFile {
        val key = DayKeys.of(date)
        return withMonth(month) { current ->
            current.copy(
                timeEntries = current.timeEntries?.let { it - key },
                dailyHours = current.dailyHours - key,
                extraHours = current.extraHours - key,
            )
        }.log(ActivityType.HoursChange, "Reset $key to schedule", month)
    }

    /** Marks a workday vacation or sick; the same mark again (or null) clears it. */
    fun setDayException(month: YearMonth, date: LocalDate, type: DayException?): DataFile {
        if (!isWorkingDay(month, date)) return data
        val key = DayKeys.of(date)
        val existing = tracker.monthData(month).exceptions[key]
        val clearing = type == null || existing == type.wire
        return withMonth(month) { current ->
            current.copy(exceptions = if (clearing) current.exceptions - key else current.exceptions + (key to type!!.wire))
        }.log(
            ActivityType.ExceptionChange,
            if (clearing) "Cleared mark on $key" else "Marked $key as ${type!!.wire}",
            month,
        )
    }

    /** Quick entry; null when the text can't be read. */
    fun applyDayInput(month: YearMonth, date: LocalDate, text: String): DataFile? =
        when (val parsed = dev.quackbyte.punchboard.model.parseDayInput(text)) {
            null -> null
            is dev.quackbyte.punchboard.model.DayInput.Sessions -> setDayEntries(month, date, parsed.entries)
            is dev.quackbyte.punchboard.model.DayInput.Total -> setDayTotal(month, date, parsed.hours)
        }

    fun removeSession(month: YearMonth, date: LocalDate, index: Int): DataFile {
        val entries = tracker.monthData(month).timeEntries?.get(DayKeys.of(date)).orEmpty()
        if (index !in entries.indices) return data
        return setDayEntries(month, date, entries.filterIndexed { i, _ -> i != index })
    }

    /** Starts a session now, in today's period. No-op while one is running. */
    fun punchIn(at: LocalDateTime = now()): DataFile {
        val today = at.toLocalDate()
        val month = tracker.currentPeriod(today)
        if (tracker.openSession(month) != null) return data
        val entries = tracker.monthData(month).timeEntries?.get(DayKeys.of(today)).orEmpty()
        return TrackerEditor(data, now).setDayEntries(month, today, entries + TimeEntry(Clock.format(at.hour * 60 + at.minute), null))
    }

    /** Ends today's running session now. A punch within the same minute isn't a session. */
    fun punchOut(at: LocalDateTime = now()): DataFile {
        val today = at.toLocalDate()
        val month = tracker.currentPeriod(today)
        val open = tracker.openSession(month) ?: return data
        if (open.key != DayKeys.of(today)) return data
        val end = Clock.format(at.hour * 60 + at.minute)
        val entries = tracker.monthData(month).timeEntries!!.getValue(open.key)
            .mapIndexed { index, entry -> if (index == open.index) entry.copy(end = end) else entry }
            .filter { it.start != it.end }
        return setDayEntries(month, today, entries)
    }

    // Schedule (this period only).

    fun toggleWeekday(month: YearMonth, weekday: String): DataFile = withMonth(month) { current ->
        current.copy(
            selectedDays = if (weekday in current.selectedDays) current.selectedDays - weekday else current.selectedDays + weekday,
        )
    }.log(ActivityType.WeekdayToggle, "Toggled weekday ${weekday.uppercase()}", month)

    fun setHoursPerDay(month: YearMonth, value: Double): DataFile =
        withMonth(month) { it.copy(hoursPerDay = value) }.log(ActivityType.ValueChange, "Updated hours/day to ${value.js()}", month)

    fun setExtraDeduction(month: YearMonth, value: Double): DataFile {
        val next = maxOf(0.0, value.finiteOr0())
        return withMonth(month) { it.copy(extraDeduction = next) }
            .log(ActivityType.ValueChange, "Updated extra deduction to ${next.js()}", month)
    }

    // Pay settings carry forward: this period and every saved later one.

    private fun forward(month: YearMonth, transform: (TrackerData) -> TrackerData): DataFile {
        val key = MonthKeys.of(month)
        val base = withMonth(month, transform)
        return base.copy(months = base.months.mapValues { (k, v) -> if (k > key) transform(v) else v })
    }

    fun setHourlyRate(month: YearMonth, value: Double): DataFile {
        val next = maxOf(0.0, value.finiteOr0())
        return forward(month) { it.copy(hourlyRate = next) }.log(ActivityType.ValueChange, "Updated hourly rate to ${next.js()}", month)
    }

    fun setSalary(month: YearMonth, patch: (SalarySettings) -> SalarySettings, message: String): DataFile {
        val current = tracker.monthData(month)
        var next = normalizeSalary(patch(normalizeSalary(current.salary)))
        // Turning a salary on for the first time starts from the schedule.
        if (next.enabled && next.weeklyHours == 0.0) next = next.copy(weeklyHours = current.selectedDays.size * current.hoursPerDay)
        return forward(month) { it.copy(salary = next) }.log(ActivityType.ValueChange, message, month)
    }

    fun setCurrency(month: YearMonth, code: String): DataFile =
        forward(month) { it.copy(currency = code) }.log(ActivityType.ValueChange, "Updated currency to $code", month)

    fun setSecondaryCurrency(month: YearMonth, code: String): DataFile =
        forward(month) { it.copy(secondaryCurrency = code) }.log(ActivityType.ValueChange, "Updated secondary currency to $code", month)

    fun setConversionRate(month: YearMonth, value: Double): DataFile {
        val next = maxOf(0.0, value.finiteOr0())
        return forward(month) { it.copy(conversionRate = next) }.log(ActivityType.ValueChange, "Updated conversion rate to ${next.js()}", month)
    }

    fun setConversionEnabled(month: YearMonth, enabled: Boolean): DataFile =
        forward(month) { it.copy(currencyConversionEnabled = enabled) }
            .log(ActivityType.ValueChange, "${if (enabled) "Enabled" else "Disabled"} currency conversion", month)

    fun setTaxPercent(month: YearMonth, value: Double): DataFile {
        val next = clampPercent(value)
        return forward(month) { it.copy(taxPercent = next) }.log(ActivityType.ValueChange, "Updated tax to ${next.js()}%", month)
    }

    fun setDefaultHours(month: YearMonth, value: Double): DataFile {
        val next = maxOf(0.0, value.finiteOr0())
        return forward(month) { it.copy(defaultHours = next) }.log(ActivityType.ValueChange, "Updated holiday hours/day to ${next.js()}", month)
    }

    // Global settings.

    fun updateSettings(month: YearMonth, message: String, transform: (TrackerSettings) -> TrackerSettings): DataFile =
        data.copy(settings = transform(data.settings)).log(ActivityType.ValueChange, message, month)

    private fun Double.finiteOr0() = if (isFinite()) this else 0.0
}

private fun <V> Map<String, V>.with(key: String, value: V?): Map<String, V> = if (value == null) this - key else this + (key to value)

private fun <V> Map<String, V>.ifEmptyNullIf(condition: Boolean): Map<String, V>? = if (condition && isEmpty()) null else this

private val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** JavaScript's `toISOString()`: always milliseconds, always UTC. */
fun isoTimestamp(instant: Instant): String = isoFormatter.format(instant)

private fun randomSuffix(): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    return (1..6).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
}
