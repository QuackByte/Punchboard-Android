package dev.quackbyte.punchboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.quackbyte.punchboard.model.Clock
import dev.quackbyte.punchboard.model.DateText
import dev.quackbyte.punchboard.model.DayException
import dev.quackbyte.punchboard.model.DayInput
import dev.quackbyte.punchboard.model.DayKeys
import dev.quackbyte.punchboard.model.TimeEntry
import dev.quackbyte.punchboard.model.entryMinutes
import dev.quackbyte.punchboard.model.formatDuration
import dev.quackbyte.punchboard.model.isWorkday
import dev.quackbyte.punchboard.model.parseDayInput
import dev.quackbyte.punchboard.model.sumEntriesHours
import dev.quackbyte.punchboard.ui.TrackerSnapshot
import dev.quackbyte.punchboard.ui.components.Dot
import dev.quackbyte.punchboard.ui.components.Lucide
import dev.quackbyte.punchboard.ui.components.MonoText
import dev.quackbyte.punchboard.ui.components.Stamp
import dev.quackbyte.punchboard.ui.components.TextAction
import dev.quackbyte.punchboard.ui.theme.LocalPb
import dev.quackbyte.punchboard.ui.theme.Mono
import dev.quackbyte.punchboard.ui.theme.PbType
import dev.quackbyte.punchboard.ui.theme.Sans
import java.time.LocalDate

data class DayEditorActions(
    val applyText: (LocalDate, String) -> Boolean,
    val setEntries: (LocalDate, List<TimeEntry>) -> Unit,
    val removeEntry: (LocalDate, Int, TimeEntry) -> Unit,
    val reset: (LocalDate, Boolean) -> Unit,
    val setException: (LocalDate, DayException?, DayException?) -> Unit,
)

@Composable
fun DayEditorSheet(
    snapshot: TrackerSnapshot,
    date: LocalDate,
    actions: DayEditorActions,
    onDismiss: () -> Unit,
    undoMessage: String? = null,
    onUndo: () -> Unit = {},
) {
    val pb = LocalPb.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = pb.card,
        contentColor = pb.foreground,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(40.dp, 4.dp).clip(RoundedCornerShape(9.dp)).background(pb.input))
        },
    ) {
        if (undoMessage != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(pb.ink)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(undoMessage, Modifier.weight(1f).padding(vertical = 12.dp), style = PbType.bodySmall, color = pb.inkForeground)
                TextAction("Undo", onUndo, color = pb.brand)
            }
        }
        DayEditor(snapshot, date, actions)
    }
}

