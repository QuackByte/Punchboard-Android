package dev.quackbyte.punchboard.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Pure hour and pay calculations, ported from the desktop's
 * `calculations.ts` (and tested against its cases). Everything is
 * deterministic so the time card, the year chart, exports and reminders
 * all agree.
 */

fun normalizeSalary(raw: SalarySettings?): SalarySettings {
    fun amount(value: Double?, maxValue: Double) =
        value?.takeIf { it.isFinite() }?.coerceIn(0.0, maxValue) ?: 0.0
    return SalarySettings(
        enabled = raw?.enabled == true,
        basis = if (raw?.basis == "yearly") "yearly" else "weekly",
        weeklyHours = amount(raw?.weeklyHours, 168.0),
        yearlyAmount = amount(raw?.yearlyAmount, Double.MAX_VALUE),
        overtimePaid = raw?.overtimePaid == true,
    )
}

data class SalaryBreakdown(val yearly: Double, val perPeriod: Double, val rate: Double)

/** A salary's gross per payslip (a twelfth of the year) and the hourly rate it implies. */
fun salaryBreakdown(salary: SalarySettings, hourlyRate: Double): SalaryBreakdown {
    val yearlyHours = salary.weeklyHours * 52
    val yearly = if (salary.basis == "yearly") salary.yearlyAmount else hourlyRate * yearlyHours
    val rate = if (salary.basis == "yearly") (if (yearlyHours > 0) yearly / yearlyHours else 0.0) else hourlyRate
    return SalaryBreakdown(yearly, yearly / 12, rate)
}

data class FinishEstimate(
    /** Minutes since midnight; past 1440 when it runs into tomorrow. */
    val finishMinutes: Int,
    val remainingMinutes: Int,
)

/**
 * When today's target is reached if the clock runs from [nowMinutes] on,
 * automatic break included. Null once the target is met or without one.
 */
fun estimateFinish(entries: List<TimeEntry>, targetHours: Double, rules: WorkRules, nowMinutes: Int): FinishEstimate? {
    val target = jsRound(targetHours * 60).toInt()
    if (target <= 0) return null
    val pending = if (entries.any { it.isRunning }) entries else entries + TimeEntry(Clock.format(nowMinutes), null)
    val exact = rules.copy(roundingMinutes = 0)
    fun workedAt(minutes: Int) = workedTime(pending, exact, minutes).workedMinutes

    if (workedAt(nowMinutes) >= target) return null
    var finish = nowMinutes
    repeat(3) {
        val missing = target - workedAt(finish)
        if (missing <= 0) return@repeat
        finish += missing
    }
    return FinishEstimate(finish, finish - nowMinutes)
}

enum class DaySource { Sessions, Manual, Scheduled, None }

data class DayResult(
    val key: String,
    val date: LocalDate,
    val isWorkingDay: Boolean,
    val exception: DayException?,
    /** Paid hours for the day. */
    val hours: Double,
    /** Of [hours], how many count as overtime. */
    val overtimeHours: Double,
    /** Minutes on the clock from sessions, before the break. */
    val clockedMinutes: Int,
    val breakMinutes: Int,
    val source: DaySource,
)

data class PeriodResult(
    val days: List<DayResult>,
    val workingDaysCount: Int,
    val vacationDays: Int,
    val sickDays: Int,
    val actualHours: Double,
    val estimatedHours: Double,
    val extraHoursTotal: Double,
    val overtimeHours: Double,
    val breakMinutesTotal: Int,
) {
    val byKey: Map<String, DayResult> = days.associateBy { it.key }
}

fun TrackerData.isWorkday(date: LocalDate) = WeekdayKeys.of(date.dayOfWeek) in selectedDays

