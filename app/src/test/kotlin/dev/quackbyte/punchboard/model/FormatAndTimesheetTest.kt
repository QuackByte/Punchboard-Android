package dev.quackbyte.punchboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** Ported from the desktop's `utils.test.ts` and `timesheet.test.ts`. */
class ParseTest {
    @Test
    fun `parses clocks`() {
        mapOf("9" to 540, "9:30" to 570, "0930" to 570, "9.30" to 570, "9h30" to 570, "5pm" to 1020, "12am" to 0, "12pm" to 720, " 23:59 " to 1439)
            .forEach { (input, minutes) -> assertEquals(input, minutes, Clock.parse(input)) }
        listOf("", "24:00", "9:60", "13pm", "noon").forEach { assertNull(it, Clock.parse(it)) }
    }

    @Test
    fun `parses hours`() {
        mapOf("7.5" to 7.5, "9h 40m" to 9 + 40 / 60.0, "9h" to 9.0, "40m" to 40 / 60.0, "08:30 to 18:45" to 10.25, "8-12" to 4.0)
            .forEach { (input, hours) -> assertEquals(input, hours, parseHoursInput(input)!!, 1e-9) }
        listOf("", "abc", "18-8", "9h 75m").forEach { assertNull(it, parseHoursInput(it)) }
    }

    @Test
    fun `parses a list of sessions and sorts them`() {
        assertEquals(
            DayInput.Sessions(listOf(TimeEntry("09:00", "13:00"), TimeEntry("14:30", "18:00"))),
            parseDayInput("14:30-18, 9-13"),
        )
    }

    @Test
    fun `accepts to, semicolons and and as separators`() {
        val result = parseDayInput("8:45 to 12:15; 13-17:30 and 19-20")
        assertEquals(3, (result as DayInput.Sessions).entries.size)
    }

    @Test
    fun `parses a single total`() {
        assertEquals(DayInput.Total(7.5), parseDayInput("7h 30m"))
    }

    @Test
    fun `rejects nonsense and zero-length sessions`() {
        assertNull(parseDayInput("hello"))
        assertNull(parseDayInput("9-9"))
        assertNull(parseDayInput("25"))
    }

    @Test
    fun `sums overnight and running sessions`() {
        assertEquals(4.0, sumEntriesHours(listOf(TimeEntry("22:00", "02:00"))), 1e-9)
        assertEquals(0.0, sumEntriesHours(listOf(TimeEntry("09:00", null))), 1e-9)
        assertEquals(1.0, sumEntriesHours(listOf(TimeEntry("09:00", null)), 600), 1e-9)
    }

    @Test
    fun `formats durations`() {
        mapOf(8.0 to "8h", 7.5 to "7h 30m", 0.25 to "15m", 0.0 to "0h", -3.0 to "0h")
            .forEach { (hours, text) -> assertEquals(text, formatDuration(hours)) }
    }

    @Test
    fun `payslip ranges and the current period`() {
        val march = PayslipPeriods.range(YearMonth.of(2026, 3), 21)
        assertEquals("2026-1-21", DayKeys.of(march.start))
        assertEquals("2026-2-20", DayKeys.of(march.endInclusive))
        val clamped = PayslipPeriods.range(YearMonth.of(2026, 3), 31)
        assertEquals("2026-1-28", DayKeys.of(clamped.start))
        assertEquals("2026-2-30", DayKeys.of(clamped.endInclusive))
        assertEquals(YearMonth.of(2026, 10), PayslipPeriods.endMonthFor(LocalDate.of(2026, 10, 1), 21))
        assertEquals(YearMonth.of(2026, 11), PayslipPeriods.endMonthFor(LocalDate.of(2026, 10, 21), 21))
        assertEquals(YearMonth.of(2027, 1), PayslipPeriods.endMonthFor(LocalDate.of(2026, 12, 25), 21))
    }

    @Test
    fun `names leave years`() {
        assertEquals("2026", formatLeaveYear(LeaveYear(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1))))
        assertEquals("Apr 2026 – Mar 2027", formatLeaveYear(LeaveYear(LocalDate.of(2026, 4, 1), LocalDate.of(2027, 4, 1))))
    }
}

class TimesheetTest {
    private val data = TrackerData(
        hoursPerDay = 8.0,
        hourlyRate = 20.0,
        currency = "EUR",
        secondaryCurrency = "USD",
        taxPercent = 10.0,
        exceptions = mapOf("2026-2-3" to "sick"),
        timeEntries = mapOf(
            "2026-2-2" to listOf(TimeEntry("09:00", "13:00", "Client \"A\", kickoff"), TimeEntry("14:00", "18:30")),
        ),
    )
    private val rules = WorkRules(breakMinutes = 30.0, overtimeMultiplier = 1.5)

    // With start day 1, "2026-04" is March 2026.
    private val period = computePeriod(data, YearMonth.of(2026, 4), 1, rules)
    private val pay = computePay(period.actualHours, period.overtimeHours, 20.0, 10.0, 0.0, 1.5)
    private val rows = buildTimesheetRows(period, data.timeEntries!!)
    private val meta = TimesheetMeta("March 2026", "1 Mar 2026 – 31 Mar 2026", "EUR", 10.0, 1.5)

    @Test
    fun `has a row per day with sessions, notes and statuses`() {
        assertEquals(31, rows.size)
        val monday = rows.first { it.key == "2026-2-2" }
        assertEquals("Workday", monday.status)
        assertEquals("09:00–13:00, 14:00–18:30", monday.sessions)
        assertEquals("Client \"A\", kickoff", monday.notes)
        assertEquals(8.5, monday.clockedHours, 1e-9)
        assertEquals(0, monday.breakMinutes)
        assertEquals(8.5, monday.hours, 1e-9)
        assertEquals("Sick", rows.first { it.key == "2026-2-3" }.status)
        assertEquals("Day off", rows.first { it.key == "2026-2-1" }.status)
        assertTrue(rows.first { it.key == "2026-2-4" }.assumed)
    }

    @Test
    fun `writes the desktop's CSV`() {
        val csv = timesheetCsv(rows, period, pay, meta)
        assertTrue(csv.startsWith("﻿"))
        val lines = csv.removePrefix("﻿").split("\r\n")
        assertEquals("Date,Weekday,Status,Sessions,Notes,Clocked (h),Break (min),Hours,Overtime (h),Source", lines[0])
        assertEquals(
            "2026-03-02,Mon,Workday,\"09:00–13:00, 14:00–18:30\",\"Client \"\"A\"\", kickoff\",8.5,0,8.5,0,Punched",
            lines.first { it.startsWith("2026-03-02") },
        )
        assertTrue("Hours worked,168.5" in lines)
        assertTrue("Sick days,1" in lines)
        assertEquals("Net (EUR),${round2(pay.net).js()}", lines.last())
    }

    @Test
    fun `adds conversion and vacation lines`() {
        val csv = timesheetCsv(rows, period, pay, meta.copy(conversion = "USD" to 2.0, vacation = Triple("2026", 3, 25.0)))
        val lines = csv.removePrefix("﻿").split("\r\n")
        assertTrue("Vacation days used (2026),3" in lines)
        assertTrue("Vacation allowance,25" in lines)
        assertTrue("Converted to USD,at 2" in lines)
        assertTrue("Gross (USD),${round2(pay.gross * 2).js()}" in lines)
        assertEquals("Net (USD),${round2(pay.net * 2).js()}", lines.last())
    }
}
