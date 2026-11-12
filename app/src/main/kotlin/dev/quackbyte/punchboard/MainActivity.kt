package dev.quackbyte.punchboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.quackbyte.punchboard.reminders.ReminderEngine

/**
 * Placeholder until the UI direction is picked (Punchboard look vs.
 * Material 3). The data layer and reminders already run underneath.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.placeholder_ui), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Thread { ReminderEngine.check(applicationContext) }.start()
    }
}