/** Every day of a payslip period: workday or not, the hours it counts for and its overtime. */
fun computePeriod(
    data: TrackerData,
    endMonth: YearMonth,
    payslipStartDay: Int,
    rules: WorkRules,
    live: Pair<String, Int>? = null,
): PeriodResult {
    val timeEntries = data.timeEntries.orEmpty()
    val days = mutableListOf<DayResult>()
    var workingDays = 0
    var vacation = 0
    var sick = 0
    var actual = 0.0
    var extra = 0.0
    var overtime = 0.0
    var breaks = 0

    val range = PayslipPeriods.range(endMonth, payslipStartDay)
    var date = range.start
    while (date <= range.endInclusive) {
        val key = DayKeys.of(date)
        val isWorkingDay = data.isWorkday(date)
        val exception = if (isWorkingDay) DayException.fromWire(data.exceptions[key]) else null
        val entries = timeEntries[key].orEmpty()
        val worked = if (entries.isNotEmpty()) {
            workedTime(entries, rules, live?.takeIf { it.first == key }?.second)
        } else {
            null
        }
        val sessionHours = (worked?.workedMinutes ?: 0) / 60.0

        var hours = 0.0
        var source = DaySource.None
        var dayOvertime = 0.0

        if (isWorkingDay) {
            workingDays++
            when {
                exception == DayException.Sick -> sick++
                exception == DayException.Vacation -> {
                    vacation++
                    val logged = if (worked != null) sessionHours else clampHours(data.dailyHours[key] ?: 0.0)
                    hours = data.defaultHours + logged
                    source = when {
                        worked != null -> DaySource.Sessions
                        data.dailyHours.containsKey(key) -> DaySource.Manual
                        else -> DaySource.Scheduled
                    }
                }
                worked != null -> {
                    hours = sessionHours
                    source = DaySource.Sessions
                }
                data.dailyHours.containsKey(key) -> {
                    hours = clampHours(data.dailyHours.getValue(key))
                    source = DaySource.Manual
                }
                else -> {
                    hours = data.hoursPerDay
                    source = DaySource.Scheduled
                }
            }
            if (rules.overtimeIncludesLongDays && exception == null) {
                dayOvertime = max(0.0, hours - data.hoursPerDay)
            }
        } else {
            hours = if (worked != null) sessionHours else clampHours(data.extraHours[key] ?: 0.0)
            source = when {
                worked != null -> DaySource.Sessions
                hours > 0 -> DaySource.Manual
                else -> DaySource.None
            }
            extra += hours
            dayOvertime = hours
        }

        val result = DayResult(
            key = key,
            date = date,
            isWorkingDay = isWorkingDay,
            exception = exception,
            hours = hours,
            overtimeHours = dayOvertime,
            clockedMinutes = worked?.clockedMinutes ?: 0,
            breakMinutes = worked?.breakMinutes ?: 0,
            source = source,
        )
        days += result
        actual += hours
        overtime += dayOvertime
        breaks += result.breakMinutes
        date = date.plusDays(1)
    }

    return PeriodResult(
        days = days,
        workingDaysCount = workingDays,
        vacationDays = vacation,
        sickDays = sick,
        actualHours = actual,
        estimatedHours = workingDays * data.hoursPerDay,
        extraHoursTotal = extra,
        overtimeHours = overtime,
        breakMinutesTotal = breaks,
    )
}

data class PayResult(
    val salaried: Boolean,
    /** The year's gross: the salary, or by the hour an estimate from the weekly hours. */
    val yearly: Double,
    val rate: Double,
    /** Gross over hours actually worked (0 without hours). */
    val effectiveRate: Double,
    val regularHours: Double,
    val overtimeHours: Double,
    /** Overtime a salary doesn't pay for, tracked only. */
    val unpaidOvertimeHours: Double,
    val regularPay: Double,
    val overtimePay: Double,
    val gross: Double,
    val tax: Double,
    val deductions: Double,
    val net: Double,
)

/**
 * Hours into an estimated payslip. By the hour, overtime is only paid
 * differently above 1×. On a salary the gross is fixed, and overtime is
 * either paid on top at the implied rate × the multiplier or tracked unpaid.
 */
