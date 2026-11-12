package dev.quackbyte.punchboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * Ported from the desktop's `calculations.test.ts`, case for case, so both
 * apps agree on every number.
 */
private const val EPS = 1e-9

// Periods are named after the month they end in: with start day 1,
// "2026-04" runs 1-31 March 2026, which has 22 weekdays.
private val MONTH = YearMonth.of(2026, 4)
private const val START_DAY = 1
private const val MONDAY = "2026-2-2"
private const val SATURDAY = "2026-2-7"

private fun data(
    hoursPerDay: Double = 8.0,
    defaultHours: Double = 8.0,
    exceptions: Map<String, String> = emptyMap(),
    dailyHours: Map<String, Double> = emptyMap(),
    extraHours: Map<String, Double> = emptyMap(),
    timeEntries: Map<String, List<TimeEntry>> = emptyMap(),
    salary: SalarySettings? = null,
) = TrackerData(
    selectedDays = listOf("mon", "tue", "wed", "thu", "fri"),
    hoursPerDay = hoursPerDay,
    hourlyRate = 20.0,
    currency = "EUR",
    secondaryCurrency = "USD",
    defaultHours = defaultHours,
    exceptions = exceptions,
    dailyHours = dailyHours,
    extraHours = extraHours,
    timeEntries = timeEntries,
    salary = salary,
)

private fun rules(
    breakMinutes: Double = 0.0,
    overtimeIncludesLongDays: Boolean = false,
    balanceResetsYearly: Boolean = false,
    balanceCapHours: Double = 0.0,
    balanceStartDate: String = "",
) = WorkRules(
    breakMinutes = breakMinutes,
    overtimeIncludesLongDays = overtimeIncludesLongDays,
    balanceResetsYearly = balanceResetsYearly,
    balanceCapHours = balanceCapHours,
    balanceStartDate = balanceStartDate,
)

private fun date(y: Int, monthIndex: Int, d: Int) = LocalDate.of(y, monthIndex + 1, d)

class NormalizeTest {
    @Test
    fun `fills defaults and clamps work rules`() {
        assertEquals(WorkRules(), WorkRules.from(TrackerSettings()))
        assertEquals(
            WorkRules(
                breakMinutes = 0.0,
                breakAfterHours = 24.0,
                roundingMinutes = 0,
                overtimeMultiplier = 5.0,
                overtimeIncludesLongDays = true,
                balanceWeek = false,
                carryBalance = false,
                balanceResetsYearly = true,
                balanceCapHours = 0.0,
                balanceStartDate = "",
            ),
            WorkRules.from(
                TrackerSettings(
                    breakMinutes = -10.0,
                    breakAfterHours = 99.0,
                    roundingMinutes = 7,
                    overtimeMultiplier = 9.0,
                    overtimeIncludesLongDays = true,
                    balanceWeek = false,
                    carryBalance = false,
                    balanceResetsYearly = true,
                    balanceCapHours = -3.0,
                    balanceStartDate = "21/10/2026",
                ),
            ),
        )
    }

    @Test
    fun `rounds to the nearest step`() {
        assertEquals(480, roundMinutes(487, 15))
        assertEquals(495, roundMinutes(488, 15))
        assertEquals(487, roundMinutes(487, 0))
    }

    @Test
    fun `salary defaults to hourly pay and clamps bad values`() {
        assertEquals(SalarySettings(), normalizeSalary(null))
        val clamped = normalizeSalary(SalarySettings(basis = "monthly", weeklyHours = 500.0, yearlyAmount = -1.0))
        assertEquals("weekly", clamped.basis)
        assertEquals(168.0, clamped.weeklyHours, EPS)
        assertEquals(0.0, clamped.yearlyAmount, EPS)
    }
}

class ComputePeriodTest {
    @Test
    fun `assumes the schedule for days without input`() {
        val period = computePeriod(data(), MONTH, START_DAY, rules())
        assertEquals(22, period.workingDaysCount)
        assertEquals(176.0, period.estimatedHours, EPS)
        assertEquals(176.0, period.actualHours, EPS)
        assertEquals(DaySource.Scheduled, period.byKey.getValue(MONDAY).source)
        val saturday = period.byKey.getValue(SATURDAY)
        assertFalse(saturday.isWorkingDay)
        assertEquals(0.0, saturday.hours, EPS)
        assertEquals(DaySource.None, saturday.source)
    }