@Composable
private fun DayEditor(snapshot: TrackerSnapshot, date: LocalDate, actions: DayEditorActions) {
    val pb = LocalPb.current
    val now = rememberNow(15_000)
    val nowMinutes = now.hour * 60 + now.minute
    val key = DayKeys.of(date)
    val month = snapshot.monthData
    val isWorkingDay = month.isWorkday(date)
    val isToday = date == snapshot.today
    val exception = if (isWorkingDay) DayException.fromWire(month.exceptions[key]) else null
    val entries = snapshot.timeEntries[key].orEmpty()
    val hasOverride = if (isWorkingDay) month.dailyHours.containsKey(key) || entries.isNotEmpty() else (month.extraHours[key] ?: 0.0) > 0 || entries.isNotEmpty()
    val (total, breakMinutes) = liveDayHours(snapshot, date, nowMinutes)
    var text by remember(key) { mutableStateOf("") }
    var invalid by remember(key) { mutableStateOf(false) }
    var editingTime by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(DateText.weekday(date), fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = pb.foreground)
                MonoText(DateText.long(date), size = 13f, color = pb.mutedForeground)
            }
            StatusPill(exception, isWorkingDay)
        }

        if (exception != DayException.Sick) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Stamp("Quick entry")
                val preview = text.takeIf { it.isNotBlank() }?.let(::parseDayInput)
                val previewLabel = when {
                    text.isBlank() -> null
                    preview == null -> "Couldn't read that — try 9-17:30 or 7.5"
                    preview is DayInput.Sessions -> "${preview.entries.size} session${if (preview.entries.size == 1) "" else "s"} · ${formatDuration(sumEntriesHours(preview.entries))}"
                    preview is DayInput.Total -> "${formatDuration(preview.hours)} total"
                    else -> null
                }
                val submit = {
                    if (text.isNotBlank()) {
                        if (actions.applyText(date, text)) {
                            text = ""
                            invalid = false
                        } else {
                            invalid = true
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(pb.background.copy(alpha = 0.6f))
                        .border(if (invalid) 2.dp else 1.dp, if (invalid) pb.destructive else pb.input, RoundedCornerShape(16.dp))
                        .padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (text.isEmpty()) MonoText("9-13, 14-18:30  or  7.5", size = 15f, color = pb.mutedForeground.copy(alpha = 0.6f))
                        BasicTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                invalid = false
                            },
                            singleLine = true,
                            textStyle = TextStyle(fontFamily = Mono, fontSize = 15.sp, color = pb.foreground),
                            cursorBrush = SolidColor(pb.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.None),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(pb.primary)
                            .clickable(role = Role.Button, onClickLabel = "Apply quick entry") { submit() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.ArrowRight, "Apply quick entry", Modifier.size(18.dp), tint = pb.onPrimary)
                    }
                }
                MonoText(
                    previewLabel?.let { "→ $it" } ?: "Done to save · replaces this day",
                    size = 12f,
                    color = if (preview == null && text.isNotBlank()) pb.destructive else pb.mutedForeground,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Stamp("Sessions")
                    Row {
                        MonoText(formatDuration(total), size = 14f, weight = FontWeight.SemiBold)
                        if (breakMinutes > 0) MonoText(" · −${formatDuration(breakMinutes / 60.0)} break", size = 12f, color = pb.mutedForeground)
                        if (!hasOverride && isWorkingDay && exception == null) MonoText(" · scheduled", size = 12f, color = pb.mutedForeground)
                    }
                }
                if (entries.isEmpty()) {
                    Text(
                        "No clocked sessions" + when {
                            isWorkingDay && exception == null && hasOverride -> " — a total is set manually."
                            isWorkingDay && exception == null -> " — counting the scheduled ${formatDuration(month.hoursPerDay)}."
                            else -> "."
                        },
                        style = PbType.bodySmall,
                        color = pb.mutedForeground,
                    )
                }
                entries.forEachIndexed { index, entry ->
                    SessionRow(
                        entry = entry,
                        isToday = isToday,
                        nowMinutes = nowMinutes,
                        onEditStart = { editingTime = index to true },
                        onEditEnd = { editingTime = index to false },
                        onRemove = { actions.removeEntry(date, index, entry) },
                        onNote = { note ->
                            actions.setEntries(date, entries.mapIndexed { i, e -> if (i == index) e.copy(note = note.ifBlank { null }) else e })
                        },
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextAction("Add session", {
                        actions.setEntries(date, entries + newSession(entries, isToday, nowMinutes, month.hoursPerDay))
                    }, icon = Lucide.Plus)
                    if (hasOverride) {
                        TextAction(
                            if (isWorkingDay) "Reset to schedule" else "Clear",
                            { actions.reset(date, isWorkingDay) },
                            icon = Lucide.RotateCcw,
                            color = pb.mutedForeground,
                        )
                    }
                }
            }
        }

        if (isWorkingDay) {
            HorizontalDivider(color = pb.border)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Stamp("Mark day")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MarkButton("Vacation", pb.vacation, exception == DayException.Vacation, Modifier.weight(1f)) {
                        actions.setException(date, DayException.Vacation, exception)
                    }
                    MarkButton("Sick", pb.sick, exception == DayException.Sick, Modifier.weight(1f)) {
                        actions.setException(date, DayException.Sick, exception)
                    }
                }
            }
        }
    }

    editingTime?.let { (index, isStart) ->
        val entry = entries.getOrNull(index)
        if (entry == null) {
            editingTime = null
        } else {
            val initial = Clock.parse(if (isStart) entry.start else entry.end ?: Clock.format(nowMinutes)) ?: nowMinutes
            TimeDialog(
                title = if (isStart) "Start" else "End",
                initialMinutes = initial,
                onDismiss = { editingTime = null },
                onConfirm = { minutes ->
                    val value = Clock.format(minutes)
                    actions.setEntries(date, entries.mapIndexed { i, e -> if (i == index) (if (isStart) e.copy(start = value) else e.copy(end = value)) else e })
                    editingTime = null
                },
            )
        }
    }
}

/** A new session after the last one, or now on an empty today, like the desktop. */
private fun newSession(entries: List<TimeEntry>, isToday: Boolean, nowMinutes: Int, hoursPerDay: Double): TimeEntry {
    val lastEnd = entries.lastOrNull()?.end?.let(Clock::parse)
    val start = when {
        lastEnd != null -> minOf(lastEnd + 60, 23 * 60)
        isToday && entries.isEmpty() -> nowMinutes
        else -> 9 * 60
    }
    val length = if (entries.isEmpty()) (maxOf(1.0, hoursPerDay) * 60).toInt() else 4 * 60
    return TimeEntry(Clock.format(start), Clock.format(minOf(start + length, 23 * 60 + 59)))
}

