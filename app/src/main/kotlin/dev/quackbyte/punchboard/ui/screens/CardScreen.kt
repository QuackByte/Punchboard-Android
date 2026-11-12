package dev.quackbyte.punchboard.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.quackbyte.punchboard.model.Clock
import dev.quackbyte.punchboard.model.DateText
import dev.quackbyte.punchboard.model.DayException
import dev.quackbyte.punchboard.model.DayKeys
import dev.quackbyte.punchboard.model.TimeEntry
import dev.quackbyte.punchboard.model.formatDuration
import dev.quackbyte.punchboard.model.isWorkday
import dev.quackbyte.punchboard.model.isoWeek
import dev.quackbyte.punchboard.model.mondayOf
import dev.quackbyte.punchboard.model.workedTime
import dev.quackbyte.punchboard.ui.TrackerSnapshot
import dev.quackbyte.punchboard.ui.components.Dot
import dev.quackbyte.punchboard.ui.components.MonoText
import dev.quackbyte.punchboard.ui.components.Panel
import dev.quackbyte.punchboard.ui.components.Stamp
import dev.quackbyte.punchboard.ui.components.hatched
import dev.quackbyte.punchboard.ui.theme.LocalPb
import dev.quackbyte.punchboard.ui.theme.Mono
import dev.quackbyte.punchboard.ui.theme.PbType
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/** Visible window of the day for the mini timeline in each cell. */
private const val TIMELINE_START = 6 * 60
private const val TIMELINE_END = 22 * 60

/** A day's hours with the time rules applied, plus a running session so far. */
fun liveDayHours(snapshot: TrackerSnapshot, date: LocalDate, nowMinutes: Int): Pair<Double, Int> {
    val key = DayKeys.of(date)
    val entries = snapshot.timeEntries[key].orEmpty()
    val stored = if (entries.isNotEmpty()) workedTime(entries, snapshot.rules) else null
    val live = if (stored != null && date == snapshot.today && entries.any { it.isRunning }) {
        workedTime(entries, snapshot.rules, nowMinutes)
    } else {
        stored
    }
    val base = snapshot.period.byKey[key]?.hours ?: 0.0
    val hours = base + if (stored != null && live != null) (live.workedMinutes - stored.workedMinutes) / 60.0 else 0.0
    return hours to (live?.breakMinutes ?: 0)
}

data class DayMenuActions(
    val onLog: (LocalDate) -> Unit,
    val onException: (LocalDate, DayException?, DayException?) -> Unit,
    val onReset: (LocalDate, Boolean) -> Unit,
)

@Composable
fun CardScreen(snapshot: TrackerSnapshot, actions: DayMenuActions) {
    val now = rememberNow(30_000)
    val nowMinutes = now.hour * 60 + now.minute
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel(Modifier.fillMaxWidth(), padding = 12.dp) {
            CalendarHeader()
            Spacer(Modifier.height(6.dp))
            snapshot.cells.chunked(7).forEachIndexed { weekIndex, week ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    week.forEachIndexed { index, date ->
                        Box(Modifier.weight(1f)) {
                            if (date != null) {
                                DayCell(snapshot, date, isFirstCell = weekIndex == 0 && index == snapshot.cells.indexOfFirst { it != null }, nowMinutes, actions)
                            } else {
                                Spacer(Modifier.height(66.dp))
                            }
                        }
                    }
                }
            }
            Legend()
        }
        WeekStrip(snapshot, nowMinutes)
        Text(
            "Tap a day to log times · long-press a workday to mark it",
            Modifier.padding(horizontal = 4.dp),
            style = PbType.bodySmall.copy(fontSize = 12.sp),
            color = LocalPb.current.mutedForeground,
        )
    }
}