    @Test
    fun `prefers sessions over manual hours`() {
        val period = computePeriod(
            data(dailyHours = mapOf(MONDAY to 3.0), timeEntries = mapOf(MONDAY to listOf(TimeEntry("09:00", "19:00")))),
            MONTH,
            START_DAY,
            rules(),
        )
        assertEquals(10.0, period.byKey.getValue(MONDAY).hours, EPS)
        assertEquals(DaySource.Sessions, period.byKey.getValue(MONDAY).source)
        assertEquals(178.0, period.actualHours, EPS)
    }

    @Test
    fun `handles sick and vacation days`() {
        val period = computePeriod(
            data(defaultHours = 7.0, exceptions = mapOf(MONDAY to "sick", "2026-2-3" to "vacation"), dailyHours = mapOf("2026-2-3" to 1.0)),
            MONTH,
            START_DAY,
            rules(),
        )
        assertEquals(1, period.sickDays)
        assertEquals(1, period.vacationDays)
        assertEquals(0.0, period.byKey.getValue(MONDAY).hours, EPS)
        assertEquals(8.0, period.byKey.getValue("2026-2-3").hours, EPS)
        assertEquals(176.0 - 8 - 8 + 8, period.actualHours, EPS)
    }

    @Test
    fun `counts days off as overtime`() {
        val period = computePeriod(data(extraHours = mapOf(SATURDAY to 4.0)), MONTH, START_DAY, rules())
        assertEquals(4.0, period.extraHoursTotal, EPS)
        assertEquals(4.0, period.overtimeHours, EPS)
        assertEquals(180.0, period.actualHours, EPS)
    }

    @Test
    fun `optionally counts long workdays as overtime`() {
        val input = data(dailyHours = mapOf(MONDAY to 10.0))
        assertEquals(0.0, computePeriod(input, MONTH, START_DAY, rules()).overtimeHours, EPS)
        assertEquals(2.0, computePeriod(input, MONTH, START_DAY, rules(overtimeIncludesLongDays = true)).overtimeHours, EPS)
    }

    @Test
    fun `applies the live clock only to the live day`() {
        val input = data(timeEntries = mapOf(MONDAY to listOf(TimeEntry("09:00", null))))
        assertEquals(0.0, computePeriod(input, MONTH, START_DAY, rules()).byKey.getValue(MONDAY).hours, EPS)
        assertEquals(3.0, computePeriod(input, MONTH, START_DAY, rules(), MONDAY to 720).byKey.getValue(MONDAY).hours, EPS)
    }

    @Test
    fun `totals breaks`() {
        val period = computePeriod(
            data(timeEntries = mapOf(MONDAY to listOf(TimeEntry("09:00", "17:30")))),
            MONTH,
            START_DAY,
            rules(breakMinutes = 30.0),
        )
        assertEquals(30, period.breakMinutesTotal)
        assertEquals(8.0, period.byKey.getValue(MONDAY).hours, EPS)
    }
}

class ComputePayTest {
    private fun pay(
        actual: Double,
        overtime: Double,
        rate: Double = 20.0,
        tax: Double = 25.0,
        deduction: Double = 50.0,
        multiplier: Double = 1.0,
        salary: SalarySettings? = null,
        weeklyHours: Double = 0.0,
    ) = computePay(actual, overtime, rate, tax, deduction, multiplier, salary, weeklyHours)

    @Test
    fun `pays every hour at the rate without a multiplier`() {
        assertEquals(
            PayResult(
                salaried = false, yearly = 0.0, rate = 20.0, effectiveRate = 20.0, regularHours = 100.0, overtimeHours = 0.0,
                unpaidOvertimeHours = 0.0, regularPay = 2000.0, overtimePay = 0.0, gross = 2000.0, tax = 500.0, deductions = 50.0, net = 1450.0,
            ),
            pay(100.0, 10.0),
        )
    }

    @Test
    fun `pays overtime at the multiplier`() {
        val result = pay(100.0, 10.0, multiplier = 1.5)
        assertEquals(1800.0, result.regularPay, EPS)
        assertEquals(300.0, result.overtimePay, EPS)
        assertEquals(2100.0, result.gross, EPS)
        assertEquals(2100.0 - 525 - 50, result.net, EPS)
    }

