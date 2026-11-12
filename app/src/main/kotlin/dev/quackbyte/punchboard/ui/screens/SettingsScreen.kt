package dev.quackbyte.punchboard.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.quackbyte.punchboard.BuildConfig
import dev.quackbyte.punchboard.data.ImportFailure
import dev.quackbyte.punchboard.data.ImportResult
import dev.quackbyte.punchboard.data.SyncStatus
import dev.quackbyte.punchboard.model.Clock
import dev.quackbyte.punchboard.model.Currencies
import dev.quackbyte.punchboard.model.DateText
import dev.quackbyte.punchboard.model.WeekdayKeys
import dev.quackbyte.punchboard.model.formatMoney
import dev.quackbyte.punchboard.model.js
import dev.quackbyte.punchboard.model.normalizeSalary
import dev.quackbyte.punchboard.model.salaryBreakdown
import dev.quackbyte.punchboard.reminders.ReminderSettings
import dev.quackbyte.punchboard.ui.TrackerSnapshot
import dev.quackbyte.punchboard.ui.TrackerViewModel
import dev.quackbyte.punchboard.ui.components.FieldLabel
import dev.quackbyte.punchboard.ui.components.Lucide
import dev.quackbyte.punchboard.ui.components.MonoText
import dev.quackbyte.punchboard.ui.components.NumberField
import dev.quackbyte.punchboard.ui.components.Panel
import dev.quackbyte.punchboard.ui.components.Segmented
import dev.quackbyte.punchboard.ui.components.SelectField
import dev.quackbyte.punchboard.ui.components.SquareIconButton
import dev.quackbyte.punchboard.ui.components.Stamp
import dev.quackbyte.punchboard.ui.components.SwitchRow
import dev.quackbyte.punchboard.ui.components.TextAction
import dev.quackbyte.punchboard.ui.components.ValueRow
import dev.quackbyte.punchboard.ui.theme.LocalPb
import dev.quackbyte.punchboard.ui.theme.PbType
import dev.quackbyte.punchboard.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun SettingsScreen(vm: TrackerViewModel, snapshot: TrackerSnapshot, onBack: () -> Unit) {
    val pb = LocalPb.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SquareIconButton(Lucide.ChevronLeft, "Back", onBack)
            Column(Modifier.padding(start = 12.dp)) {
                Stamp("Punchboard")
                Text("Settings", style = PbType.title, color = pb.foreground)
            }
        }
        DataSection(vm)
        RemindersSection(vm)
        AppearanceSection(vm)
        ScheduleSection(vm, snapshot)
        TimeRulesSection(vm, snapshot)
        PaySection(vm, snapshot)
        ActivitySection(snapshot)
        AboutSection()
    }
}

@Composable
private fun Section(title: String, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Stamp(title)
        if (note != null) {
            Spacer(Modifier.height(2.dp))
            Text(note, style = PbType.bodySmall.copy(fontSize = 12.sp), color = LocalPb.current.mutedForeground)
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = PbType.bodySmall.copy(fontSize = 12.5.sp), color = LocalPb.current.mutedForeground)
}

private fun relative(instant: Instant): String {
    val seconds = java.time.Duration.between(instant, Instant.now()).seconds
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86400 -> "${seconds / 3600} h ago"
        else -> DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.UK).format(instant.atZone(java.time.ZoneId.systemDefault()))
    }
}

