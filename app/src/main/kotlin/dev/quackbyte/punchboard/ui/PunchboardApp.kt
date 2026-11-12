package dev.quackbyte.punchboard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.quackbyte.punchboard.R
import dev.quackbyte.punchboard.export.TimesheetExport
import dev.quackbyte.punchboard.model.DateText
import dev.quackbyte.punchboard.model.MonthKeys
import dev.quackbyte.punchboard.model.TimesheetMeta
import dev.quackbyte.punchboard.model.buildTimesheetRows
import dev.quackbyte.punchboard.model.periodLabel
import dev.quackbyte.punchboard.ui.components.Lucide
import dev.quackbyte.punchboard.ui.components.SquareIconButton
import dev.quackbyte.punchboard.ui.components.dotGrid
import dev.quackbyte.punchboard.ui.screens.CardScreen
import dev.quackbyte.punchboard.ui.screens.DayEditorActions
import dev.quackbyte.punchboard.ui.screens.DayEditorSheet
import dev.quackbyte.punchboard.ui.screens.DayMenuActions
import dev.quackbyte.punchboard.ui.screens.PayScreen
import dev.quackbyte.punchboard.ui.screens.SettingsScreen
import dev.quackbyte.punchboard.ui.screens.TodayScreen
import dev.quackbyte.punchboard.ui.screens.WelcomeScreen
import dev.quackbyte.punchboard.ui.screens.YearScreen
import dev.quackbyte.punchboard.ui.theme.LocalPb
import dev.quackbyte.punchboard.ui.theme.Mono
import dev.quackbyte.punchboard.ui.theme.PbType
import dev.quackbyte.punchboard.ui.theme.PunchboardTheme
import dev.quackbyte.punchboard.ui.theme.Sans
import java.time.LocalDate
import java.time.YearMonth

enum class Tab(val label: String) { Today("Today"), Card("Card"), Year("Year"), Pay("Pay") }

@Composable
fun PunchboardApp(vm: TrackerViewModel, openTab: Tab? = null) {
    val theme by vm.theme.collectAsState()
    val screen by vm.screen.collectAsState()
    PunchboardTheme(theme) {
        val pb = LocalPb.current
        Box(Modifier.fillMaxSize().dotGrid(pb.background, pb.dot)) {
            when (val s = screen) {
                Screen.Loading -> Unit
                Screen.Welcome -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) { WelcomeScreen(vm) }
                is Screen.Ready -> Main(vm, s.snapshot, openTab)
            }
        }
    }
}