    @Test
    fun `never goes negative`() {
        assertEquals(0.0, pay(1.0, 0.0, deduction = 1000.0).net, EPS)
    }

    private val salary = SalarySettings(enabled = true, weeklyHours = 40.0)
    private fun salaried(actual: Double, overtime: Double, s: SalarySettings = salary, multiplier: Double = 1.5) =
        pay(actual, overtime, rate = 15.0, tax = 0.0, deduction = 0.0, multiplier = multiplier, salary = s)

    @Test
    fun `pays an hourly rate over weekly hours as a twelfth of the year`() {
        val result = salaried(120.0, 0.0)
        assertTrue(result.salaried)
        assertEquals(15.0, result.rate, EPS)
        assertEquals(31200.0, result.yearly, EPS)
        assertEquals(2600.0, result.regularPay, EPS)
        assertEquals(2600.0, result.gross, EPS)
    }

    @Test
    fun `pays a yearly amount and derives the rate from the weekly hours`() {
        val result = salaried(160.0, 0.0, salary.copy(basis = "yearly", yearlyAmount = 41600.0))
        assertEquals(41600.0 / 12, result.regularPay, 1e-6)
        assertEquals(20.0, result.rate, EPS)
    }

    @Test
    fun `doesn't change with the hours worked`() {
        val short = salaried(50.0, 0.0)
        val long = salaried(200.0, 0.0)
        assertEquals(short.gross, long.gross, EPS)
        assertEquals(2600.0 / 200, long.effectiveRate, EPS)
    }

    @Test
    fun `tracks overtime as unpaid by default`() {
        val result = salaried(180.0, 10.0)
        assertEquals(0.0, result.overtimePay, EPS)
        assertEquals(0.0, result.overtimeHours, EPS)
        assertEquals(10.0, result.unpaidOvertimeHours, EPS)
        assertEquals(2600.0, result.gross, EPS)
    }

    @Test
    fun `pays overtime on top at the implied rate, even at 1x`() {
        val paid = salaried(180.0, 10.0, salary.copy(overtimePaid = true))
        assertEquals(10 * 15 * 1.5, paid.overtimePay, EPS)
        assertEquals(2600.0 + 225, paid.gross, EPS)
        assertEquals(0.0, paid.unpaidOvertimeHours, EPS)
        assertEquals(150.0, salaried(180.0, 10.0, salary.copy(overtimePaid = true), multiplier = 1.0).overtimePay, EPS)
    }

    @Test
    fun `ignores a salary that's switched off`() {
        val result = salaried(100.0, 0.0, salary.copy(enabled = false))
        assertFalse(result.salaried)
        assertEquals(1500.0, result.gross, EPS)
    }

    @Test
    fun `pays nothing hourly on a yearly basis without contracted hours`() {
        val result = salaried(100.0, 5.0, salary.copy(basis = "yearly", yearlyAmount = 12000.0, weeklyHours = 0.0, overtimePaid = true))
        assertEquals(0.0, result.rate, EPS)
        assertEquals(1000.0, result.gross, EPS)
    }

    @Test
    fun `estimates the year by the hour from the weekly hours`() {
        assertEquals(41600.0, pay(100.0, 0.0, tax = 0.0, deduction = 0.0, weeklyHours = 40.0).yearly, EPS)
    }
}

class EstimateFinishTest {
    private val noBreak = WorkRules(breakMinutes = 0.0, breakAfterHours = 6.0)
    private val lunch = WorkRules(breakMinutes = 30.0, breakAfterHours = 6.0)
    private fun at(clock: String) = Clock.parse(clock)!!

    @Test
    fun `finishes 9 hours after a 7am start`() {
        assertEquals(FinishEstimate(at("16:00"), 360), estimateFinish(listOf(TimeEntry("07:00", null)), 9.0, noBreak, at("10:00")))
    }

    @Test
    fun `adds the automatic break the day will reach`() {
        assertEquals(at("16:30"), estimateFinish(listOf(TimeEntry("07:00", null)), 9.0, lunch, at("10:00"))?.finishMinutes)
    }