@Composable
private fun DataSection(vm: TrackerViewModel) {
    val pb = LocalPb.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by vm.syncStatus.collectAsState()
    val syncName = vm.repository.syncName
    var restore by remember { mutableStateOf<ImportResult?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val chooseFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.connectSync(uri, adoptFile = false)
    }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.connectSync(uri, adoptFile = false)
    }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = vm.backupText()
        if (uri != null && text != null) {
            scope.launch { message = if (vm.writeTo(uri, text)) "Backup saved." else "Couldn't save the backup." }
        }
    }
    val restoreBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { restore = vm.readImport(uri) }
    }

    Section("Data & sync") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(if (syncName != null) Lucide.Link else Lucide.File, null, Modifier.size(18.dp), tint = pb.mutedForeground)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(syncName ?: "Only on this phone", style = PbType.label, color = pb.foreground)
                Hint(
                    when (val s = status) {
                        SyncStatus.Off -> if (syncName == null) "Sync with the file your desktop app uses (Drive, Dropbox…)" else "Not synced yet"
                        SyncStatus.Syncing -> "Syncing…"
                        is SyncStatus.Synced -> "Synced ${relative(s.at)}"
                        is SyncStatus.Failed -> "Sync failed: ${s.message}"
                    },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        if (syncName != null) {
            TextAction("Sync now", vm::syncNow, icon = Lucide.RefreshCw)
            TextAction("Use another file…", { chooseFile.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, icon = Lucide.File)
            TextAction("Stop syncing", vm::disconnectSync, icon = Lucide.Unlink, color = pb.mutedForeground)
        } else {
            TextAction("Sync with a file…", { chooseFile.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, icon = Lucide.Link)
            TextAction("Create a new synced file…", { createFile.launch("punchboard.json") }, icon = Lucide.Plus)
        }
        Hint("Changes on either side are merged day by day. If the desktop app and the phone both change the same day, the one saved last wins.")
        Spacer(Modifier.height(10.dp))
        FieldLabel("Backup")
        TextAction("Export backup…", { exportBackup.launch("punchboard-${LocalDate.now()}.json") }, icon = Lucide.Download)
        TextAction("Restore backup…", { restoreBackup.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, icon = Lucide.Upload)
        message?.let { Hint(it) }
    }

    when (val result = restore) {
        is ImportResult.Ready -> AlertDialog(
            onDismissRequest = { restore = null },
            containerColor = pb.popover,
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "It replaces everything on this phone" + (if (syncName != null) " and in $syncName" else "") +
                        " with ${result.summary.monthCount} months, ${result.summary.daysLogged} logged days. Export a backup first if you want to keep the current data.",
                )
            },
            confirmButton = {
                TextButton({
                    vm.restoreBackup(result.document)
                    restore = null
                }) { Text("Restore", color = pb.destructive) }
            },
            dismissButton = { TextButton({ restore = null }) { Text("Cancel") } },
        )
        is ImportResult.Failed -> AlertDialog(
            onDismissRequest = { restore = null },
            containerColor = pb.popover,
            title = { Text("Can't restore that file") },
            text = { Text(importFailureText(result.reason)) },
            confirmButton = { TextButton({ restore = null }) { Text("OK") } },
        )
        null -> Unit
    }
}

fun importFailureText(reason: ImportFailure) = when (reason) {
    ImportFailure.Unreadable -> "The file couldn't be read. Try downloading it to the phone first."
    ImportFailure.TooLarge -> "That file is too large to be a Punchboard data file."
    ImportFailure.NotPunchboard -> "That isn't a Punchboard data file. Pick the punchboard.json your desktop app saves to, or a backup you exported."
    ImportFailure.NewerVersion -> "This file comes from a newer Punchboard. Update the app and try again."
}

