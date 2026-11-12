package dev.quackbyte.punchboard

import android.app.Application
import dev.quackbyte.punchboard.reminders.Notifications

class PunchboardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
