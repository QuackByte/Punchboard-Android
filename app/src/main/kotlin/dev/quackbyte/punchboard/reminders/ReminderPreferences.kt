package dev.quackbyte.punchboard.reminders

import android.content.Context
import androidx.core.content.edit
import java.time.LocalDate
import java.time.LocalTime

/**
 * Reminder settings and which reminders already fired today. These are
 * per-device (like the desktop's app config), not part of the data file.
 */
class ReminderPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    var settings: ReminderSettings
        get() = ReminderSettings(
            punchInAt = prefs.getString(KEY_PUNCH_IN, null)?.let(::parseTime),
            stillOnClock = prefs.getBoolean(KEY_STILL_ON_CLOCK, true),
            forgotPunchOutAt = prefs.getString(KEY_FORGOT, null)?.let(::parseTime),
            liveClock = prefs.getBoolean(KEY_LIVE_CLOCK, true),
        )
        set(value) = prefs.edit {
            putString(KEY_PUNCH_IN, value.punchInAt?.toString())
            putBoolean(KEY_STILL_ON_CLOCK, value.stillOnClock)
            putString(KEY_FORGOT, value.forgotPunchOutAt?.toString())
            putBoolean(KEY_LIVE_CLOCK, value.liveClock)
        }

    /** Reminders shown on [date]; each fires at most once a day. */
    fun firedOn(date: LocalDate): Set<ReminderKind> {
        if (prefs.getString(KEY_FIRED_DATE, null) != date.toString()) return emptySet()
        return prefs.getStringSet(KEY_FIRED, emptySet()).orEmpty()
            .mapNotNull { name -> ReminderKind.entries.firstOrNull { it.name == name } }
            .toSet()
    }

    fun markFired(date: LocalDate, kinds: Collection<ReminderKind>) {
        if (kinds.isEmpty()) return
        val fired = firedOn(date) + kinds
        prefs.edit {
            putString(KEY_FIRED_DATE, date.toString())
            putStringSet(KEY_FIRED, fired.map { it.name }.toSet())
        }
    }

    private fun parseTime(value: String): LocalTime? = runCatching { LocalTime.parse(value) }.getOrNull()

    private companion object {
        const val KEY_PUNCH_IN = "punch_in_at"
        const val KEY_STILL_ON_CLOCK = "still_on_clock"
        const val KEY_FORGOT = "forgot_punch_out_at"
        const val KEY_LIVE_CLOCK = "live_clock"
        const val KEY_FIRED_DATE = "fired_date"
        const val KEY_FIRED = "fired"
    }
}
