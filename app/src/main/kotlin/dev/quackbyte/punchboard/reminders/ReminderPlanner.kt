package dev.quackbyte.punchboard.reminders

import dev.quackbyte.punchboard.model.Clock
import dev.quackbyte.punchboard.model.DataFile
import dev.quackbyte.punchboard.domain.Tracker
import dev.quackbyte.punchboard.model.TimeEntry
import dev.quackbyte.punchboard.model.WorkRules
import dev.quackbyte.punchboard.model.workedTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.roundToInt

/** What the user turned on in Settings › Reminders. */
data class ReminderSettings(
    /** Nudge to punch in on workdays when the card is still empty; null = off. */
    val punchInAt: LocalTime? = null,
    /** "Still on the clock?" once today's target is done. */
    val stillOnClock: Boolean = true,
    /** Remind to punch out if the clock is still running at this time; null = off. */
    val forgotPunchOutAt: LocalTime? = null,
    /** Pinned notification with a live clock while punched in. */
    val liveClock: Boolean = true,
)

enum class ReminderKind { PunchIn, StillOnClock, ForgotPunchOut }

/** A running session: the day it started on and its "HH:MM" start. */
data class RunningSession(val day: LocalDate, val start: String) {
    fun startedAt(): LocalDateTime? = Clock.parse(start)?.let { day.atStartOfDay().plusMinutes(it.toLong()) }
}

/** Everything the reminders need to know about today, read from the data file. */
data class TodayStatus(
    val date: LocalDate,
    val isWorkday: Boolean,
    val entries: List<TimeEntry>,
    /** Today's target in minutes (0 on days off). */
    val targetMinutes: Int,
    val rules: WorkRules,
    val running: RunningSession?,
) {
    fun clockedMinutes(now: LocalDateTime): Int =
        workedTime(entries, rules, now.hour * 60 + now.minute).clockedMinutes

    companion object {
        /** Reads today from the payslip period [date] falls in, with the desktop's daily target. */
        fun from(data: DataFile, date: LocalDate): TodayStatus {
            val tracker = Tracker(data)
            val month = tracker.currentPeriod(date)
            val target = tracker.todayTarget(date)
            val running = tracker.openSession(month)?.let { open -> open.date?.let { RunningSession(it, open.entry.start) } }
            return TodayStatus(
                date = date,
                isWorkday = target.isWorkday,
                entries = tracker.todayEntries(date),
                targetMinutes = (target.hours * 60).roundToInt(),
                rules = tracker.rules,
                running = running,
            )
        }
    }
}

data class DueReminder(
    val kind: ReminderKind,
    val clockedMinutes: Int = 0,
    val targetMinutes: Int = 0,
    val since: String? = null,
    val time: LocalTime? = null,
)

data class ReminderPlan(
    val due: List<DueReminder>,
    /** When to look again; null when nothing is pending. */
    val nextCheck: LocalDateTime?,
)

/**
 * Decides which reminders are due now and when to check next. Pure, so it's
 * unit tested; [ReminderEngine] feeds it and posts the notifications.
 * Mirrors the desktop's `checkReminders`, plus a "forgot to punch out".
 */
object ReminderPlanner {
    /** How long after the start time the punch-in nudge may still fire. */
    const val PUNCH_IN_WINDOW_MINUTES = 120L

    /** While punched in, refresh the live clock's progress this often. */
    const val LIVE_REFRESH_MINUTES = 15L

    fun plan(
        now: LocalDateTime,
        settings: ReminderSettings,
        today: TodayStatus,
        firedToday: Set<ReminderKind>,
        punchInSnoozedUntil: LocalDateTime? = null,
    ): ReminderPlan {
        val due = mutableListOf<DueReminder>()
        val next = mutableListOf<LocalDateTime>()
        val clocked = today.clockedMinutes(now)
        val runningToday = today.entries.any { it.isRunning }

        settings.punchInAt?.let { time ->
            val start = today.date.atTime(time)
            when {
                now < start -> next += start
                else -> {
                    val snoozed = punchInSnoozedUntil?.takeIf { it.toLocalDate() == today.date }
                    val windowEnd = maxOf(start.plusMinutes(PUNCH_IN_WINDOW_MINUTES), snoozed ?: start)
                    val waiting = today.isWorkday && today.entries.isEmpty() && today.running == null &&
                        ReminderKind.PunchIn !in firedToday && !now.isAfter(windowEnd)
                    when {
                        !waiting -> Unit
                        snoozed != null && now < snoozed -> next += snoozed
                        else -> due += DueReminder(ReminderKind.PunchIn, time = time)
                    }
                    next += today.date.plusDays(1).atTime(time)
                }
            }
        }

        if (settings.stillOnClock && runningToday && today.isWorkday && today.targetMinutes > 0 &&
            ReminderKind.StillOnClock !in firedToday
        ) {
            if (clocked >= today.targetMinutes) {
                due += DueReminder(
                    ReminderKind.StillOnClock,
                    clockedMinutes = clocked,
                    targetMinutes = today.targetMinutes,
                    since = today.entries.lastOrNull { it.isRunning }?.start,
                )
            } else {
                next += now.plusMinutes((today.targetMinutes - clocked).toLong())
            }
        }

        val running = today.running
        settings.forgotPunchOutAt?.let { time ->
            if (running != null && ReminderKind.ForgotPunchOut !in firedToday) {
                val at = today.date.atTime(time)
                val startedAt = running.startedAt()
                when {
                    // Only nags about a session that was already running at that time.
                    startedAt != null && startedAt >= at -> Unit
                    now < at -> next += at
                    else -> due += DueReminder(ReminderKind.ForgotPunchOut, since = running.start, time = time)
                }
            }
        }

        if (settings.liveClock && running != null) next += now.plusMinutes(LIVE_REFRESH_MINUTES)

        return ReminderPlan(due, next.minOrNull())
    }
}
