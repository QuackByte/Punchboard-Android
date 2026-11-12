package dev.quackbyte.punchboard.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.quackbyte.punchboard.data.PunchboardStore
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Runs a reminder check: reads the stored time card, posts what's due, keeps
 * the live clock in step and books the next check. Call [check] after
 * anything that changes the answer (punch in/out, editing today, changing
 * reminder settings); alarms, boot and clock changes call it too.
 */
object ReminderEngine {
    private const val TAG = "ReminderEngine"

    fun check(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        val document = try {
            PunchboardStore(context).load()
        } catch (error: Exception) {
            Log.w(TAG, "Couldn't read the time card", error)
            null
        }
        if (document == null) {
            Notifications.hideLiveClock(context)
            ReminderScheduler.cancel(context)
            return
        }

        val preferences = ReminderPreferences(context)
        val settings = preferences.settings
        val today = TodayStatus.from(document.data, now.toLocalDate())
        val plan = ReminderPlanner.plan(
            now,
            settings,
            today,
            preferences.firedOn(today.date),
            preferences.snoozedUntil(ReminderKind.PunchIn),
        )

        plan.due.forEach { Notifications.postReminder(context, it) }
        preferences.markFired(today.date, plan.due.map { it.kind })

        val running = today.running
        if (settings.liveClock && running != null) {
            Notifications.showLiveClock(context, running, today.clockedMinutes(now), today.targetMinutes)
        } else {
            Notifications.hideLiveClock(context)
        }

        // A reminder whose moment has passed (punched in, or out) shouldn't linger.
        if (today.running != null || today.entries.isNotEmpty()) dismiss(context, ReminderKind.PunchIn)
        if (today.running == null) {
            dismiss(context, ReminderKind.StillOnClock)
            dismiss(context, ReminderKind.ForgotPunchOut)
        }

        ReminderScheduler.schedule(context, plan.nextCheck)
    }

    fun dismiss(context: Context, kind: ReminderKind) = Notifications.cancelReminder(context, kind)

    fun dismissAll(context: Context) = ReminderKind.entries.forEach { dismiss(context, it) }
}

/** One pending alarm that wakes [ReminderReceiver] for the next check. */
object ReminderScheduler {
    fun schedule(context: Context, at: LocalDateTime?) {
        if (at == null) return cancel(context)
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Inexact on purpose: a nudge a minute or two late is fine, and exact
        // alarms need a special permission Play reserves for alarm clocks.
        alarmManager(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingIntent(context))
    }

    fun cancel(context: Context) {
        alarmManager(context).cancel(pendingIntent(context))
    }

    private fun alarmManager(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_CHECK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Wakes for scheduled checks, and re-books them after boot, updates and clock changes. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CHECK,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> Unit
            else -> return
        }
        val pending = goAsync()
        Thread {
            try {
                ReminderEngine.check(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_CHECK = "dev.quackbyte.punchboard.action.CHECK_REMINDERS"
    }
}