    @Test
    fun `counts time off the clock towards the break`() {
        val result = estimateFinish(listOf(TimeEntry("07:00", "11:00"), TimeEntry("11:20", null)), 9.0, lunch, at("12:00"))
        assertEquals(at("16:30"), result?.finishMinutes)
    }

    @Test
    fun `assumes a start now when off the clock`() {
        assertEquals(at("17:15"), estimateFinish(emptyList(), 8.0, noBreak, at("09:15"))?.finishMinutes)
    }

    @Test
    fun `runs past midnight`() {
        assertEquals(at("04:00") + 1440, estimateFinish(listOf(TimeEntry("20:00", null)), 8.0, noBreak, at("21:00"))?.finishMinutes)
    }

    @Test
    fun `is null once the target is met or without one`() {
        assertNull(estimateFinish(listOf(TimeEntry("07:00", "16:00")), 9.0, noBreak, at("17:00")))
        assertNull(estimateFinish(emptyList(), 0.0, noBreak, at("09:00")))
    }
}

class WeeklyDayTargetTest {
    // The week of Monday 2 – Sunday 8 March 2026, on a 45h, 9h-a-day schedule.
    private val weekDays = listOf("2026-2-2", "2026-2-3", "2026-2-4", "2026-2-5", "2026-2-6")
    private val thursday = date(2026, 2, 5)
    private val friday = date(2026, 2, 6)
    private fun week(exceptions: Map<String, String> = emptyMap(), dailyHours: Map<String, Double> = emptyMap()) =
        computePeriod(data(hoursPerDay = 9.0, exceptions = exceptions, dailyHours = dailyHours), MONTH, START_DAY, rules())
            .days.filter { it.date >= date(2026, 2, 2) && it.date <= date(2026, 2, 8) }
    private fun logged(vararg hours: Double) = hours.mapIndexed { index, value -> weekDays[index] to value }.toMap()

    @Test
    fun `leaves 5h for Friday after 40h Monday to Thursday`() {
        assertEquals(5.0, weeklyDayTarget(week(dailyHours = logged(10.0, 10.0, 10.0, 10.0)), friday, 45.0, 5), EPS)
    }

    @Test
    fun `asks for more when the week is short`() {
        assertEquals(13.0, weeklyDayTarget(week(dailyHours = logged(8.0, 8.0, 8.0, 8.0)), friday, 45.0, 5), EPS)
    }

    @Test
    fun `shares what's left over the days still to come`() {
        assertEquals(12.5, weeklyDayTarget(week(dailyHours = logged(10.0, 10.0, 0.0)), thursday, 45.0, 5), EPS)
    }

    @Test
    fun `is the plain daily hours on a steady week`() {
        assertEquals(9.0, weeklyDayTarget(week(), thursday, 45.0, 5), EPS)
    }

    @Test
    fun `uses contracted hours that differ from the schedule`() {
        assertEquals(8.0, weeklyDayTarget(week(dailyHours = logged(8.0, 8.0, 8.0, 8.0)), friday, 40.0, 5), EPS)
    }

    @Test
    fun `credits sick days with their share and vacation with holiday hours`() {
        val days = week(exceptions = mapOf("2026-2-2" to "sick", "2026-2-3" to "vacation"), dailyHours = mapOf("2026-2-4" to 9.0, "2026-2-5" to 9.0))
        assertEquals(10.0, weeklyDayTarget(days, friday, 45.0, 5), EPS)
    }

    @Test
    fun `leaves days marked off later in the week out of the share`() {
        assertEquals(10.0, weeklyDayTarget(week(exceptions = mapOf("2026-2-6" to "vacation")), thursday, 45.0, 5), EPS)
    }

    @Test
    fun `counts days settled by the hour as their share`() {
        val days = week(dailyHours = logged(12 + 5 / 60.0, 11.5, 10 + 55 / 60.0, 8 + 52 / 60.0))
        assertEquals(1 + 38 / 60.0, weeklyDayTarget(days, friday, 45.0, 5), 1e-6)
        assertEquals(7 + 13 / 60.0, weeklyDayTarget(days, friday, 45.0, 5, 0.0) { it.key == "2026-2-2" || it.key == "2026-2-3" }, 1e-6)
    }

