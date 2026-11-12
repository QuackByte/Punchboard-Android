package dev.quackbyte.punchboard.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.quackbyte.punchboard.MainActivity
import dev.quackbyte.punchboard.R
import java.time.LocalDateTime
import java.time.ZoneId

object Notifications {
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_CLOCK = "clock"

    private const val ID_LIVE_CLOCK = 1
    private fun idFor(kind: ReminderKind) = 100 + kind.ordinal

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_REMINDERS,
                    context.getString(R.string.channel_reminders),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.channel_reminders_description) },
                NotificationChannel(
                    CHANNEL_CLOCK,
                    context.getString(R.string.channel_clock),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.channel_clock_description)
                    setShowBadge(false)
                },
            ),
        )
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun postReminder(context: Context, reminder: DueReminder) {
        if (!canPost(context)) return
        val (title, body) = when (reminder.kind) {
            ReminderKind.PunchIn -> context.getString(R.string.reminder_punch_in_title) to
                context.getString(R.string.reminder_punch_in_body, reminder.time.toString())
            ReminderKind.StillOnClock -> context.getString(R.string.reminder_still_on_clock_title) to
                context.getString(
                    R.string.reminder_still_on_clock_body,
                    Durations.format(reminder.clockedMinutes),
                    Durations.format(reminder.targetMinutes),
                    reminder.since.orEmpty(),
                )
            ReminderKind.ForgotPunchOut -> context.getString(R.string.reminder_forgot_title) to
                context.getString(R.string.reminder_forgot_body, reminder.since.orEmpty(), reminder.time.toString())
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        notifySafely(context, idFor(reminder.kind), notification)
    }

    /** Pinned while punched in: a live chronometer since the session started. */
    fun showLiveClock(context: Context, session: RunningSession, clockedMinutes: Int, targetMinutes: Int) {
        if (!canPost(context)) return
        val startedAt = session.startedAt() ?: return
        val builder = NotificationCompat.Builder(context, CHANNEL_CLOCK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.live_clock_title, session.start))
            .setUsesChronometer(true)
            .setWhen(startedAt.toEpochMillis())
            .setShowWhen(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setContentIntent(openApp(context))
        if (targetMinutes > 0) {
            builder
                .setContentText(
                    context.getString(
                        R.string.live_clock_body,
                        Durations.format(clockedMinutes),
                        Durations.format(targetMinutes),
                    ),
                )
                .setProgress(targetMinutes, clockedMinutes.coerceAtMost(targetMinutes), false)
        }
        notifySafely(context, ID_LIVE_CLOCK, builder.build())
    }

    fun hideLiveClock(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_LIVE_CLOCK)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notifySafely(context: Context, id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post; nothing to do.
        }
    }

    private fun LocalDateTime.toEpochMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

/** "8h 04m", "45m", "8h" — the desktop's duration style. */
object Durations {
    fun format(minutes: Int): String {
        val safe = maxOf(0, minutes)
        val h = safe / 60
        val m = safe % 60
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h %02dm".format(m)
        }
    }
}
