package dev.quackbyte.punchboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class KeysTest {
    @Test
    fun `day keys use a zero-based month like the desktop`() {
        assertEquals("2026-9-14", DayKeys.of(LocalDate.of(2026, 10, 14)))
        assertEquals("2026-0-1", DayKeys.of(LocalDate.of(2026, 1, 1)))
        assertEquals(LocalDate.of(2026, 10, 14), DayKeys.parse("2026-9-14"))
        assertNull(DayKeys.parse("2026-12-1"))
        assertNull(DayKeys.parse("nope"))
    }

    @Test
    fun `month keys are padded and one-based`() {
        assertEquals("2026-01", MonthKeys.of(YearMonth.of(2026, 1)))
        assertEquals(YearMonth.of(2026, 10), MonthKeys.parse("2026-10"))
        assertNull(MonthKeys.parse("2026-1"))
    }

    @Test
    fun `payslip periods are named after the month they end in`() {
        assertEquals(YearMonth.of(2026, 10), PayslipPeriods.endMonthFor(LocalDate.of(2026, 10, 14), 21))
        assertEquals(YearMonth.of(2026, 11), PayslipPeriods.endMonthFor(LocalDate.of(2026, 10, 21), 21))
        assertEquals(
            LocalDate.of(2026, 9, 21)..LocalDate.of(2026, 10, 20),
            PayslipPeriods.range(YearMonth.of(2026, 10), 21),
        )
    }

    @Test
    fun `payslip periods clamp to short months`() {
        // Start day 31: February has no 31st, so the period ends on 27 Feb.
        assertEquals(
            LocalDate.of(2026, 1, 31)..LocalDate.of(2026, 2, 27),
            PayslipPeriods.range(YearMonth.of(2026, 2), 31),
        )
        // Start day 1: the period "2026-10" is September, as on the desktop.
        assertEquals(
            LocalDate.of(2026, 9, 1)..LocalDate.of(2026, 9, 30),
            PayslipPeriods.range(YearMonth.of(2026, 10), 1),
        )
    }
}

class ClockTest {
    @Test
    fun `parses the formats quick entry accepts`() {
        assertEquals(540, Clock.parse("9"))
        assertEquals(570, Clock.parse("9:30"))
        assertEquals(570, Clock.parse("0930"))
        assertEquals(570, Clock.parse("9.30"))
        assertEquals(1020, Clock.parse("5pm"))
        assertEquals(0, Clock.parse("12am"))
        assertNull(Clock.parse("24:00"))
        assertNull(Clock.parse("13pm"))
    }

    @Test
    fun `formats and wraps`() {
        assertEquals("08:05", Clock.format(485))
        assertEquals("00:30", Clock.format(1470))
    }
}

/** Ported from the desktop's calculations.test.ts › workedTime. */
class WorkedTimeTest {
    private val day = listOf(TimeEntry("08:58", "13:02"), TimeEntry("13:30", "17:07"))
    private val none = WorkRules()

    @Test
    fun `sums sessions exactly without rules`() {
        assertEquals(WorkedTime(244 + 217, 0, 461), workedTime(day, none))
    }

    @Test
    fun `rounds each punch`() {
        assertEquals(450, workedTime(day, none.copy(roundingMinutes = 15)).clockedMinutes)
    }

    @Test
    fun `only tops up the break beyond gaps already taken`() {
        val result = workedTime(day, none.copy(roundingMinutes = 15, breakMinutes = 45.0))
        assertEquals(15, result.breakMinutes)
        assertEquals(435, result.workedMinutes)
    }

    @Test
    fun `deducts the full break from one long session`() {
        assertEquals(
            WorkedTime(480, 30, 450),
            workedTime(listOf(TimeEntry("09:00", "17:00")), none.copy(breakMinutes = 30.0, breakAfterHours = 6.0)),
        )
    }

    @Test
    fun `skips the break on short days`() {
        val result = workedTime(listOf(TimeEntry("09:00", "13:00")), none.copy(breakMinutes = 30.0))
        assertEquals(0, result.breakMinutes)
    }

    @Test
    fun `counts running sessions up to now and overnight sessions`() {
        assertEquals(0, workedTime(listOf(TimeEntry("09:00", null)), none).clockedMinutes)
        assertEquals(90, workedTime(listOf(TimeEntry("09:00", null)), none, 630).clockedMinutes)
        assertEquals(480, workedTime(listOf(TimeEntry("22:00", "06:00")), none).clockedMinutes)
    }

    @Test
    fun `normalizes rules like the desktop`() {
        val rules = WorkRules.from(
            TrackerSettings(breakMinutes = 999.0, roundingMinutes = 7, overtimeMultiplier = 0.5, balanceStartDate = "x"),
        )
        assertEquals(240.0, rules.breakMinutes, 0.0)
        assertEquals(0, rules.roundingMinutes)
        assertEquals(1.0, rules.overtimeMultiplier, 0.0)
        assertEquals("", rules.balanceStartDate)
        assertEquals(true, rules.balanceWeek)
    }
}