@Composable
private fun RemindersSection(vm: TrackerViewModel) {
    val pb = LocalPb.current
    val context = LocalContext.current
    val settings by vm.reminders.collectAsState()
    var granted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    // Re-read when coming back from the system settings page.
    var promoted by remember { mutableStateOf(dev.quackbyte.punchboard.reminders.Notifications.canPromote(context)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        promoted = dev.quackbyte.punchboard.reminders.Notifications.canPromote(context)
        onPauseOrDispose { }
    }
    var editing by remember { mutableStateOf<String?>(null) }
    fun update(next: ReminderSettings) {
        vm.setReminders(next)
        if (!granted && Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Section("Reminders") {
        if (!granted) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(pb.vacation.copy(alpha = 0.12f))
                    .border(1.dp, pb.vacation.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) {
                        if (Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(Lucide.Bell, null, Modifier.size(18.dp), tint = pb.vacationText)
                Text("Notifications are off. Tap to allow them so reminders can show.", Modifier.padding(start = 10.dp), style = PbType.bodySmall, color = pb.foreground)
            }
            Spacer(Modifier.height(6.dp))
        }
        SwitchRow(
            "Punch-in nudge",
            settings.punchInAt?.let { "On workdays when the card is still empty at ${it}" } ?: "A nudge on workdays if you haven't punched in by a set time.",
            settings.punchInAt != null,
            { update(settings.copy(punchInAt = if (it) LocalTime.of(9, 0) else null)) },
        )
        settings.punchInAt?.let { time -> ValueRow("Nudge at", time.toString(), { editing = "in" }) }
        SwitchRow(
            "Still on the clock?",
            "A reminder once today's scheduled hours are done and you haven't punched out.",
            settings.stillOnClock,
            { update(settings.copy(stillOnClock = it)) },
        )
        SwitchRow(
            "Forgot to punch out?",
            settings.forgotPunchOutAt?.let { "If the clock is still running at $it" } ?: "A reminder if the clock is still running late in the day.",
            settings.forgotPunchOutAt != null,
            { update(settings.copy(forgotPunchOutAt = if (it) LocalTime.of(20, 0) else null)) },
        )
        settings.forgotPunchOutAt?.let { time -> ValueRow("Remind at", time.toString(), { editing = "out" }) }
        SwitchRow(
            "Live Update",
            "While you're punched in: a timer in the status bar and on the lock screen, today's progress and a Punch out button.",
            settings.liveClock,
            { update(settings.copy(liveClock = it)) },
        )
        if (settings.liveClock && granted && !promoted) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(pb.muted.copy(alpha = 0.6f))
                    .clickable(role = Role.Button) {
                        context.startActivity(dev.quackbyte.punchboard.reminders.Notifications.promotionSettings(context))
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(Lucide.Clock, null, Modifier.size(18.dp), tint = pb.mutedForeground)
                Text(
                    "Live Updates are off for Punchboard, so the timer shows as a plain notification. Tap to turn them on.",
                    Modifier.padding(start = 10.dp),
                    style = PbType.bodySmall,
                    color = pb.foreground,
                )
            }
        }
        Hint("Add the Punch tile to Quick Settings or the widget to your home screen to punch in and out from anywhere.")
    }

    editing?.let { which ->
        val current = if (which == "in") settings.punchInAt else settings.forgotPunchOutAt
        TimeDialog(
            title = if (which == "in") "Nudge at" else "Remind at",
            initialMinutes = (current ?: LocalTime.of(9, 0)).let { it.hour * 60 + it.minute },
            onDismiss = { editing = null },
            onConfirm = { minutes ->
                val time = LocalTime.of(minutes / 60, minutes % 60)
                update(if (which == "in") settings.copy(punchInAt = time) else settings.copy(forgotPunchOutAt = time))
                editing = null
            },
        )
    }
}

@Composable
private fun AppearanceSection(vm: TrackerViewModel) {
    val theme by vm.theme.collectAsState()
    Section("Appearance") {
        Segmented(
            listOf(ThemeMode.System to "System", ThemeMode.Light to "Light", ThemeMode.Dark to "Dark"),
            theme,
            vm::setTheme,
            Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ScheduleSection(vm: TrackerViewModel, snapshot: TrackerSnapshot) {
    val pb = LocalPb.current
    val month = snapshot.monthData
    val tracker = snapshot.tracker
    Section("Schedule", "Workdays and hours apply to the ${DateText.monthYear(snapshot.viewMonth)} period.") {
        FieldLabel("Working weekdays")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayOfWeek.entries.forEach { day ->
                val key = WeekdayKeys.of(day)
                val on = key in month.selectedDays
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (on) pb.primary else pb.background.copy(alpha = 0.6f))
                        .border(1.dp, if (on) pb.primary else pb.input, RoundedCornerShape(12.dp))
                        .clickable(role = Role.Checkbox, onClickLabel = key) { vm.editSettings { e, m -> e.toggleWeekday(m, key) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(key.take(2).replaceFirstChar { it.uppercase() }, style = PbType.label.copy(fontSize = 13.sp), color = if (on) pb.onPrimary else pb.foreground)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField("Payslip start day", tracker.payslipStartDay.toDouble(), { v ->
                val day = v.toInt().coerceIn(1, 31)
                vm.updateSettings("Updated payslip start day to $day") { it.copy(payslipStartDay = day) }
            }, Modifier.weight(1f), decimals = false)
            NumberField("Vacation days/year", tracker.vacationDaysPerYear, { v ->
                val days = v.coerceIn(0.0, 366.0)
                vm.updateSettings("Updated vacation allowance to ${days.js()} days") { it.copy(vacationDaysPerYear = days) }
            }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        SelectField(
            "Leave year starts",
            (1..12).map { it to DateText.monthLong(YearMonth.of(2026, it)) },
            tracker.leaveYearStartMonth,
            { m -> vm.updateSettings("Updated leave year start to month $m") { it.copy(leaveYearStartMonth = m) } },
        )
    }
}

@Composable
private fun TimeRulesSection(vm: TrackerViewModel, snapshot: TrackerSnapshot) {
    val rules = snapshot.rules
    val month = snapshot.monthData
    val salary = normalizeSalary(month.salary)
    val weeklyHours = if (salary.enabled && salary.weeklyHours > 0) salary.weeklyHours else month.selectedDays.size * month.hoursPerDay
    val overtimeSeparate = if (salary.enabled) salary.overtimePaid else rules.overtimeMultiplier > 1
    var pickingDate by remember { mutableStateOf(false) }

    Section("Time rules") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField("Auto break (min)", rules.breakMinutes, { v ->
                vm.updateSettings("Updated breakMinutes") { it.copy(breakMinutes = v.coerceIn(0.0, 240.0)) }
            }, Modifier.weight(1f))
            NumberField("On days over (h)", rules.breakAfterHours, { v ->
                vm.updateSettings("Updated breakAfterHours") { it.copy(breakAfterHours = v.coerceIn(0.0, 24.0)) }
            }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectField(
                "Round punches",
                listOf(0, 5, 6, 10, 15, 30).map { it to if (it == 0) "Exact" else "Nearest $it min" },
                rules.roundingMinutes,
                { m -> vm.updateSettings("Updated roundingMinutes") { it.copy(roundingMinutes = m) } },
                Modifier.weight(1f),
            )
            NumberField("Overtime rate (×)", rules.overtimeMultiplier, { v ->
                vm.updateSettings("Updated overtimeMultiplier") { it.copy(overtimeMultiplier = v.coerceIn(1.0, 5.0)) }
            }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Hint(
            (if (rules.breakMinutes > 0) "Days with ${rules.breakAfterHours.js()}h or more on the clock lose ${rules.breakMinutes.js()} min, minus any gaps between sessions. " else "") +
                when {
                    salary.enabled && salary.overtimePaid -> "Hours on days off are paid on top of the salary at ${rules.overtimeMultiplier.js()}×."
                    salary.enabled -> "Hours on days off are tracked as unpaid overtime."
                    rules.overtimeMultiplier > 1 -> "Hours on days off are paid at ${rules.overtimeMultiplier.js()}×."
                    else -> "Set an overtime rate above 1× to pay days off at a premium."
                },
        )
        SwitchRow(
            "Long workdays count as overtime",
            if (salary.enabled && !salary.overtimePaid) {
                "Hours beyond the scheduled ${month.hoursPerDay.js()}h on a workday are tracked as unpaid overtime too."
            } else {
                "Hours beyond the scheduled ${month.hoursPerDay.js()}h on a workday are paid at the overtime rate too."
            },
            rules.overtimeIncludesLongDays,
            { v -> vm.updateSettings("Updated overtimeIncludesLongDays") { it.copy(overtimeIncludesLongDays = v) } },
            enabled = salary.enabled || overtimeSeparate,
        )
        SwitchRow(
            "Balance the week",
            "Today's target is what's left of the week's ${weeklyHours.js()}h, shared over the workdays still to come, so long days early on mean a shorter one later.",
            rules.balanceWeek,
            { v -> vm.updateSettings("Updated balanceWeek") { it.copy(balanceWeek = v) } },
        )
        SwitchRow(
            "Carry hours to next week",
            "Hours over or under in earlier weeks come off or go onto this week's ${weeklyHours.js()}h: 5h over this week means 5h less the next.",
            rules.carryBalance,
            { v -> vm.updateSettings("Updated carryBalance") { it.copy(carryBalance = v) } },
            enabled = rules.balanceWeek,
        )
        if (rules.balanceWeek && rules.carryBalance) {
            SwitchRow(
                "Reset the balance each January",
                "Hours carried start again from zero on 1 January.",
                rules.balanceResetsYearly,
                { v -> vm.updateSettings("Updated balanceResetsYearly") { it.copy(balanceResetsYearly = v) } },
            )
            ValueRow(
                "Count balance from",
                rules.balanceStartDate.ifEmpty { "Always" },
                { pickingDate = true },
                description = "Days before this date never count.",
            )
            if (rules.balanceStartDate.isNotEmpty()) {
                TextAction("Count from the start", { vm.updateSettings("Updated balanceStartDate") { it.copy(balanceStartDate = "") } }, color = LocalPb.current.mutedForeground)
            }
            Spacer(Modifier.height(6.dp))
            NumberField("Most hours carried (0 = no limit)", rules.balanceCapHours, { v ->
                vm.updateSettings("Updated balanceCapHours") { it.copy(balanceCapHours = v.coerceIn(0.0, 1000.0)) }
            })
        }
    }

    if (pickingDate) {
        val initial = rules.balanceStartDate.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        vm.updateSettings("Updated balanceStartDate") { it.copy(balanceStartDate = date) }
                    }
                    pickingDate = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton({ pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun PaySection(vm: TrackerViewModel, snapshot: TrackerSnapshot) {
    val month = snapshot.monthData
    val salary = normalizeSalary(month.salary)
    Section("Pay & currency", "Pay settings apply from the ${DateText.monthYear(snapshot.viewMonth)} period on.") {
        FieldLabel("Paid")
        Spacer(Modifier.height(6.dp))
        Segmented(
            listOf(false to "By the hour", true to "Fixed salary"),
            salary.enabled,
            { on ->
                vm.editSettings { e, m -> e.setSalary(m, { it.copy(enabled = on) }, "Switched to ${if (on) "a fixed salary" else "hourly pay"}") }
            },
            Modifier.fillMaxWidth(),
        )
        if (salary.enabled) {
            Spacer(Modifier.height(12.dp))
            FieldLabel("Salary from")
            Spacer(Modifier.height(6.dp))
            Segmented(
                listOf("weekly" to "Rate × weekly hours", "yearly" to "Yearly amount"),
                salary.basis,
                { basis -> vm.editSettings { e, m -> e.setSalary(m, { it.copy(basis = basis) }, "Updated salary basis") } },
                Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField("Hours/day", month.hoursPerDay, { v -> vm.editSettings { e, m -> e.setHoursPerDay(m, v) } }, Modifier.weight(1f))
            if (salary.enabled && salary.basis == "yearly") {
                NumberField("Salary (per year)", salary.yearlyAmount, { v ->
                    vm.editSettings { e, m -> e.setSalary(m, { it.copy(yearlyAmount = v) }, "Updated salary yearlyAmount") }
                }, Modifier.weight(1f))
            } else {
                NumberField("Rate (per hour)", month.hourlyRate, { v -> vm.editSettings { e, m -> e.setHourlyRate(m, v) } }, Modifier.weight(1f))
            }
        }
        if (salary.enabled) {
            Spacer(Modifier.height(12.dp))
            NumberField("Contracted h/week", salary.weeklyHours, { v ->
                vm.editSettings { e, m -> e.setSalary(m, { it.copy(weeklyHours = v) }, "Updated salary weeklyHours") }
            })
            val amounts = salaryBreakdown(salary, month.hourlyRate)
            Spacer(Modifier.height(6.dp))
            MonoText(
                "${formatMoney(amounts.perPeriod, month.currency)} per payslip · ${formatMoney(amounts.yearly, month.currency)} a year · ${formatMoney(amounts.rate, month.currency)}/h",
                size = 11.5f,
                color = LocalPb.current.mutedForeground,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField("Tax (%)", month.taxPercent, { v -> vm.editSettings { e, m -> e.setTaxPercent(m, v) } }, Modifier.weight(1f))
            NumberField("Extra deduction", month.extraDeduction, { v -> vm.editSettings { e, m -> e.setExtraDeduction(m, v) } }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        NumberField("Holiday hours/day", month.defaultHours, { v -> vm.editSettings { e, m -> e.setDefaultHours(m, v) } })
        if (salary.enabled) {
            SwitchRow(
                "Pay overtime on top",
                "Overtime is paid at the salary's hourly rate × ${snapshot.rules.overtimeMultiplier.js()}. Off, it's tracked but unpaid.",
                salary.overtimePaid,
                { on -> vm.editSettings { e, m -> e.setSalary(m, { it.copy(overtimePaid = on) }, "Updated salary overtimePaid") } },
            )
        }
        Spacer(Modifier.height(12.dp))
        FieldLabel("Currency")
        Spacer(Modifier.height(6.dp))
        Segmented(
            Currencies.codes.map { it to "${Currencies.symbol(it)} $it" },
            month.currency,
            { code -> vm.editSettings { e, m -> e.setCurrency(m, code) } },
            Modifier.fillMaxWidth(),
        )
        SwitchRow(
            "Currency conversion",
            "Show a second currency on every payslip line, at a rate you set.",
            month.currencyConversionEnabled,
            { on -> vm.editSettings { e, m -> e.setConversionEnabled(m, on) } },
        )
        if (month.currencyConversionEnabled) {
            Segmented(
                Currencies.codes.map { it to "${month.currency} → $it" },
                month.secondaryCurrency,
                { code -> vm.editSettings { e, m -> e.setSecondaryCurrency(m, code) } },
                Modifier.fillMaxWidth(),
            )
            if (month.currency != month.secondaryCurrency) {
                Spacer(Modifier.height(12.dp))
                NumberField(
                    "Conversion rate (${month.currency} → ${month.secondaryCurrency})",
                    month.conversionRate,
                    { v -> vm.editSettings { e, m -> e.setConversionRate(m, v) } },
                )
            }
        }
    }
}

@Composable
private fun ActivitySection(snapshot: TrackerSnapshot) {
    val pb = LocalPb.current
    val format = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.UK)
    Section("Recent activity") {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(pb.muted.copy(alpha = 0.5f))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (snapshot.recentActivity.isEmpty()) Hint("No activity yet.")
            snapshot.recentActivity.forEach { entry ->
                val time = runCatching { format.format(Instant.parse(entry.timestamp).atZone(java.time.ZoneId.systemDefault())) }.getOrDefault("")
                Row {
                    MonoText(time, size = 10.5f, color = pb.mutedForeground.copy(alpha = 0.7f))
                    Text(" ${entry.message}", style = PbType.bodySmall.copy(fontSize = 12.sp), color = pb.mutedForeground)
                }
            }
        }
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    Section("About") {
        Hint("Punchboard ${BuildConfig.VERSION_NAME} for Android · by QuackByte · MIT licence")
        TextAction("Releases & updates", {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/QuackByte/Punchboard-Android/releases")))
        }, icon = Lucide.ExternalLink)
        TextAction("Punchboard for desktop", {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://quackbyte.dev/punchboard/")))
        }, icon = Lucide.ExternalLink)
    }
}

@Suppress("unused")
private fun clockLabel(minutes: Int) = Clock.format(minutes)