@Composable
private fun StatusPill(exception: DayException?, isWorkingDay: Boolean) {
    val pb = LocalPb.current
    val (label, color) = when {
        exception == DayException.Vacation -> "Vacation" to pb.vacationText
        exception == DayException.Sick -> "Sick" to pb.sickText
        isWorkingDay -> "Workday" to pb.primary
        else -> "Day off" to pb.mutedForeground
    }
    Text(
        label.uppercase(),
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = if (isWorkingDay || exception != null) 0.1f else 0f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = PbType.stamp.copy(fontSize = 11.sp),
        color = color,
    )
}

@Composable
private fun SessionRow(
    entry: TimeEntry,
    isToday: Boolean,
    nowMinutes: Int,
    onEditStart: () -> Unit,
    onEditEnd: () -> Unit,
    onRemove: () -> Unit,
    onNote: (String) -> Unit,
) {
    val pb = LocalPb.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(pb.background.copy(alpha = 0.6f))
            .border(1.dp, pb.border, RoundedCornerShape(16.dp))
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeChip(entry.start, false, "Start", onEditStart)
            Icon(Lucide.ArrowRight, null, Modifier.padding(horizontal = 4.dp).size(14.dp), tint = pb.mutedForeground)
            if (entry.end == null && isToday) {
                Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PulsingDot(pb.signal, 7.dp)
                    Spacer(Modifier.width(6.dp))
                    MonoText("now", size = 18f, color = pb.signal)
                }
            } else {
                TimeChip(entry.end ?: "--:--", entry.end == null, "End", onEditEnd)
            }
            Spacer(Modifier.weight(1f))
            MonoText(formatDuration(entryMinutes(entry, if (isToday) nowMinutes else null) / 60.0), size = 12f, color = pb.mutedForeground)
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Remove session", onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.X, "Remove session", Modifier.size(16.dp), tint = pb.mutedForeground)
            }
        }
        NoteField(entry.note.orEmpty(), onNote)
    }
}

@Composable
private fun TimeChip(value: String, invalid: Boolean, label: String, onClick: () -> Unit) {
    val pb = LocalPb.current
    Text(
        value,
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClickLabel = "Change $label time", onClick = onClick)
            .then(if (invalid) Modifier.border(1.dp, pb.destructive, RoundedCornerShape(10.dp)) else Modifier)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        fontFamily = Mono,
        fontSize = 19.sp,
        color = pb.foreground,
    )
}

/** A per-session note, saved when the field loses focus or on Done. */
@Composable
private fun NoteField(value: String, onCommit: (String) -> Unit) {
    val pb = LocalPb.current
    var draft by remember(value) { mutableStateOf(value) }
    val commit = { if (draft.trim() != value) onCommit(draft.trim()) }
    Row(Modifier.padding(start = 10.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Lucide.StickyNote, null, Modifier.size(13.dp), tint = pb.mutedForeground.copy(alpha = 0.6f))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (draft.isEmpty()) Text("Add a note", style = PbType.bodySmall, color = pb.mutedForeground.copy(alpha = 0.55f))
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = PbType.bodySmall.copy(color = pb.foreground),
                cursorBrush = SolidColor(pb.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commit() },
            )
        }
    }
}

@Composable
private fun MarkButton(label: String, color: Color, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val pb = LocalPb.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .height(52.dp)
            .clip(shape)
            .background(if (active) color.copy(alpha = 0.15f) else pb.background.copy(alpha = 0.6f))
            .border(1.dp, if (active) color.copy(alpha = 0.6f) else pb.border, shape)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(color)
        Spacer(Modifier.width(10.dp))
        Text(if (active) "Unmark ${label.lowercase()}" else label, style = PbType.body.copy(fontSize = 16.sp), color = pb.foreground)
    }
}

@Composable
fun TimeDialog(title: String, initialMinutes: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val pb = LocalPb.current
    val state = rememberTimePickerState(initialHour = initialMinutes / 60, initialMinute = initialMinutes % 60, is24Hour = true)
    LaunchedEffect(Unit) { }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = pb.popover,
        title = { Text(title, fontFamily = Sans, fontWeight = FontWeight.Bold) },
        text = {
            TimePicker(
                state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = pb.muted,
                    selectorColor = pb.primary,
                    timeSelectorSelectedContainerColor = pb.primary.copy(alpha = 0.18f),
                    timeSelectorUnselectedContainerColor = pb.muted,
                    timeSelectorSelectedContentColor = pb.foreground,
                    timeSelectorUnselectedContentColor = pb.foreground,
                ),
            )
        },
        confirmButton = { TextButton({ onConfirm(state.hour * 60 + state.minute) }) { Text("Set") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
