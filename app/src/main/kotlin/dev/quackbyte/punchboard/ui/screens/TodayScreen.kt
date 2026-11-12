package dev.quackbyte.punchboard.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.quackbyte.punchboard.model.Clock
import dev.quackbyte.punchboard.model.DateText
import dev.quackbyte.punchboard.model.TimeEntry
import dev.quackbyte.punchboard.model.entryMinutes
import dev.quackbyte.punchboard.model.estimateFinish
import dev.quackbyte.punchboard.model.formatDuration
import dev.quackbyte.punchboard.model.formatSignedDuration
import dev.quackbyte.punchboard.model.sumEntriesHours
import dev.quackbyte.punchboard.model.workedTime
import dev.quackbyte.punchboard.ui.TrackerSnapshot
import dev.quackbyte.punchboard.ui.components.BigButton
import dev.quackbyte.punchboard.ui.components.Dot
import dev.quackbyte.punchboard.ui.components.InkGlow
import dev.quackbyte.punchboard.ui.components.Lucide
import dev.quackbyte.punchboard.ui.components.MonoText
import dev.quackbyte.punchboard.ui.components.Panel
import dev.quackbyte.punchboard.ui.components.PanelShape
import dev.quackbyte.punchboard.ui.components.Stamp
import dev.quackbyte.punchboard.ui.theme.LocalPb
import dev.quackbyte.punchboard.ui.theme.Mono
import dev.quackbyte.punchboard.ui.theme.PbType
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import kotlin.math.abs

@Composable
fun TodayScreen(
    snapshot: TrackerSnapshot,
    onPunchIn: () -> Unit,
    onPunchOut: () -> Unit,
    onGoToCurrentPeriod: () -> Unit,
    onEditDay: (java.time.LocalDate) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PunchClockCard(snapshot, onPunchIn, onPunchOut, onGoToCurrentPeriod, onEditDay)
        HoursCard(snapshot)
    }
}