@Composable
private fun Main(vm: TrackerViewModel, snapshot: TrackerSnapshot, openTab: Tab?) {
    val pb = LocalPb.current
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingMonth by remember { mutableStateOf(false) }
    val undo by vm.undo.collectAsState()

    LaunchedEffect(openTab) { if (openTab != null) tab = openTab }

    // Reminders and the live clock are on by default; ask once for the permission they need.
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { vm.setReminders(vm.reminders.value) }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notifications", false) &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    BackHandler(enabled = !settingsOpen && tab != Tab.Today) { tab = Tab.Today }

    val editDay: (LocalDate) -> Unit = { date ->
        if (date !in dev.quackbyte.punchboard.model.PayslipPeriods.range(snapshot.viewMonth, snapshot.tracker.payslipStartDay)) {
            vm.showMonth(snapshot.tracker.currentPeriod(date))
        }
        editing = date.toString()
    }

    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp, bottom = 120.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Crossfade(settingsOpen, label = "settings") { inSettings ->
            if (inSettings) {
                SettingsScreen(vm, snapshot) { settingsOpen = false }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.widthIn(max = 720.dp)) {
                    Header(onSettings = { settingsOpen = true })
                    if (tab != Tab.Year) {
                        MonthNavigator(snapshot, vm::moveMonth, { pickingMonth = true }, vm::goToCurrentPeriod)
                    }
                    when (tab) {
                        Tab.Today -> TodayScreen(snapshot, vm::punchIn, vm::punchOut, vm::goToCurrentPeriod, editDay)
                        Tab.Card -> CardScreen(
                            snapshot,
                            DayMenuActions(
                                onLog = editDay,
                                onException = { date, type, current -> vm.setException(snapshot.viewMonth, date, type, current) },
                                onReset = { date, workday -> vm.resetDay(snapshot.viewMonth, date, workday) },
                            ),
                        )
                        Tab.Year -> YearScreen(snapshot, vm::setGraphYear) { month ->
                            vm.showMonth(month)
                            tab = Tab.Card
                        }
                        Tab.Pay -> PayScreen(
                            snapshot,
                            onExportCsv = { exportTimesheet(context, snapshot, pdf = false) },
                            onExportPdf = { exportTimesheet(context, snapshot, pdf = true) },
                        )
                    }
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        AnimatedVisibility(undo != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            undo?.let { offer -> UndoToast(offer.message, vm::undo, vm::dismissUndo) }
        }
        if (!settingsOpen) {
            Spacer(Modifier.height(10.dp))
            BottomBar(tab) { tab = it }
        }
    }

    editing?.let { iso ->
        val date = LocalDate.parse(iso)
        val month = snapshot.viewMonth
        DayEditorSheet(
            snapshot = snapshot,
            date = date,
            actions = DayEditorActions(
                applyText = { d, text -> vm.applyDayInput(month, d, text) },
                setEntries = { d, entries -> vm.setDayEntries(month, d, entries) },
                removeEntry = { d, index, entry -> vm.removeSession(month, d, index, entry) },
                reset = { d, workday -> vm.resetDay(month, d, workday) },
                setException = { d, type, current -> vm.setException(month, d, type, current) },
            ),
            onDismiss = { editing = null },
            undoMessage = undo?.message,
            onUndo = vm::undo,
        )
    }

    if (pickingMonth) {
        MonthPicker(snapshot, onPick = {
            vm.showMonth(it)
            pickingMonth = false
        }, onDismiss = { pickingMonth = false })
    }
}

private fun exportTimesheet(context: android.content.Context, snapshot: TrackerSnapshot, pdf: Boolean) {
    val month = snapshot.monthData
    val vacation = snapshot.periodVacation
    val meta = TimesheetMeta(
        title = DateText.monthYear(snapshot.viewMonth),
        periodLabel = periodLabel(snapshot.viewMonth, snapshot.tracker.payslipStartDay, withYear = true),
        currency = month.currency,
        taxPercent = month.taxPercent,
        overtimeMultiplier = snapshot.rules.overtimeMultiplier,
        conversion = if (snapshot.showConversion) month.secondaryCurrency to month.conversionRate else null,
        vacation = if (vacation.allowance > 0) Triple(vacation.label, vacation.taken + vacation.booked, vacation.allowance) else null,
    )
    val rows = buildTimesheetRows(snapshot.period, snapshot.timeEntries)
    val key = MonthKeys.of(snapshot.viewMonth)
    if (pdf) {
        TimesheetExport.sharePdf(context, key, rows, snapshot.period, snapshot.pay, meta)
    } else {
        TimesheetExport.shareCsv(context, key, rows, snapshot.period, snapshot.pay, meta)
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    val pb = LocalPb.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(36.dp))
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text("Punchboard", fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = pb.foreground)
            Text("TIME CARD", style = PbType.stamp.copy(fontSize = 9.5.sp, letterSpacing = 2.sp), color = pb.mutedForeground)
        }
        SquareIconButton(Lucide.Settings, "Settings", onSettings)
    }
}