    @Test
    fun `is 0 on days off, marked days, and once the week is done`() {
        assertEquals(0.0, weeklyDayTarget(week(), date(2026, 2, 7), 45.0, 5), EPS)
        assertEquals(0.0, weeklyDayTarget(week(exceptions = mapOf("2026-2-6" to "sick")), friday, 45.0, 5), EPS)
        assertEquals(0.0, weeklyDayTarget(week(dailyHours = logged(12.0, 12.0, 12.0, 12.0)), friday, 45.0, 5), EPS)
    }
}

class VacationTest {
    // Payslips from the 21st: "2026-01" runs 21 Dec 2025 – 20 Jan 2026.
    private fun days(month: YearMonth, exceptions: Map<String, String>) = computePeriod(data(exceptions = exceptions), month, 21, rules()).days
    private val today = date(2026, 0, 14)
    private val jan = YearMonth.of(2026, 1)

    @Test
    fun `splits taken and booked days around today`() {
        val result = countVacation(days(jan, mapOf("2026-0-12" to "vacation", "2026-0-14" to "vacation", "2026-0-15" to "vacation")), leaveYearRange(2026, 1), today)
        assertEquals(VacationCount(2, 1), result)
    }

    @Test
    fun `only counts days in the calendar year`() {
        assertEquals(VacationCount(1, 0), countVacation(days(jan, mapOf("2025-11-22" to "vacation", "2026-0-5" to "vacation")), leaveYearRange(2026, 1), today))
    }

    @Test
    fun `ignores sick days and weekends`() {
        assertEquals(VacationCount(0, 0), countVacation(days(jan, mapOf("2026-0-6" to "sick", "2026-0-10" to "vacation")), leaveYearRange(2026, 1), today))
    }

    @Test
    fun `adds up days across periods without double counting`() {
        val january = days(jan, mapOf("2026-0-5" to "vacation"))
        val february = days(YearMonth.of(2026, 2), mapOf("2026-0-26" to "vacation"))
        assertEquals(VacationCount(1, 1), countVacation(january + february + january, leaveYearRange(2026, 1), today))
    }

    @Test
    fun `leave years run from the start month to a year on`() {
        assertEquals(LeaveYear(LocalDate.of(2026, 4, 1), LocalDate.of(2027, 4, 1)), leaveYearRange(2026, 4))
        assertEquals(LocalDate.of(2026, 12, 1), leaveYearRange(2026, 99).start)
        assertEquals("2026", formatLeaveYear(leaveYearRange(2026, 1)))
    }

    @Test
    fun `counts vacation in an April-March leave year`() {
        val march = computePeriod(data(exceptions = mapOf("2026-2-2" to "vacation", "2026-2-31" to "vacation")), MONTH, START_DAY, rules()).days
        assertEquals(VacationCount(2, 0), countVacation(march, leaveYearRange(2025, 4), date(2026, 5, 1)))
        assertEquals(VacationCount(0, 0), countVacation(march, leaveYearRange(2026, 4), date(2026, 5, 1)))
    }
}

class BalanceTest {
    private val nextMonday = date(2026, 2, 9)
    private fun days(defaultHours: Double = 8.0, exceptions: Map<String, String> = emptyMap(), dailyHours: Map<String, Double> = emptyMap(), extraHours: Map<String, Double> = emptyMap(), longDays: Boolean = false) =
        computePeriod(data(hoursPerDay = 9.0, defaultHours = defaultHours, exceptions = exceptions, dailyHours = dailyHours, extraHours = extraHours), MONTH, START_DAY, rules(overtimeIncludesLongDays = longDays)).days
    private fun firstWeek(vararg hours: Double) = hours.mapIndexed { index, value -> "2026-2-${index + 2}" to value }.toMap()
    private fun weekOf(monday: LocalDate) = days().filter { it.date >= monday && it.date < monday.plusDays(7) }

    @Test
    fun `banks a 50h week as 5h, which leaves 40h for the next`() {
        val balance = hourBalance(days(dailyHours = firstWeek(10.0, 10.0, 10.0, 10.0, 10.0)), nextMonday, 9.0)
        assertEquals(5.0, balance, EPS)
        assertEquals(8.0, weeklyDayTarget(weekOf(nextMonday), nextMonday, 45.0, 5, balance), EPS)
    }