fun computePay(
    actualHours: Double,
    periodOvertimeHours: Double,
    hourlyRate: Double,
    taxPercent: Double,
    extraDeduction: Double,
    overtimeMultiplier: Double,
    salary: SalarySettings? = null,
    weeklyHours: Double = 0.0,
): PayResult {
    val multiplier = max(1.0, if (overtimeMultiplier == 0.0 || overtimeMultiplier.isNaN()) 1.0 else overtimeMultiplier)
    val salaried = salary?.enabled == true

    var rate = hourlyRate
    var yearly = hourlyRate * weeklyHours * 52
    val overtimeHours: Double
    var unpaid = 0.0
    val regularPay: Double

    if (salaried) {
        val breakdown = salaryBreakdown(salary!!, hourlyRate)
        rate = breakdown.rate
        yearly = breakdown.yearly
        if (salary.overtimePaid) {
            overtimeHours = periodOvertimeHours
        } else {
            overtimeHours = 0.0
            unpaid = periodOvertimeHours
        }
        regularPay = breakdown.perPeriod
    } else {
        overtimeHours = if (multiplier > 1) periodOvertimeHours else 0.0
        regularPay = max(0.0, actualHours - overtimeHours) * rate
    }

    val regularHours = max(0.0, actualHours - overtimeHours)
    val overtimePay = overtimeHours * rate * multiplier
    val gross = regularPay + overtimePay
    val tax = gross * (clampPercent(taxPercent) / 100)
    val deductions = max(0.0, extraDeduction)

    return PayResult(
        salaried = salaried,
        yearly = yearly,
        rate = rate,
        effectiveRate = if (actualHours > 0) gross / actualHours else 0.0,
        regularHours = regularHours,
        overtimeHours = overtimeHours,
        unpaidOvertimeHours = unpaid,
        regularPay = regularPay,
        overtimePay = overtimePay,
        gross = gross,
        tax = tax,
        deductions = deductions,
        net = max(0.0, gross - tax - deductions),
    )
}

fun computePay(period: PeriodResult, data: TrackerData, rules: WorkRules): PayResult = computePay(
    actualHours = period.actualHours,
    periodOvertimeHours = period.overtimeHours,
    hourlyRate = data.hourlyRate,
    taxPercent = data.taxPercent,
    extraDeduction = data.extraDeduction,
    overtimeMultiplier = rules.overtimeMultiplier,
    salary = normalizeSalary(data.salary),
    weeklyHours = weeklyHoursOf(data),
)

/**
 * Today's target with the week balanced: the weekly hours minus what earlier
 * days already count for, shared over today and the workdays still to come.
 * 0 when today isn't a plain workday. [settled] days were paid some other
 * way and count as exactly their share.
 */
fun weeklyDayTarget(
    week: List<DayResult>,
    today: LocalDate,
    weeklyHours: Double,
    workdaysPerWeek: Int,
    carriedHours: Double = 0.0,
    settled: (DayResult) -> Boolean = { false },
): Double {
    val todayResult = week.firstOrNull { it.date == today }
    if (todayResult == null || !todayResult.isWorkingDay || todayResult.exception != null) return 0.0

    val share = if (workdaysPerWeek > 0) weeklyHours / workdaysPerWeek else 0.0
    fun credit(day: DayResult): Double {
        if (settled(day)) return if (day.isWorkingDay) share else 0.0
        return if (day.exception == DayException.Sick) share else day.hours
    }

    var counted = 0.0
    var daysLeft = 0
    for (day in week) {
        when {
            day.date < today -> counted += credit(day)
            day.isWorkingDay && day.exception == null -> daysLeft++
            day.date > today && day.exception != null -> counted += credit(day)
        }
    }
    return max(0.0, weeklyHours - carriedHours - counted) / max(1, daysLeft)
}

/** Weekly hours: a salary's contracted hours, else the schedule's. */
fun weeklyHoursOf(data: TrackerData): Double {
    val salary = normalizeSalary(data.salary)
    return if (salary.enabled && salary.weeklyHours > 0) salary.weeklyHours else data.selectedDays.size * data.hoursPerDay
}

/**
 * Hours banked (or owed) on days before [before] (and from [from]): a workday
 * counts what it's worth minus its share, a sick day nothing, days off what
 * was worked. With [overtimePaid], overtime was paid, so it doesn't bank.
 */
fun hourBalance(
    days: List<DayResult>,
    before: LocalDate,
    share: Double,
    from: LocalDate? = null,
    overtimePaid: Boolean = false,
): Double {
    var balance = 0.0
    for (day in days) {
        if (day.date >= before || (from != null && day.date < from)) continue
        val hours = if (overtimePaid) day.hours - day.overtimeHours else day.hours
        if (!day.isWorkingDay) balance += hours
        else if (day.exception != DayException.Sick) balance += hours - share
    }
    return balance
}

