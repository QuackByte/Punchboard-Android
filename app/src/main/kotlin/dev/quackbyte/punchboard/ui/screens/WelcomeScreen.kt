package dev.quackbyte.punchboard.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.quackbyte.punchboard.R
import dev.quackbyte.punchboard.data.ImportResult
import dev.quackbyte.punchboard.model.DateText
import dev.quackbyte.punchboard.ui.TrackerViewModel
import dev.quackbyte.punchboard.ui.components.BigButton
import dev.quackbyte.punchboard.ui.components.DottedLeader
import dev.quackbyte.punchboard.ui.components.Lucide
import dev.quackbyte.punchboard.ui.components.MonoText
import dev.quackbyte.punchboard.ui.components.Panel
import dev.quackbyte.punchboard.ui.components.Perforation
import dev.quackbyte.punchboard.ui.components.Stamp
import dev.quackbyte.punchboard.ui.components.TextAction
import dev.quackbyte.punchboard.ui.theme.LocalPb
import dev.quackbyte.punchboard.ui.theme.Mono
import dev.quackbyte.punchboard.ui.theme.PbType
import dev.quackbyte.punchboard.ui.theme.Sans
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** First run: bring the desktop's time card over, or start a new one. */
@Composable
fun WelcomeScreen(vm: TrackerViewModel) {
    val pb = LocalPb.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<Pair<Uri, ImportResult>?>(null) }
    var keepInSync by remember { mutableStateOf(true) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { picked = uri to vm.readImport(uri) }
    }
    val pick = { open.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }

    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(56.dp))
        Stamp("Welcome")
        Text("Bring your time card over", fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 33.sp, color = pb.foreground)
        Text(
            "Pick the punchboard.json your desktop app saves to — from Drive, Dropbox or Downloads. Nothing on your desktop changes.",
            style = PbType.body.copy(lineHeight = 22.sp),
            color = pb.mutedForeground,
        )

        when (val result = picked?.second) {
            is ImportResult.Ready -> {
                val summary = result.summary
                Panel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Lucide.File, null, Modifier.size(20.dp), tint = pb.primary)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            MonoText("punchboard.json", size = 14f, weight = FontWeight.SemiBold)
                            summary.savedAt?.let { saved ->
                                val text = runCatching {
                                    DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.UK).format(Instant.parse(saved).atZone(ZoneId.systemDefault()))
                                }.getOrDefault(saved)
                                Text("Saved $text", style = PbType.bodySmall, color = pb.mutedForeground)
                            }
                        }
                        Icon(Lucide.Check, "Valid file", Modifier.size(20.dp), tint = pb.primary)
                    }
                    Spacer(Modifier.height(12.dp))
                    Perforation()
                    Spacer(Modifier.height(12.dp))
                    val range = if (summary.firstMonth != null && summary.lastMonth != null) {
                        " · ${DateText.monthShort(summary.firstMonth)} ${summary.firstMonth.year % 100} – ${DateText.monthShort(summary.lastMonth)} ${summary.lastMonth.year % 100}"
                    } else {
                        ""
                    }
                    SummaryLine("Months", "${summary.monthCount}$range")
                    SummaryLine("Days logged", summary.daysLogged.toString())
                    SummaryLine("Sessions", summary.sessions.toString())
                    SummaryLine("Vacation · sick", "${summary.vacationDays} · ${summary.sickDays}")
                    if (summary.hasRunningSession) {
                        Spacer(Modifier.height(6.dp))
                        Text("A session is still running in this file — it carries on here.", style = PbType.bodySmall, color = pb.mutedForeground)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(pb.muted.copy(alpha = 0.6f))
                            .clickable(role = Role.Checkbox) { keepInSync = !keepInSync }
                            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Keep in sync with this file", style = PbType.label, color = pb.foreground)
                            Text("Saves back to it, so desktop and phone share one card", style = PbType.bodySmall, color = pb.mutedForeground)
                        }
                        Checkbox(keepInSync, null, colors = CheckboxDefaults.colors(checkedColor = pb.primary, checkmarkColor = pb.onPrimary))
                    }
                }
                BigButton(
                    "Import ${summary.monthCount} month${if (summary.monthCount == 1) "" else "s"}",
                    Lucide.Download,
                    pb.foreground,
                    pb.background,
                    { vm.importDocument(result.document, if (keepInSync) picked!!.first else null) },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextAction("Choose another file", pick)
                    TextAction("Start fresh", vm::startFresh, color = pb.mutedForeground)
                }
            }
            is ImportResult.Failed -> {
                Text(importFailureText(result.reason), style = PbType.body, color = pb.destructive)
                BigButton("Choose another file", Lucide.File, pb.foreground, pb.background, pick)
                TextAction("Start fresh", vm::startFresh, color = pb.mutedForeground)
            }
            null -> {
                BigButton("Choose punchboard.json", Lucide.File, pb.foreground, pb.background, pick)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, pb.border, RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button, onClick = vm::startFresh)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Start fresh", style = PbType.label, color = pb.foreground)
                        Text("A new time card on this phone. You can sync it with a file later.", style = PbType.bodySmall, color = pb.mutedForeground)
                    }
                    Icon(Lucide.ChevronRight, null, Modifier.size(18.dp), tint = pb.mutedForeground)
                }
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    val pb = LocalPb.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Bottom) {
        Text(label, fontFamily = Mono, fontSize = 13.sp, color = pb.foreground)
        DottedLeader(Modifier.weight(1f).padding(horizontal = 8.dp).padding(bottom = 4.dp))
        Text(value, fontFamily = Mono, fontSize = 13.sp, color = pb.foreground)
    }
}