    @Test
    fun `owes a short week, making the next one longer`() {
        val balance = hourBalance(days(dailyHours = firstWeek(8.0, 8.0, 8.0, 8.0, 8.0)), nextMonday, 9.0)
        assertEquals(-5.0, balance, EPS)
        assertEquals(10.0, weeklyDayTarget(weekOf(nextMonday), nextMonday, 45.0, 5, balance), EPS)
    }

    @Test
    fun `is neutral on scheduled, sick and matching vacation days`() {
        assertEquals(0.0, hourBalance(days(defaultHours = 9.0, exceptions = mapOf("2026-2-2" to "sick", "2026-2-3" to "vacation")), nextMonday, 9.0), EPS)
    }

    @Test
    fun `banks hours on days off and ignores days from before on`() {
        assertEquals(4.0, hourBalance(days(extraHours = mapOf("2026-2-7" to 4.0), dailyHours = mapOf("2026-2-10" to 12.0)), nextMonday, 9.0), EPS)
    }

    @Test
    fun `weekly hours use a salary's contracted hours, else the schedule`() {
        assertEquals(45.0, weeklyHoursOf(data(hoursPerDay = 9.0)), EPS)
        assertEquals(37.5, weeklyHoursOf(data(salary = SalarySettings(enabled = true, weeklyHours = 37.5))), EPS)
    }

    // Dec 2025 – Jan 2026 periods on a 45h week, 9h a day, from the 1st.
    private fun source(month: YearMonth, dailyHours: Map<String, Double>) =
        BalanceSource(computePeriod(data(hoursPerDay = 9.0, dailyHours = dailyHours), month, START_DAY, rules()).days, 9.0, false)
    private val sources = listOf(source(YearMonth.of(2026, 1), mapOf("2025-11-1" to 10.0)), source(YearMonth.of(2026, 2), mapOf("2026-0-5" to 10.0)))
    private val monday = date(2026, 0, 12)

    @Test
    fun `carried balance adds up every period before the week`() {
        assertEquals(2.0, carriedBalance(sources, monday, rules()), EPS)
    }

    @Test
    fun `carried balance starts from zero on 1 January when it resets`() {
        assertEquals(1.0, carriedBalance(sources, monday, rules(balanceResetsYearly = true)), EPS)
    }

    @Test
    fun `carried balance stays within the cap either way`() {
        assertEquals(1.5, carriedBalance(sources, monday, rules(balanceCapHours = 1.5)), EPS)
        assertEquals(-4.0, carriedBalance(listOf(source(YearMonth.of(2026, 2), mapOf("2026-0-5" to 0.0))), monday, rules(balanceCapHours = 4.0)), EPS)
    }

    @Test
    fun `carried balance skips days before the start date`() {
        assertEquals(1.0, carriedBalance(sources, monday, rules(balanceStartDate = "2026-01-05")), EPS)
        assertEquals(0.0, carriedBalance(sources, monday, rules(balanceStartDate = "2026-01-06")), EPS)
    }

    @Test
    fun `carried balance starts from the later of the start date and 1 January`() {
        assertEquals(1.0, carriedBalance(sources, monday, rules(balanceResetsYearly = true, balanceStartDate = "2025-06-01")), EPS)
    }

    // balanceNow: a 45h week with 1h banked on each of Mon-Thu, so Friday's target is 5h.
    private val friday = date(2026, 2, 6)
    private val week = mapOf("2026-2-2" to 10.0, "2026-2-3" to 10.0, "2026-2-4" to 10.0, "2026-2-5" to 10.0)
    private fun now(daily: Map<String, Double>, defaultHours: Double = 8.0, exceptions: Map<String, String> = emptyMap(), extra: Map<String, Double> = emptyMap(), overtimePaid: Boolean = false) =
        listOf(BalanceSource(days(defaultHours = defaultHours, exceptions = exceptions, dailyHours = daily, extraHours = extra), 9.0, overtimePaid))
    private fun friday(hours: Double) = week + ("2026-2-6" to hours)

    @Test
    fun `leaves today out until its target is met`() {
        assertEquals(4.0, balanceNow(now(friday(3.0)), friday, rules(), 5.0, true), EPS)
    }