/** A payslip period as the carried balance sees it. */
data class BalanceSource(val days: List<DayResult>, val share: Double, val overtimePaid: Boolean)

/** A period's contribution to the carried balance; null unless salaried (by the hour every hour was paid). */
fun balanceSource(data: TrackerData, days: List<DayResult>): BalanceSource? {
    val salary = normalizeSalary(data.salary)
    val workdays = data.selectedDays.size
    if (!salary.enabled || workdays == 0) return null
    return BalanceSource(days, weeklyHoursOf(data) / workdays, salary.overtimePaid)
}

/**
 * The balance carried into the days before [before] (exclusive): counted
 * from the start date and, when it resets yearly, from 1 January of
 * [yearOf]'s year, whichever is later, and kept within the cap.
 */
fun carriedBalance(
    sources: List<BalanceSource>,
    before: LocalDate,
    rules: WorkRules,
    yearOf: LocalDate = before,
): Double {
    val starts = listOfNotNull(
        if (rules.balanceResetsYearly) LocalDate.of(yearOf.year, 1, 1) else null,
        rules.balanceStartDate.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    )
    val from = starts.maxOrNull()
    val balance = sources.sumOf { hourBalance(it.days, before, it.share, from, it.overtimePaid) }
    val cap = rules.balanceCapHours
    return if (cap > 0) min(cap, max(-cap, balance)) else balance
}

/**
 * The balance as it stands today. Today joins once its target is met (a day
 * that isn't a plain workday joins at once). [runningHours] is what a
 * running session has added so far; with overtime paid on top it waits for
 * the punch-out.
 */
fun balanceNow(
    sources: List<BalanceSource>,
    today: LocalDate,
    rules: WorkRules,
    targetHours: Double,
    isWorkday: Boolean,
    runningHours: Double = 0.0,
): Double {
    val period = sources.firstOrNull { source -> source.days.any { it.date == today } }
    val day = period?.days?.firstOrNull { it.date == today }
    val running = if (period?.overtimePaid == true) 0.0 else runningHours

    val logged = (if (day != null && day.source != DaySource.Scheduled) day.hours else 0.0) + running
    val counts = !isWorkday || (logged > 0 && logged >= targetHours - 1e-9)
    if (!counts) return carriedBalance(sources, today, rules)

    val withRunning = sources.map { source ->
        if (source === period) {
            source.copy(days = source.days.map { if (it === day) it.copy(hours = it.hours + running) else it })
        } else {
            source
        }
    }
    // Up to the end of today, keeping today's year for a yearly reset.
    return carriedBalance(withRunning, today.plusDays(1), rules, yearOf = today)
}

data class LeaveYear(val start: LocalDate, val endExclusive: LocalDate)

/** The leave year starting in [year]: from its start month to a year on. */
fun leaveYearRange(year: Int, startMonth: Int): LeaveYear {
    val month = startMonth.coerceIn(1, 12)
    val start = LocalDate.of(year, month, 1)
    return LeaveYear(start, start.plusYears(1))
}

/** "2026" for a calendar year, else e.g. "Apr 2026 – Mar 2027". */
fun formatLeaveYear(range: LeaveYear): String {
    if (range.start.monthValue == 1) return range.start.year.toString()
    val last = YearMonth.from(range.endExclusive).minusMonths(1)
    val first = YearMonth.from(range.start)
    return "${DateText.monthShort(first)} ${first.year} – ${DateText.monthShort(last)} ${last.year}"
}

data class VacationCount(val taken: Int, val booked: Int)

/** Vacation workdays in [range], taken (up to today) and booked (after), each counted once. */
fun countVacation(days: List<DayResult>, range: LeaveYear, today: LocalDate): VacationCount {
    val seen = mutableSetOf<String>()
    var taken = 0
    var booked = 0
    for (day in days) {
        if (day.exception != DayException.Vacation) continue
        if (day.date < range.start || day.date >= range.endExclusive) continue
        if (!seen.add(day.key)) continue
        if (day.date <= today) taken++ else booked++
    }
    return VacationCount(taken, booked)
}

fun mondayOf(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

/** ISO week number, as the time card's week column shows. */
fun isoWeek(date: LocalDate): Int = date.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)

fun Double.nearlyZero() = abs(this) < 1.0 / 60
