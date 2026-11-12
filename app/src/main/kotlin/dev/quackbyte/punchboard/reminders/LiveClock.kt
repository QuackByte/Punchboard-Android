package dev.quackbyte.punchboard.reminders

import dev.quackbyte.punchboard.model.Clock
import dev.quackbyte.punchboard.model.TimeEntry
import dev.quackbyte.punchboard.model.entryMinutes
import dev.quackbyte.punchboard.model.estimateFinish
import dev.quackbyte.punchboard.model.formatMinutes
import java.time.LocalDateTime

/**
 * What the punch's Live Update shows while the clock runs: a status-bar
 * chip counting up today's time, and a progress bar towards today's target
 * that turns to overtime once it's passed. Pure, so it's unit tested; the
 * notification itself is built in [Notifications.showLiveClock].
 */
data class LiveClockState(
    val title: String,
    val text: String,
    /** Wall-clock millis the chip's chronometer counts up from: today's time so far. */
    val chronometerBase: LocalDateTime,
    /** Minutes on the clock today, running session included. */
    val clockedMinutes: Int,
    val targetMinutes: Int,
    /** Progress bar: scheduled part, then overtime past the target. */
    val segments: List<Segment>,
    val progress: Int,
    /** Session start, used to recognise the same session after a dismissal. */
    val sessionKey: String,
) {
    enum class Kind { Target, Overtime }

    data class Segment(val length: Int, val kind: Kind)

    val overTarget get() = targetMinutes > 0 && clockedMinutes >= targetMinutes

    companion object {
        /** A day without a target still gets a bar, an hour at a time. */
        private const val OPEN_ENDED_STEP = 60

        fun of(today: TodayStatus, now: LocalDateTime): LiveClockState? {
            val running = today.running ?: return null
            val startedAt = running.startedAt() ?: return null
            val nowMinutes = now.hour * 60 + now.minute
            val runningToday = running.day == today.date

            // Finished sessions today, then the running one from its real start.
            val finished = if (runningToday) today.entries.filterNot(TimeEntry::isRunning).sumOf { entryMinutes(it) } else 0
            val runningMinutes = java.time.Duration.between(startedAt, now).toMinutes().toInt().coerceAtLeast(0)
            val clocked = finished + runningMinutes
            val target = today.targetMinutes

            val segments: List<Segment>
            val progress: Int
            if (target > 0) {
                val over = (clocked - target).coerceAtLeast(0)
                segments = if (over > 0) {
                    listOf(Segment(target, Kind.Target), Segment(over, Kind.Overtime))
                } else {
                    listOf(Segment(target, Kind.Target))
                }
                progress = clocked
            } else {
                val length = ((clocked / OPEN_ENDED_STEP) + 1) * OPEN_ENDED_STEP
                segments = listOf(Segment(length, Kind.Overtime))
                progress = clocked
            }

            val finish = if (runningToday && target > 0) {
                estimateFinish(today.entries, target / 60.0, today.rules, nowMinutes)
            } else {
                null
            }
            val text = when {
                target <= 0 -> "Today ${formatMinutes(clocked)} · a day off"
                clocked >= target -> "Today ${formatMinutes(clocked)} of ${formatMinutes(target)} · +${formatMinutes(clocked - target)} over"
                finish != null -> "Today ${formatMinutes(clocked)} of ${formatMinutes(target)} · done at ${Clock.format(finish.finishMinutes)}"
                else -> "Today ${formatMinutes(clocked)} of ${formatMinutes(target)}"
            }
            val title = when {
                target > 0 && clocked >= target -> "Done for today · still on the clock"
                else -> "On the clock since ${running.start}"
            }

            return LiveClockState(
                title = title,
                text = text,
                chronometerBase = startedAt.minusMinutes(finished.toLong()),
                clockedMinutes = clocked,
                targetMinutes = target,
                segments = segments,
                progress = progress,
                sessionKey = "${running.day}T${running.start}",
            )
        }
    }
}
