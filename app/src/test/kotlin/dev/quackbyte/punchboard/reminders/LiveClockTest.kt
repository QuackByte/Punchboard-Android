package dev.quackbyte.punchboard.reminders

import dev.quackbyte.punchboard.model.TimeEntry
import dev.quackbyte.punchboard.model.WorkRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class LiveClockTest {
    private val day = LocalDate.of(2026, 10, 14)
    private fun at(h: Int, m: Int) = day.atTime(h, m)

    private fun status(entries: List<TimeEntry>, target: Int = 480, rules: WorkRules = WorkRules(), runningDay: LocalDate = day) =
        TodayStatus(
            date = day,
            isWorkday = target > 0,
            entries = entries,
            targetMinutes = target,
            rules = rules,
            running = entries.lastOrNull { it.isRunning }?.let { RunningSession(runningDay, it.start) },
        )

    private val lunchDay = listOf(TimeEntry("08:57", "12:31"), TimeEntry("13:15", null))

    @Test
    fun `nothing to show off the clock`() {
        assertNull(LiveClockState.of(status(listOf(TimeEntry("09:00", "17:00"))), at(18, 0)))
    }

    @Test
    fun `counts today's time up from the morning, not just this session`() {
        val state = LiveClockState.of(status(lunchDay), at(15, 42))!!
        assertEquals(361, state.clockedMinutes)
        // 13:15 minus the 3h 34m before lunch: the chip reads today's 6h 01m at 15:42.
        assertEquals(LocalDateTime.of(2026, 10, 14, 9, 41), state.chronometerBase)
        assertEquals("On the clock since 13:15", state.title)
        assertEquals("Today 6h 01m of 8h · done at 17:41", state.text)
        assertEquals(listOf(LiveClockState.Segment(480, LiveClockState.Kind.Target)), state.segments)
        assertEquals(361, state.progress)
        assertFalse(state.overTarget)
        assertEquals("2026-10-14T13:15", state.sessionKey)
    }

    @Test
    fun `includes the automatic break in the finish time`() {
        val state = LiveClockState.of(status(listOf(TimeEntry("09:00", null)), rules = WorkRules(breakMinutes = 30.0)), at(10, 0))!!
        assertEquals("Today 1h of 8h · done at 17:30", state.text)
    }

    @Test
    fun `turns to overtime past the target`() {
        val state = LiveClockState.of(status(lunchDay), at(17, 45))!!
        assertTrue(state.overTarget)
        assertEquals("Done for today · still on the clock", state.title)
        assertEquals("Today 8h 04m of 8h · +4m over", state.text)
        assertEquals(
            listOf(LiveClockState.Segment(480, LiveClockState.Kind.Target), LiveClockState.Segment(4, LiveClockState.Kind.Overtime)),
            state.segments,
        )
    }

    @Test
    fun `a day off grows an open-ended bar`() {
        val state = LiveClockState.of(status(listOf(TimeEntry("10:00", null)), target = 0), at(11, 30))!!
        assertEquals("Today 1h 30m · a day off", state.text)
        assertEquals(listOf(LiveClockState.Segment(120, LiveClockState.Kind.Overtime)), state.segments)
        assertEquals(90, state.progress)
    }

    @Test
    fun `a session from last night counts from its real start`() {
        val yesterday = day.minusDays(1)
        val state = LiveClockState.of(status(emptyList<TimeEntry>().plus(TimeEntry("22:00", null)), runningDay = yesterday), at(1, 0))!!
        assertEquals(180, state.clockedMinutes)
        assertEquals(yesterday.atTime(22, 0), state.chronometerBase)
    }
}
