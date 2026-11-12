package dev.quackbyte.punchboard.reminders

import dev.quackbyte.punchboard.data.DataFileCodec
import dev.quackbyte.punchboard.data.DecodeResult
import dev.quackbyte.punchboard.model.TimeEntry
import dev.quackbyte.punchboard.model.WorkRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ReminderPlannerTest {
    private val wednesday = LocalDate.of(2026, 10, 14)
    private fun at(time: String): LocalDateTime = wednesday.atTime(LocalTime.parse(time))

    private fun status(
        entries: List<TimeEntry> = emptyList(),
        isWorkday: Boolean = true,
        target: Int = 480,
    ) = TodayStatus(
        date = wednesday,
        isWorkday = isWorkday,
        entries = entries,
        targetMinutes = target,
        rules = WorkRules(),
        running = entries.lastOrNull { it.isRunning }?.let { RunningSession(wednesday, it.start) },
    )

    private val nudgeAtNine = ReminderSettings(punchInAt = LocalTime.of(9, 0), liveClock = false)

    @Test
    fun `punch-in nudge waits for the start time`() {
        val plan = ReminderPlanner.plan(at("08:30"), nudgeAtNine, status(), emptySet())
        assertTrue(plan.due.isEmpty())
        assertEquals(at("09:00"), plan.nextCheck)
    }

    @Test
    fun `punch-in nudge fires on an empty workday, then books tomorrow`() {
        val plan = ReminderPlanner.plan(at("09:10"), nudgeAtNine, status(), emptySet())
        assertEquals(listOf(ReminderKind.PunchIn), plan.due.map { it.kind })
        assertEquals(wednesday.plusDays(1).atTime(9, 0), plan.nextCheck)
    }

    @Test
    fun `punch-in nudge stays quiet when it shouldn't nag`() {
        val worked = status(entries = listOf(TimeEntry("08:50", "09:05")))
        assertTrue(ReminderPlanner.plan(at("09:10"), nudgeAtNine, worked, emptySet()).due.isEmpty())
        assertTrue(ReminderPlanner.plan(at("09:10"), nudgeAtNine, status(isWorkday = false), emptySet()).due.isEmpty())
        assertTrue(ReminderPlanner.plan(at("09:10"), nudgeAtNine, status(), setOf(ReminderKind.PunchIn)).due.isEmpty())
        // Two hours after the start time it's too late to be useful.
        assertTrue(ReminderPlanner.plan(at("11:01"), nudgeAtNine, status(), emptySet()).due.isEmpty())
    }

    @Test
    fun `still on the clock fires once the target is reached`() {
        val settings = ReminderSettings(liveClock = false)
        val day = status(entries = listOf(TimeEntry("08:57", "12:31"), TimeEntry("13:15", null)))

        // 3h34m + 2h27m = 6h01m at 15:42: 1h59m to go.
        val early = ReminderPlanner.plan(at("15:42"), settings, day, emptySet())
        assertTrue(early.due.isEmpty())
        assertEquals(at("17:41"), early.nextCheck)

        val late = ReminderPlanner.plan(at("17:45"), settings, day, emptySet())
        val due = late.due.single()
        assertEquals(ReminderKind.StillOnClock, due.kind)
        assertEquals(484, due.clockedMinutes)
        assertEquals("13:15", due.since)
        assertNull(late.nextCheck)
    }

    @Test
    fun `still on the clock ignores days off and finished days`() {
        val settings = ReminderSettings(liveClock = false)
        val dayOff = status(entries = listOf(TimeEntry("08:00", null)), isWorkday = false, target = 0)
        assertTrue(ReminderPlanner.plan(at("18:00"), settings, dayOff, emptySet()).due.isEmpty())
        val punchedOut = status(entries = listOf(TimeEntry("08:00", "17:00")))
        assertTrue(ReminderPlanner.plan(at("18:00"), settings, punchedOut, emptySet()).due.isEmpty())
    }

    @Test
    fun `forgot to punch out fires at its time while running`() {
        val settings = ReminderSettings(stillOnClock = false, forgotPunchOutAt = LocalTime.of(20, 0), liveClock = false)
        val day = status(entries = listOf(TimeEntry("13:15", null)))
        assertEquals(at("20:00"), ReminderPlanner.plan(at("18:00"), settings, day, emptySet()).nextCheck)
        val due = ReminderPlanner.plan(at("20:01"), settings, day, emptySet()).due.single()
        assertEquals(ReminderKind.ForgotPunchOut, due.kind)
        assertEquals("13:15", due.since)
    }

    @Test
    fun `forgot to punch out leaves late starters alone`() {
        val settings = ReminderSettings(stillOnClock = false, forgotPunchOutAt = LocalTime.of(20, 0), liveClock = false)
        val nightShift = status(entries = listOf(TimeEntry("21:00", null)))
        val plan = ReminderPlanner.plan(at("21:30"), settings, nightShift, emptySet())
        assertTrue(plan.due.isEmpty())
        assertNull(plan.nextCheck)
    }

    @Test
    fun `live clock refreshes while punched in`() {
        val day = status(entries = listOf(TimeEntry("09:00", null)))
        val plan = ReminderPlanner.plan(at("09:30"), ReminderSettings(stillOnClock = false), day, emptySet())
        assertEquals(at("09:45"), plan.nextCheck)
    }

    @Test
    fun `reads today from the desktop file`() {
        val text = javaClass.getResource("/desktop-punchboard.json")!!.readText()
        val data = (DataFileCodec.decode(text) as DecodeResult.Ok).document.data

        val today = TodayStatus.from(data, wednesday)
        assertTrue(today.isWorkday)
        assertEquals(480, today.targetMinutes)
        assertEquals(RunningSession(wednesday, "13:15"), today.running)
        assertEquals(361, today.clockedMinutes(at("15:42")))

        // Sick on Tue 29 Sept; Saturday 3 Oct is a day off.
        assertFalse(TodayStatus.from(data, LocalDate.of(2026, 9, 29)).isWorkday)
        assertFalse(TodayStatus.from(data, LocalDate.of(2026, 10, 3)).isWorkday)

        // 21 Oct starts a period the desktop hasn't opened: the schedule carries forward.
        val nextPeriod = TodayStatus.from(data, LocalDate.of(2026, 10, 21))
        assertTrue(nextPeriod.isWorkday)
        assertNull(nextPeriod.running)
    }
}