    @Test
    fun `counts today once its target is met`() {
        assertEquals(0.0, balanceNow(now(friday(5.0)), friday, rules(), 5.0, true), EPS)
        assertEquals(1.0, balanceNow(now(friday(6.0)), friday, rules(), 5.0, true), EPS)
    }

    @Test
    fun `doesn't take a day with nothing logged for its schedule`() {
        assertEquals(4.0, balanceNow(now(week), friday, rules(), 5.0, true), EPS)
        assertEquals(4.0, balanceNow(now(week), friday, rules(), 0.0, true), EPS)
    }

    @Test
    fun `counts a running session towards the target and the balance`() {
        assertEquals(4.0, balanceNow(now(friday(3.0)), friday, rules(), 5.0, true, 1.0), EPS)
        assertEquals(1.0, balanceNow(now(friday(3.0)), friday, rules(), 5.0, true, 3.0), EPS)
    }

    @Test
    fun `waits for the punch-out when overtime is paid on top`() {
        assertEquals(4.0, balanceNow(now(friday(3.0), overtimePaid = true), friday, rules(), 5.0, true, 3.0), EPS)
    }

    @Test
    fun `counts a day that isn't a plain workday at once`() {
        assertEquals(6.0, balanceNow(now(week, extra = mapOf("2026-2-7" to 2.0)), date(2026, 2, 7), rules(), 0.0, false), EPS)
        assertEquals(4.0, balanceNow(now(week, defaultHours = 9.0, exceptions = mapOf("2026-2-6" to "vacation")), friday, rules(), 0.0, false), EPS)
    }

    @Test
    fun `stays within the cap with a running session`() {
        assertEquals(2.0, balanceNow(now(friday(3.0)), friday, rules(balanceCapHours = 2.0), 5.0, true, 9.0), EPS)
    }

    @Test
    fun `keeps the year's balance on 31 December when it resets yearly`() {
        val december = listOf(
            BalanceSource(
                computePeriod(data(hoursPerDay = 9.0, dailyHours = mapOf("2025-11-1" to 10.0, "2025-11-31" to 10.0)), YearMonth.of(2026, 1), START_DAY, rules()).days,
                9.0,
                false,
            ),
        )
        assertEquals(2.0, balanceNow(december, date(2025, 11, 31), rules(balanceResetsYearly = true), 9.0, true), EPS)
    }

    @Test
    fun `balance sources leave out hourly periods and share a salary's hours`() {
        assertNull(balanceSource(data(hoursPerDay = 9.0), emptyList()))
        assertNull(balanceSource(data(salary = SalarySettings(enabled = false, weeklyHours = 45.0)), emptyList()))
        assertEquals(BalanceSource(emptyList(), 9.0, true), balanceSource(data(salary = SalarySettings(enabled = true, weeklyHours = 45.0, overtimePaid = true)), emptyList()))
    }

    @Test
    fun `switching to a salary mid-history only banks from the salaried periods`() {
        val hourly = data(hoursPerDay = 9.0, dailyHours = mapOf("2026-1-2" to 14.0))
        val salaried = data(hoursPerDay = 9.0, salary = SalarySettings(enabled = true, weeklyHours = 45.0), dailyHours = mapOf("2026-2-2" to 10.0))
        val sources = listOfNotNull(
            balanceSource(hourly, computePeriod(hourly, YearMonth.of(2026, 3), START_DAY, rules()).days),
            balanceSource(salaried, computePeriod(salaried, MONTH, START_DAY, rules()).days),
        )
        assertEquals(1.0, carriedBalance(sources, date(2026, 3, 1), rules()), EPS)
    }

    @Test
    fun `doesn't bank paid overtime`() {
        val plain = days(dailyHours = mapOf("2026-2-2" to 11.0), extraHours = mapOf("2026-2-7" to 4.0))
        assertEquals(2.0, hourBalance(plain, nextMonday, 9.0, overtimePaid = true), EPS)
        assertEquals(6.0, hourBalance(plain, nextMonday, 9.0), EPS)
        val long = days(dailyHours = mapOf("2026-2-2" to 11.0), extraHours = mapOf("2026-2-7" to 4.0), longDays = true)
        assertEquals(0.0, hourBalance(long, nextMonday, 9.0, overtimePaid = true), EPS)
    }
}
