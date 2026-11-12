package dev.quackbyte.punchboard.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.View
import android.widget.RemoteViews
import dev.quackbyte.punchboard.MainActivity
import dev.quackbyte.punchboard.R
import dev.quackbyte.punchboard.data.PunchboardStore
import dev.quackbyte.punchboard.domain.Tracker
import dev.quackbyte.punchboard.model.DayKeys
import dev.quackbyte.punchboard.model.formatDuration
import dev.quackbyte.punchboard.model.sumEntriesHours
import dev.quackbyte.punchboard.punch.PunchActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

/** What the widget and tile show, read straight from the stored card. */
private data class ClockView(
    val hasCard: Boolean,
    val running: Boolean,
    val stale: Boolean,
    val since: String?,
    val startedAtMillis: Long?,
    val todayHours: Double,
    val targetHours: Double,
)

private fun readClock(context: Context, now: LocalDateTime = LocalDateTime.now()): ClockView {
    val data = runCatching { PunchboardStore(context).load() }.getOrNull()?.data
        ?: return ClockView(false, false, false, null, null, 0.0, 0.0)
    val tracker = Tracker(data)
    val today = now.toLocalDate()
    val open = tracker.openSession(tracker.currentPeriod(today))
    val running = open != null && open.key == DayKeys.of(today)
    val startedAt = if (running) {
        dev.quackbyte.punchboard.model.Clock.parse(open!!.entry.start)?.let {
            today.atStartOfDay().plusMinutes(it.toLong()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    } else {
        null
    }
    return ClockView(
        hasCard = true,
        running = running,
        stale = open != null && !running,
        since = open?.entry?.start,
        startedAtMillis = startedAt,
        todayHours = sumEntriesHours(tracker.todayEntries(today), now.hour * 60 + now.minute),
        targetHours = tracker.todayTarget(today).hours,
    )
}

/** A mini punch clock for the home screen: status, today's hours and one punch button. */
class PunchWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                render(context, manager, ids)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Re-renders every placed widget and the tile; call after any change. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PunchWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
            runCatching { TileService.requestListeningState(context, ComponentName(context, PunchTileService::class.java)) }
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val clock = readClock(context)
            val views = RemoteViews(context.packageName, R.layout.widget_punch)
            val openApp = PendingIntent.getActivity(
                context,
                20,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, openApp)

            val status: String
            val button: String
            val buttonBackground: Int
            val buttonText: Int
            val dot: Int
            val buttonIntent: PendingIntent
            when {
                !clock.hasCard -> {
                    status = context.getString(R.string.widget_no_card)
                    button = context.getString(R.string.widget_open)
                    buttonBackground = R.drawable.widget_button_neutral
                    buttonText = R.color.ink_foreground
                    dot = R.color.ink_muted
                    buttonIntent = openApp
                }
                clock.running -> {
                    status = context.getString(R.string.live_clock_title, clock.since)
                    button = context.getString(R.string.action_punch_out)
                    buttonBackground = R.drawable.widget_button_out
                    buttonText = R.color.on_signal
                    dot = R.color.signal
                    buttonIntent = PunchActions.broadcast(context, PunchActions.ACTION_PUNCH_OUT, 21)
                }
                clock.stale -> {
                    status = context.getString(R.string.widget_stale)
                    button = context.getString(R.string.punch_set_end)
                    buttonBackground = R.drawable.widget_button_neutral
                    buttonText = R.color.ink_foreground
                    dot = R.color.vacation_dot
                    buttonIntent = openApp
                }
                else -> {
                    status = context.getString(if (clock.todayHours > 0) R.string.punch_off_clock else R.string.punch_not_yet)
                    button = context.getString(R.string.action_punch_in)
                    buttonBackground = R.drawable.widget_button_in
                    buttonText = R.color.on_brand
                    dot = R.color.ink_muted
                    buttonIntent = PunchActions.broadcast(context, PunchActions.ACTION_PUNCH_IN, 22)
                }
            }

            views.setTextViewText(R.id.widget_status, status)
            views.setInt(R.id.widget_dot, "setColorFilter", context.getColor(dot))
            views.setTextViewText(R.id.widget_button, button)
            views.setInt(R.id.widget_button, "setBackgroundResource", buttonBackground)
            views.setTextColor(R.id.widget_button, context.getColor(buttonText))
            views.setOnClickPendingIntent(R.id.widget_button, buttonIntent)
            views.setTextViewText(
                R.id.widget_today,
                if (!clock.hasCard) "" else context.getString(
                    R.string.widget_today,
                    formatDuration(clock.todayHours),
                    if (clock.targetHours > 0) " / ${formatDuration(clock.targetHours)}" else "",
                ),
            )
            if (clock.running && clock.startedAtMillis != null) {
                val base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - clock.startedAtMillis)
                views.setChronometer(R.id.widget_chronometer, base, null, true)
                views.setViewVisibility(R.id.widget_chronometer, View.VISIBLE)
            } else {
                views.setChronometer(R.id.widget_chronometer, SystemClock.elapsedRealtime(), null, false)
                views.setViewVisibility(R.id.widget_chronometer, View.GONE)
            }
            manager.updateAppWidget(ids, views)
        }
    }
}

/** Quick Settings tile: punch in or out from anywhere. */
class PunchTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val clock = readClock(this)
        tile.state = when {
            !clock.hasCard || clock.stale -> Tile.STATE_INACTIVE
            clock.running -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(if (clock.running) R.string.action_punch_out else R.string.action_punch_in)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !clock.hasCard -> getString(R.string.widget_no_card)
                clock.running -> getString(R.string.tile_since, clock.since)
                clock.stale -> getString(R.string.widget_stale)
                else -> formatDuration(clock.todayHours)
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val clock = readClock(this)
        if (!clock.hasCard || clock.stale) {
            openApp()
            return
        }
        scope.launch {
            PunchActions.toggle(applicationContext)
            onStartListening()
        }
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 30, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