@Composable
private fun CalendarHeader() {
    val pb = LocalPb.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { index, day ->
            Stamp(day, Modifier.weight(1f).padding(start = 3.dp), color = if (index >= 5) pb.mutedForeground.copy(alpha = 0.6f) else pb.mutedForeground)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayCell(snapshot: TrackerSnapshot, date: LocalDate, isFirstCell: Boolean, nowMinutes: Int, actions: DayMenuActions) {
    val pb = LocalPb.current
    val haptics = LocalHapticFeedback.current
    val key = DayKeys.of(date)
    val month = snapshot.monthData
    val isWorkingDay = month.isWorkday(date)
    val isToday = date == snapshot.today
    val exception = if (isWorkingDay) DayException.fromWire(month.exceptions[key]) else null
    val entries = snapshot.timeEntries[key].orEmpty()
    val hasEntries = entries.isNotEmpty()
    val isRunning = entries.any { it.isRunning }
    val hasOverride = if (isWorkingDay) month.dailyHours.containsKey(key) || hasEntries else (month.extraHours[key] ?: 0.0) > 0 || hasEntries
    val (total, _) = liveDayHours(snapshot, date, nowMinutes)
    val notes = entries.mapNotNull { it.note?.trim()?.takeIf(String::isNotEmpty) }.joinToString(" · ")
    val scheduledOnly = isWorkingDay && !hasOverride && exception == null
    val isPast = date < snapshot.today
    val showMonthTag = date.dayOfMonth == 1 || isFirstCell
    var menuOpen by remember { mutableStateOf(false) }

    val shape = RoundedCornerShape(11.dp)
    val base = Modifier
        .fillMaxWidth()
        .height(66.dp)
        .let {
            when {
                exception == DayException.Vacation -> it.hatched(pb.vacation, shape).border(1.dp, pb.vacation.copy(alpha = 0.45f), shape)
                exception == DayException.Sick -> it.hatched(pb.sick, shape).border(1.dp, pb.sick.copy(alpha = 0.45f), shape)
                isWorkingDay -> it.clip(shape).background(pb.card).border(1.dp, pb.border, shape)
                hasOverride -> it.clip(shape).background(pb.extra.copy(alpha = 0.08f)).border(1.dp, pb.extra.copy(alpha = 0.45f), shape)
                else -> it.clip(shape).dashedCellBorder(pb.border)
            }
        }
        .let { if (isToday) it.border(2.dp, pb.primary, shape) else it }

    Box {
        Column(
            base
                .combinedClickable(
                    onClickLabel = "Log times",
                    onLongClickLabel = if (isWorkingDay) "Mark day" else null,
                    onClick = { actions.onLog(date) },
                    onLongClick = if (isWorkingDay) {
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        }
                    } else {
                        null
                    },
                )
                .semantics {
                    contentDescription = buildString {
                        append(DateText.weekdayDayMonth(date))
                        append(", ").append(formatDuration(total))
                        exception?.let { append(", ").append(it.wire) }
                        if (notes.isNotEmpty()) append(", ").append(notes)
                    }
                }
                .padding(horizontal = 5.dp, vertical = 6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    date.dayOfMonth.toString(),
                    Modifier.let { if (isToday) it.offset(x = (-2).dp, y = (-2).dp).clip(RoundedCornerShape(6.dp)).background(pb.primary).padding(horizontal = 4.dp, vertical = 2.dp) else it },
                    fontFamily = Mono,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    lineHeight = 12.sp,
                    color = when {
                        isToday -> pb.onPrimary
                        !isWorkingDay && !hasOverride -> pb.mutedForeground
                        else -> pb.foreground
                    },
                )
                Spacer(Modifier.weight(1f))
                when {
                    exception != null -> Text(
                        if (exception == DayException.Vacation) "VAC" else "SICK",
                        fontFamily = Mono,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 7.5.sp,
                        lineHeight = 8.sp,
                        color = if (exception == DayException.Vacation) pb.vacationText else pb.sickText,
                    )
                    isRunning -> PulsingDot(pb.signal, 7.dp)
                    notes.isNotEmpty() -> Dot(pb.foreground.copy(alpha = 0.3f), 5.dp)
                    showMonthTag -> Text(
                        DateText.monthShort(YearMonth.from(date)).uppercase(),
                        fontFamily = Mono,
                        fontSize = 7.5.sp,
                        lineHeight = 8.sp,
                        color = pb.mutedForeground,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (exception != DayException.Sick && (total > 0 || isWorkingDay)) {
                Text(
                    (if (!isWorkingDay && hasOverride) "+" else "") + compactDuration(total),
                    fontFamily = Mono,
                    fontWeight = if (scheduledOnly) FontWeight.Medium else FontWeight.SemiBold,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    color = when {
                        scheduledOnly -> pb.mutedForeground.copy(alpha = if (isPast) 0.8f else 0.55f)
                        !isWorkingDay && hasOverride -> pb.extraText
                        else -> pb.foreground
                    },
                )
            }
            Spacer(Modifier.height(5.dp))
            if (hasEntries) {
                Timeline(entries, if (isToday) nowMinutes else null)
            } else if (scheduledOnly) {
                DashedLine(pb.mutedForeground.copy(alpha = 0.3f))
            } else {
                Spacer(Modifier.height(3.dp))
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("Log times…") }, onClick = {
                menuOpen = false
                actions.onLog(date)
            })
            HorizontalDivider()
            DropdownMenuItem(
                leadingIcon = { Dot(pb.vacation, 7.dp) },
                text = { Text(if (exception == DayException.Vacation) "Unmark vacation" else "Mark vacation") },
                onClick = {
                    menuOpen = false
                    actions.onException(date, DayException.Vacation, exception)
                },
            )
            DropdownMenuItem(
                leadingIcon = { Dot(pb.sick, 7.dp) },
                text = { Text(if (exception == DayException.Sick) "Unmark sick" else "Mark sick") },
                onClick = {
                    menuOpen = false
                    actions.onException(date, DayException.Sick, exception)
                },
            )
            if (hasOverride) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Reset to schedule") }, onClick = {
                    menuOpen = false
                    actions.onReset(date, true)
                })
            }
        }
    }
}