@Composable
private fun MonthNavigator(snapshot: TrackerSnapshot, onMove: (Long) -> Unit, onPick: () -> Unit, onToday: () -> Unit) {
    val pb = LocalPb.current
    val shape = RoundedCornerShape(16.dp)
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(shape)
                .background(pb.card)
                .border(1.dp, pb.border, shape)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavArrow(Lucide.ChevronLeft, "Previous period") { onMove(-1) }
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Choose a period", onClick = onPick).padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(DateText.monthYear(snapshot.viewMonth), fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = pb.foreground)
                    Icon(Lucide.ChevronDown, null, Modifier.padding(start = 4.dp).size(14.dp), tint = pb.mutedForeground)
                }
                Text(periodLabel(snapshot.viewMonth, snapshot.tracker.payslipStartDay), fontFamily = Mono, fontSize = 11.sp, color = pb.mutedForeground)
            }
            NavArrow(Lucide.ChevronRight, "Next period") { onMove(1) }
        }
        if (!snapshot.isViewingCurrentPeriod) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
                Text(
                    "Back to today",
                    Modifier.clip(RoundedCornerShape(999.dp)).clickable(role = Role.Button, onClick = onToday)
                        .background(pb.primary.copy(alpha = 0.1f)).padding(horizontal = 14.dp, vertical = 6.dp),
                    style = PbType.label.copy(fontSize = 13.sp),
                    color = pb.primary,
                )
            }
        }
    }
}

@Composable
private fun NavArrow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(18.dp), tint = LocalPb.current.foreground)
    }
}

@Composable
private fun MonthPicker(snapshot: TrackerSnapshot, onPick: (YearMonth) -> Unit, onDismiss: () -> Unit) {
    val pb = LocalPb.current
    var year by remember { mutableStateOf(snapshot.viewMonth.year) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = pb.popover,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NavArrow(Lucide.ChevronLeft, "Previous year") { year-- }
                Text(year.toString(), Modifier.weight(1f), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, textAlign = TextAlign.Center, color = pb.foreground)
                NavArrow(Lucide.ChevronRight, "Next year") { year++ }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..12).chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { m ->
                            val month = YearMonth.of(year, m)
                            val selected = month == snapshot.viewMonth
                            val current = month == snapshot.currentPeriod
                            Column(
                                Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) pb.primary else pb.background.copy(alpha = 0.6f))
                                    .border(1.dp, if (current && !selected) pb.primary else pb.border, RoundedCornerShape(12.dp))
                                    .clickable(role = Role.Button) { onPick(month) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(DateText.monthShort(month), style = PbType.label, color = if (selected) pb.onPrimary else pb.foreground)
                                if (month in snapshot.savedMonths) {
                                    Box(Modifier.padding(top = 3.dp).size(4.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) pb.onPrimary else pb.primary))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ onPick(snapshot.currentPeriod) }) { Text("Today") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

@Composable
private fun UndoToast(message: String, onUndo: () -> Unit, onDismiss: () -> Unit) {
    val pb = LocalPb.current
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(pb.ink)
            .border(1.dp, pb.inkBorder, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, Modifier.weight(1f).padding(vertical = 14.dp), style = PbType.bodySmall.copy(fontSize = 14.sp), color = pb.inkForeground)
        Text(
            "Undo",
            Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onUndo).padding(horizontal = 12.dp, vertical = 12.dp),
            style = PbType.label,
            color = pb.brand,
        )
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClickLabel = "Dismiss", onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.X, "Dismiss", Modifier.size(16.dp), tint = pb.inkMuted)
        }
    }
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val pb = LocalPb.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .shadow(20.dp, shape, ambientColor = androidx.compose.ui.graphics.Color.Black, spotColor = androidx.compose.ui.graphics.Color.Black)
            .clip(shape)
            .background(pb.card)
            .border(1.dp, pb.border, shape)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { tab ->
            val active = tab == selected
            val icon = when (tab) {
                Tab.Today -> Lucide.Clock
                Tab.Card -> Lucide.Calendar
                Tab.Year -> Lucide.BarChart
                Tab.Pay -> Lucide.Receipt
            }
            Column(
                Modifier
                    .weight(1f)
                    .height(54.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(role = Role.Tab, onClickLabel = tab.label) { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(icon, null, Modifier.size(20.dp), tint = if (active) pb.primary else pb.mutedForeground)
                Spacer(Modifier.height(3.dp))
                Text(tab.label.uppercase(), style = PbType.stamp.copy(fontSize = 10.sp), color = if (active) pb.primary else pb.mutedForeground)
            }
        }
    }
}
