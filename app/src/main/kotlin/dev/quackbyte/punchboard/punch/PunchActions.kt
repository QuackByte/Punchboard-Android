package dev.quackbyte.punchboard.punch

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.quackbyte.punchboard.MainActivity
import dev.quackbyte.punchboard.data.PunchboardRepository
import dev.quackbyte.punchboard.domain.Tracker
import dev.quackbyte.punchboard.domain.TrackerEditor
import dev.quackbyte.punchboard.model.DayKeys
import dev.quackbyte.punchboard.reminders.ReminderEngine
import dev.quackbyte.punchboard.reminders.ReminderKind
import dev.quackbyte.punchboard.reminders.ReminderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

/** What a one-tap punch (notification, tile, widget) can do right now. */
enum class PunchState {
    /** No time card yet: open the app to import or start one. */
    NoCard,
    CanPunchIn,
    CanPunchOut,
    /** A session from another day is still open: it needs an end time in the app. */
    StaleSession,
}

object PunchActions {
    const val ACTION_TOGGLE = "dev.quackbyte.punchboard.action.PUNCH_TOGGLE"
    const val ACTION_PUNCH_IN = "dev.quackbyte.punchboard.action.PUNCH_IN"
    const val ACTION_PUNCH_OUT = "dev.quackbyte.punchboard.action.PUNCH_OUT"
    const val ACTION_SNOOZE = "dev.quackbyte.punchboard.action.SNOOZE_PUNCH_IN"

    const val SNOOZE_MINUTES = 15L

    fun state(context: Context, today: LocalDate = LocalDate.now()): PunchState {
        val data = PunchboardRepository.get(context).document.value?.data ?: return PunchState.NoCard
        val tracker = Tracker(data)
        val open = tracker.openSession(tracker.currentPeriod(today))
        return when {
            open == null -> PunchState.CanPunchIn
            open.key == DayKeys.of(today) -> PunchState.CanPunchOut
            else -> PunchState.StaleSession
        }
    }

    /**
     * Punches in or out like the desktop's tray: out when today's session is
     * running, in when nothing is. Returns false when the app has to open
     * instead (no card yet, or a session left open on another day).
     */
    suspend fun toggle(context: Context, action: String = ACTION_TOGGLE): Boolean {
        val repository = PunchboardRepository.get(context)
        repository.ensureLoaded()
        val now = LocalDateTime.now()
        val state = state(context, now.toLocalDate())
        val punchIn = when (action) {
            ACTION_PUNCH_IN -> true
            ACTION_PUNCH_OUT -> false
            else -> state == PunchState.CanPunchIn
        }
        return when {
            punchIn && state == PunchState.CanPunchIn -> {
                repository.edit { TrackerEditor(it).punchIn(now) }
                true
            }
            !punchIn && state == PunchState.CanPunchOut -> {
                repository.edit { TrackerEditor(it).punchOut(now) }
                true
            }
            // Already in the state asked for (e.g. a stale notification button).
            punchIn && state == PunchState.CanPunchOut -> true
            !punchIn && state == PunchState.CanPunchIn -> true
            else -> false
        }
    }

    fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, PunchActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun openApp(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }
}

/** Punch buttons in notifications and the widget land here. */
class PunchActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pending = goAsync()
        scope.launch {
            try {
                val app = context.applicationContext
                if (action == PunchActions.ACTION_SNOOZE) {
                    ReminderPreferences(app).snooze(ReminderKind.PunchIn, LocalDateTime.now().plusMinutes(PunchActions.SNOOZE_MINUTES))
                    ReminderEngine.dismiss(app, ReminderKind.PunchIn)
                    ReminderEngine.check(app)
                } else {
                    PunchActions.toggle(app, action)
                    ReminderEngine.dismissAll(app)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