/** "8h01" in a narrow cell; "8h" and "45m" as usual. */
private fun compactDuration(hours: Double): String = formatDuration(hours).replace(Regex("^(\\d+)h (\\d+)m$"), "$1h$2")

private fun Modifier.dashedCellBorder(color: Color) = drawBehind {
    drawRoundRect(
        color,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(11.dp.toPx()),
        style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}

@Composable
private fun DashedLine(color: Color) {
    Box(
        Modifier.fillMaxWidth().height(3.dp).drawBehind {
            drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
        },
    )
}

@Composable
private fun Timeline(entries: List<TimeEntry>, nowMinutes: Int?) {
    val pb = LocalPb.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(99.dp)).background(pb.foreground.copy(alpha = 0.07f))) {
        val span = (TIMELINE_END - TIMELINE_START).toFloat()
        fun pct(minutes: Int) = (minutes.coerceIn(TIMELINE_START, TIMELINE_END) - TIMELINE_START) / span
        entries.forEach { entry ->
            val start = Clock.parse(entry.start) ?: return@forEach
            var end = entry.end?.let(Clock::parse) ?: nowMinutes ?: return@forEach
            if (end < start) end = TIMELINE_END
            val left = pct(start)
            val width = maxOf(pct(end) - left, 0.02f)
            Box(
                Modifier
                    .offset(x = maxWidth * left)
                    .width(maxWidth * width)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(99.dp))
                    .background(if (entry.isRunning) pb.signal else pb.primary),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend() {
    val pb = LocalPb.current
    Spacer(Modifier.height(10.dp))
    HorizontalDivider(color = pb.border)
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
        LegendItem({ MonoText("8h", size = 11f, weight = FontWeight.SemiBold) }, "Logged")
        LegendItem({ MonoText("8h", size = 11f, color = pb.mutedForeground.copy(alpha = 0.6f)) }, "Scheduled")
        LegendItem({ Box(Modifier.width(16.dp).height(3.dp).clip(RoundedCornerShape(99.dp)).background(pb.primary)) }, "Sessions 6–22")
        LegendItem({ Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(pb.vacation.copy(alpha = 0.6f))) }, "Vacation")
        LegendItem({ Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(pb.sick.copy(alpha = 0.6f))) }, "Sick")
        LegendItem({ Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(pb.extra.copy(alpha = 0.6f))) }, "Extra")
    }
}

@Composable
private fun LegendItem(swatch: @Composable () -> Unit, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        swatch()
        Spacer(Modifier.width(5.dp))
        Text(label, style = PbType.bodySmall.copy(fontSize = 11.sp), color = LocalPb.current.mutedForeground)
    }
}

@Composable
private fun WeekStrip(snapshot: TrackerSnapshot, nowMinutes: Int) {
    val pb = LocalPb.current
    val weeks = snapshot.cells.chunked(7).mapNotNull { week ->
        val first = week.firstOrNull { it != null } ?: return@mapNotNull null
        val total = week.filterNotNull().sumOf { liveDayHours(snapshot, it, nowMinutes).first }
        val scheduled = week.filterNotNull().count { snapshot.monthData.isWorkday(it) } * snapshot.monthData.hoursPerDay
        val current = week.any { it == snapshot.today }
        Triple(first, Triple(total, scheduled, current), snapshot.weekBalances[mondayOf(first)])
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Stamp("Weeks")
            val carried = snapshot.weekBalances[mondayOf(snapshot.today)]
            if (carried != null && abs(carried) >= 1.0 / 60) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Balance ", style = PbType.bodySmall.copy(fontSize = 12.sp), color = pb.mutedForeground)
                    DeltaPill(carried, small = true)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            weeks.forEach { (first, totals, balance) ->
                val (total, scheduled, current) = totals
                Column(
                    Modifier
                        .width(84.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(pb.card)
                        .background(if (current) pb.primary.copy(alpha = 0.08f) else pb.card)
                        .border(1.dp, if (current) pb.primary else pb.border, RoundedCornerShape(14.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    MonoText("W${isoWeek(first)}", size = 10f, color = pb.mutedForeground)
                    Text(formatDuration(total), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = pb.foreground, maxLines = 1)
                    if (scheduled > 0) {
                        MonoText("/${formatDuration(scheduled)}", size = 10f, color = if (total >= scheduled) pb.primary else pb.mutedForeground)
                    }
                    if (balance != null && abs(balance) >= 1.0 / 60) DeltaPill(balance, small = true)
                }
            }
        }
    }
}
