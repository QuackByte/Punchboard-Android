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
import dev.quackbyte.punchboard.punch.PunchActionReceiver
import dev.quackbyte.punchboard.punch.PunchActions
import androidx.core.graphics.drawable.IconCompat
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
        val builder = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
        when (reminder.kind) {
            ReminderKind.PunchIn -> builder
                .addAction(0, context.getString(R.string.action_punch_in), PunchActions.broadcast(context, PunchActions.ACTION_PUNCH_IN, 10))
                .addAction(0, context.getString(R.string.action_snooze), PunchActions.broadcast(context, PunchActions.ACTION_SNOOZE, 11))
            ReminderKind.StillOnClock, ReminderKind.ForgotPunchOut -> builder
                .addAction(0, context.getString(R.string.action_punch_out), PunchActions.broadcast(context, PunchActions.ACTION_PUNCH_OUT, 12))
        }
        notifySafely(context, idFor(reminder.kind), builder.build())
    }

    /**
     * The punch as a Live Update (Android 16+): a promoted, ongoing
     * notification with a status-bar chip counting up today's time and a
     * progress bar towards today's target. Older Android versions show the
     * same notification without the promotion.
     */
    fun showLiveClock(context: Context, state: LiveClockState) {
        if (!canPost(context)) return
        val signal = 0xFFFF7033.toInt()
        val overtime = 0xFF8951EC.toInt()
        val style = NotificationCompat.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressTrackerIcon(IconCompat.createWithResource(context, R.drawable.ic_tile))
            .setProgressSegments(
                state.segments.map { segment ->
                    NotificationCompat.ProgressStyle.Segment(segment.length.coerceAtLeast(1))
                        .setColor(if (segment.kind == LiveClockState.Kind.Target) signal else overtime)
                },
            )
            .setProgress(state.progress)
        if (state.overTarget) {
            style.addProgressPoint(NotificationCompat.ProgressStyle.Point(state.targetMinutes).setColor(signal))
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_CLOCK)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(signal)
            .setContentTitle(state.title)
            .setContentText(state.text)
            .setStyle(style)
            .setRequestPromotedOngoing(true)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(false)
            .setWhen(state.chronometerBase.toEpochMillis())
            .setShowWhen(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(context))
            .setDeleteIntent(liveDismissed(context, state.sessionKey))
            .addAction(0, context.getString(R.string.action_punch_out), PunchActions.broadcast(context, PunchActions.ACTION_PUNCH_OUT, 13))
        notifySafely(context, ID_LIVE_CLOCK, builder.build())
    }

    /** Whether the user lets Punchboard show Live Updates (always true before Android 16). */
    fun canPromote(context: Context): Boolean =
        Build.VERSION.SDK_INT < 36 || context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()

    /** Opens the system page where Live Updates are turned on for the app. */
    fun promotionSettings(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 36) {
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        }

    private fun liveDismissed(context: Context, sessionKey: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        14,
        Intent(context, PunchActionReceiver::class.java)
            .setAction(PunchActions.ACTION_LIVE_DISMISSED)
            .putExtra(PunchActions.EXTRA_SESSION, sessionKey),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun cancelReminder(context: Context, kind: ReminderKind) {
        NotificationManagerCompat.from(context).cancel(idFor(kind))
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
    fun format(minutes: Int): String = dev.quackbyte.punchboard.model.formatMinutes(minutes)
}