/** Ticks every second, for the clock face. */
@Composable
fun rememberNow(intervalMillis: Long = 1000): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(intervalMillis) {
        while (true) {
            now = LocalDateTime.now()
            delay(intervalMillis - System.currentTimeMillis() % intervalMillis)
        }
    }
    return now
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PunchClockCard(
    snapshot: TrackerSnapshot,
    onPunchIn: () -> Unit,
    onPunchOut: () -> Unit,
    onGoToCurrentPeriod: () -> Unit,
    onEditDay: (java.time.LocalDate) -> Unit,
) {
    val pb = LocalPb.current
    val now = rememberNow()
    val nowMinutes = now.hour * 60 + now.minute
    val todayKey = dev.quackbyte.punchboard.model.DayKeys.of(now.toLocalDate())
    val open = snapshot.openSession
    val isRunning = open?.key == todayKey
    val isStale = open != null && !isRunning
    val viewing = snapshot.isViewingCurrentPeriod
    val entries = snapshot.todayEntries
    val targetHours = snapshot.target.hours
    val todayHours = if (viewing) sumEntriesHours(entries, nowMinutes) else 0.0
    val runningMinutes = if (isRunning) entryMinutes(open!!.entry, nowMinutes) else 0
    val finish = if (viewing && !isStale) estimateFinish(entries, targetHours, snapshot.rules, nowMinutes) else null
    val overMinutes = if (viewing && !isStale && finish == null && targetHours > 0 && entries.isNotEmpty()) {
        workedTime(entries, snapshot.rules.copy(roundingMinutes = 0), nowMinutes).workedMinutes - targetHours * 60
    } else {
        null
    }
    val progress = when {
        targetHours > 0 -> (todayHours / targetHours).coerceAtMost(1.0)
        todayHours > 0 -> 1.0
        else -> 0.0
    }
    val accent by animateColorAsState(if (isRunning) pb.signal else pb.brand, tween(700), label = "accent")
    val animatedProgress by animateFloatAsState(progress.toFloat(), tween(700), label = "progress")

    Column(
        Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(pb.ink)
            .background(InkGlow(accent))
            .border(1.dp, pb.inkBorder, PanelShape)
            .padding(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stamp("01 · Punch clock", color = pb.inkMuted)
            MonoText(DateText.weekdayDayMonth(now.toLocalDate()), size = 11f, color = pb.inkMuted)
        }
        Spacer(Modifier.height(14.dp))
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.semantics { contentDescription = "Time ${Clock.format(nowMinutes)}" },
        ) {
            Text("%02d".format(now.hour), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 64.sp, color = pb.inkForeground, lineHeight = 64.sp)
            Text(
                ":",
                Modifier.alpha(if (now.second % 2 == 0) 1f else 0.4f).padding(horizontal = 1.dp),
                fontFamily = Mono,
                fontWeight = FontWeight.SemiBold,
                fontSize = 64.sp,
                lineHeight = 64.sp,
                color = accent,
            )
            Text("%02d".format(now.minute), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 64.sp, color = pb.inkForeground, lineHeight = 64.sp)
            Spacer(Modifier.width(8.dp))
            Text("%02d".format(now.second), Modifier.padding(bottom = 8.dp), fontFamily = Mono, fontSize = 22.sp, color = pb.inkMuted)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                isRunning -> {
                    PulsingDot(pb.signal)
                    Spacer(Modifier.width(10.dp))
                    Text("On the clock since ", style = PbType.body, color = pb.inkForeground)
                    MonoText(open!!.entry.start, size = 15f, color = pb.inkForeground)
                    Text(" · ${formatDuration(runningMinutes / 60.0)}", style = PbType.body, color = pb.inkMuted)
                }
                isStale -> {
                    Dot(pb.vacation)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Session left open on ${open!!.date?.let(DateText::weekdayDayMonth) ?: open.key}",
                        style = PbType.body,
                        color = pb.inkForeground,
                    )
                }
                else -> {
                    Dot(pb.inkMuted.copy(alpha = 0.5f))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            !viewing -> "Viewing another period"
                            entries.isNotEmpty() -> "Off the clock"
                            else -> "Not punched in yet today"
                        },
                        style = PbType.body,
                        color = pb.inkMuted,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MonoText("Today", size = 12.5f, color = pb.inkMuted)
            Row {
                MonoText(formatDuration(todayHours), size = 12.5f, color = pb.inkForeground)
                if (targetHours > 0) MonoText(" / ${formatDuration(targetHours)}", size = 12.5f, color = pb.inkMuted)
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(99.dp)).background(pb.inkForeground.copy(alpha = 0.1f))) {
            Box(Modifier.fillMaxWidth(animatedProgress).fillMaxHeight().clip(RoundedCornerShape(99.dp)).background(accent))
        }
        if (finish != null) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row {
                    MonoText(
                        when {
                            isRunning -> "Done at "
                            entries.isNotEmpty() -> "Back now, done at "
                            else -> "Start now, done at "
                        },
                        size = 11.5f,
                        color = pb.inkMuted,
                    )
                    MonoText(Clock.format(finish.finishMinutes), size = 11.5f, color = pb.inkForeground)
                    if (finish.finishMinutes >= 1440) MonoText(" tomorrow", size = 11.5f, color = pb.inkMuted)
                }
                MonoText("${formatDuration(finish.remainingMinutes / 60.0)} to go", size = 11.5f, color = pb.inkMuted)
            }
        }
        if (overMinutes != null && overMinutes >= 0) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MonoText("Done for today", size = 11.5f, color = pb.inkForeground)
                if (overMinutes >= 1) MonoText("+${formatDuration(overMinutes / 60.0)} over", size = 11.5f, color = pb.inkMuted)
            }
        }
        val carried = snapshot.target.carried
        if (viewing && abs(carried) >= 1.0 / 60) {
            Spacer(Modifier.height(4.dp))
            MonoText(
                "${formatSignedDuration(carried)} ${if (carried > 0) "banked from" else "owed from"} earlier weeks",
                size = 11.5f,
                color = pb.inkMuted,
            )
        }

        if (entries.isNotEmpty() && viewing) {
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entries.forEach { SessionChip(it) }
            }
        }

        Spacer(Modifier.height(20.dp))
        when {
            !viewing -> BigButton(
                "Jump to today", Lucide.CalendarClock, pb.inkForeground.copy(alpha = 0.06f), pb.inkForeground, onGoToCurrentPeriod,
                border = BorderStroke(1.dp, pb.inkBorder),
            )
            isStale -> BigButton(
                "Set an end time", Lucide.CalendarClock, pb.inkForeground.copy(alpha = 0.06f), pb.inkForeground,
                { open!!.date?.let(onEditDay) },
                border = BorderStroke(1.dp, pb.inkBorder),
            )
            isRunning -> BigButton(
                "Punch out", Lucide.LogOut, pb.signal, pb.onSignal, onPunchOut,
                border = BorderStroke(1.dp, pb.signal.copy(alpha = 0.6f)), glow = pb.signal,
            )
            else -> BigButton(
                "Punch in", Lucide.LogIn, pb.brand, pb.onBrand, onPunchIn,
                border = BorderStroke(1.dp, pb.brand.copy(alpha = 0.6f)), glow = pb.brand,
            )
        }
    }
}

