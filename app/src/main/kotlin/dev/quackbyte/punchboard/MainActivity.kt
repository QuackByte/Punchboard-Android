package dev.quackbyte.punchboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import dev.quackbyte.punchboard.reminders.ReminderEngine
import dev.quackbyte.punchboard.ui.PunchboardApp
import dev.quackbyte.punchboard.ui.TrackerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: TrackerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PunchboardApp(vm) }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshNow()
        // Pick up anything the desktop changed while the app was away.
        lifecycleScope.launch { vm.repository.sync() }
        lifecycleScope.launch(Dispatchers.IO) { ReminderEngine.check(applicationContext) }
    }
}
