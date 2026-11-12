package dev.quackbyte.punchboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.quackbyte.punchboard.model.js
import dev.quackbyte.punchboard.ui.theme.LocalPb
import dev.quackbyte.punchboard.ui.theme.Mono
import dev.quackbyte.punchboard.ui.theme.PbType

private val FieldShape = RoundedCornerShape(12.dp)

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = PbType.label.copy(fontSize = 13.sp), color = LocalPb.current.foreground)
}

/**
 * A number input that keeps what's typed ("7.", "") while it isn't a
 * number yet, and only reports values that are.
 */
@Composable
fun NumberField(
    label: String,
    value: Double,
    onValue: (Double) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    decimals: Boolean = true,
) {
    val pb = LocalPb.current
    var text by remember { mutableStateOf(value.js()) }
    LaunchedEffect(value) {
        if (text.replace(',', '.').toDoubleOrNull() != value) text = value.js()
    }
    Column(modifier) {
        FieldLabel(label)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(FieldShape)
                .background(pb.background.copy(alpha = 0.6f))
                .border(1.dp, pb.input, FieldShape)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { next ->
                    text = next
                    next.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }?.let(onValue)
                },
                singleLine = true,
                textStyle = TextStyle(fontFamily = Mono, fontSize = 15.sp, color = pb.foreground),
                cursorBrush = SolidColor(pb.primary),
                keyboardOptions = KeyboardOptions(keyboardType = if (decimals) KeyboardType.Decimal else KeyboardType.Number),
                modifier = Modifier.weight(1f).semantics { contentDescription = label },
            )
            if (suffix != null) Text(suffix, style = PbType.bodySmall, color = pb.mutedForeground)
        }
    }
}

/** A dropdown that looks like the other fields. */
@Composable
fun <T> SelectField(
    label: String?,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pb = LocalPb.current
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        if (label != null) {
            FieldLabel(label)
            Spacer(Modifier.height(6.dp))
        }
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(FieldShape)
                    .background(pb.background.copy(alpha = 0.6f))
                    .border(1.dp, pb.input, FieldShape)
                    .clickable(role = Role.DropdownList, onClickLabel = label) { open = true }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(options.firstOrNull { it.first == selected }?.second.orEmpty(), Modifier.weight(1f), style = PbType.body, color = pb.foreground)
                Icon(Lucide.ChevronDown, null, Modifier.size(16.dp), tint = pb.mutedForeground)
            }
            DropdownMenu(open, { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = {
                        open = false
                        onSelect(value)
                    })
                }
            }
        }
    }
}

/** A row that opens a picker, showing its value in mono. */
@Composable
fun ValueRow(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier, description: String? = null) {
    val pb = LocalPb.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = PbType.label, color = pb.foreground)
            if (description != null) Text(description, style = PbType.bodySmall.copy(fontSize = 12.5.sp), color = pb.mutedForeground)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(pb.muted)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            fontFamily = Mono,
            fontSize = 14.sp,
            color = pb.foreground,
        )
    }
}