@Composable
private fun SessionChip(entry: TimeEntry) {
    val pb = LocalPb.current
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(pb.inkForeground.copy(alpha = 0.04f))
            .border(1.dp, pb.inkBorder, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText(entry.start, size = 13f, color = pb.inkForeground)
        Icon(Lucide.ArrowRight, null, Modifier.padding(horizontal = 6.dp).size(12.dp), tint = pb.inkMuted)
        if (entry.end != null) MonoText(entry.end, size = 13f, color = pb.inkForeground) else MonoText("now", size = 13f, color = pb.signal)
    }
}

@Composable
fun PulsingDot(color: Color, size: androidx.compose.ui.unit.Dp = 10.dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val phase by transition.animateFloat(1f, 0.45f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "phase")
    Dot(color, size, Modifier.alpha(phase).scale(0.82f + 0.18f * phase))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HoursCard(snapshot: TrackerSnapshot) {
    val pb = LocalPb.current
    val period = snapshot.period
    val delta = period.actualHours - period.estimatedHours
    val barTotal = maxOf(period.actualHours, period.estimatedHours, 1.0)
    val scheduledShare = (minOf(period.actualHours, period.estimatedHours) / barTotal).toFloat()
    val overShare = (maxOf(0.0, period.actualHours - period.estimatedHours) / barTotal).toFloat()

    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stamp("02 · Hours")
            MonoText(dev.quackbyte.punchboard.model.periodLabel(snapshot.viewMonth, snapshot.tracker.payslipStartDay), size = 11f, color = pb.mutedForeground)
        }
        Spacer(Modifier.height(14.dp))
        FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(10.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration(period.actualHours), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, color = pb.foreground)
            MonoText("of ${formatDuration(period.estimatedHours)}", size = 14f, color = pb.mutedForeground)
            if (abs(delta) >= 1.0 / 60) DeltaPill(delta)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)).background(pb.muted)) {
            if (scheduledShare > 0) Box(Modifier.weight(scheduledShare).fillMaxHeight().background(pb.primary))
            if (overShare > 0) Box(Modifier.weight(overShare).fillMaxHeight().padding(start = 2.dp).background(pb.extra))
            val rest = 1f - scheduledShare - overShare
            if (rest > 0.0001f) Spacer(Modifier.weight(rest))
        }
        snapshot.toDate?.let { toDate ->
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MonoText("To date", size = 12f)
                MonoText(formatDuration(toDate.logged), size = 12f)
                MonoText("of ${formatDuration(toDate.scheduled)} scheduled", size = 12f, color = pb.mutedForeground)
                MonoText("· ${toDate.daysElapsed} of ${period.workingDaysCount} workdays in", size = 12f, color = pb.mutedForeground)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniStat("Workdays", period.workingDaysCount.toString(), pb.primary, Modifier.weight(1f))
            MiniStat("Vacation", period.vacationDays.toString(), pb.vacation, Modifier.weight(1f))
            MiniStat("Sick", period.sickDays.toString(), pb.sick, Modifier.weight(1f))
            MiniStat("Extra", formatDuration(period.extraHoursTotal), pb.extra, Modifier.weight(1f))
        }
    }
}

@Composable
fun DeltaPill(delta: Double, small: Boolean = false) {
    val pb = LocalPb.current
    val positive = delta > 0
    Text(
        formatSignedDuration(delta),
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (positive) pb.primary.copy(alpha = 0.1f) else pb.vacation.copy(alpha = 0.15f))
            .padding(horizontal = if (small) 6.dp else 9.dp, vertical = if (small) 1.dp else 3.dp),
        fontFamily = Mono,
        fontSize = if (small) 9.5.sp else 12.sp,
        fontWeight = FontWeight.Medium,
        color = if (positive) pb.primary else pb.vacationText,
    )
}

@Composable
private fun MiniStat(label: String, value: String, dot: Color, modifier: Modifier = Modifier) {
    val pb = LocalPb.current
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .dashedBorder(pb.input, 12.dp)
            .padding(horizontal = 9.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(dot, 6.dp)
            Spacer(Modifier.width(5.dp))
            Text(label, style = PbType.bodySmall.copy(fontSize = 11.sp), color = pb.mutedForeground, maxLines = 1)
        }
        Spacer(Modifier.height(2.dp))
        Text(value, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = pb.foreground, maxLines = 1)
    }
}

fun Modifier.dashedBorder(color: Color, radius: androidx.compose.ui.unit.Dp): Modifier = drawBehind {
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
        width = 1.dp.toPx(),
        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
    )
    drawRoundRect(color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()), style = stroke)
}
