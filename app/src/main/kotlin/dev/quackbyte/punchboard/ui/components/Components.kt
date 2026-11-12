package dev.quackbyte.punchboard.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.quackbyte.punchboard.ui.theme.LocalPb
import dev.quackbyte.punchboard.ui.theme.Mono
import dev.quackbyte.punchboard.ui.theme.PbType
import dev.quackbyte.punchboard.ui.theme.Sans

val PanelShape = RoundedCornerShape(22.dp)

/** The dotted desk the cards sit on. */
fun Modifier.dotGrid(background: Color, dot: Color): Modifier = drawBehind {
    drawRect(background)
    val step = 22.dp.toPx()
    val radius = 1.dp.toPx()
    var y = step / 2
    while (y < size.height) {
        var x = step / 2
        while (x < size.width) {
            drawCircle(dot, radius, Offset(x, y))
            x += step
        }
        y += step
    }
}

/** A card on the desk, as the desktop's `panel`. */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    val pb = LocalPb.current
    Column(
        modifier
            .shadow(if (pb.isDark) 0.dp else 10.dp, PanelShape, ambientColor = pb.foreground.copy(alpha = 0.18f), spotColor = pb.foreground.copy(alpha = 0.18f))
            .clip(PanelShape)
            .background(pb.card)
            .border(1.dp, pb.border, PanelShape)
            .padding(padding),
        content = content,
    )
}

/** Small mono uppercase label — the "stamp" on a time card. */
@Composable
fun Stamp(text: String, modifier: Modifier = Modifier, color: Color = LocalPb.current.mutedForeground) {
    Text(text.uppercase(), modifier, style = PbType.stamp, color = color)
}

/** A label, dotted leader and value: a receipt line. */
@Composable
fun LeaderRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    labelColor: Color = LocalPb.current.mutedForeground,
    valueColor: Color = LocalPb.current.foreground,
    trailing: String? = null,
    style: TextStyle = PbType.mono,
) {
    val pb = LocalPb.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(label, style = style, color = labelColor)
        DottedLeader(Modifier.weight(1f).padding(horizontal = 8.dp).padding(bottom = 4.dp))
        Text(value, style = style, color = valueColor)
        if (trailing != null) {
            Text(
                trailing,
                Modifier.width(92.dp).padding(start = 10.dp),
                style = style.copy(fontSize = 11.5.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End),
                color = pb.mutedForeground,
            )
        }
    }
}

@Composable
fun DottedLeader(modifier: Modifier = Modifier) {
    val color = LocalPb.current.mutedForeground.copy(alpha = 0.4f)
    Box(
        modifier.height(1.dp).drawBehind {
            drawLine(
                color,
                Offset(0f, 0f),
                Offset(size.width, 0f),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.dp.toPx())),
            )
        },
    )
}

/** Tear-off perforation across a card. */
@Composable
fun Perforation(modifier: Modifier = Modifier) {
    val pb = LocalPb.current
    Box(
        modifier.fillMaxWidth().height(2.dp).drawBehind {
            drawLine(
                pb.border,
                Offset(0f, 0f),
                Offset(size.width, 0f),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
            )
        },
    )
}

/** A bordered square icon button, 44dp so it's easy to hit. */
@Composable
fun SquareIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalPb.current.foreground,
    background: Color = LocalPb.current.card,
    border: Color = LocalPb.current.border,
) {
    Box(
        modifier
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(18.dp), tint = tint)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** A big full-width button, like the punch clock's. */
@Composable
fun BigButton(
    text: String,
    icon: ImageVector?,
    background: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    glow: Color? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(if (glow != null) Modifier.shadow(18.dp, shape, ambientColor = glow, spotColor = glow) else Modifier)
            .clip(shape)
            .background(background)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = content)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = content, fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

/** A text button in the brand colour or muted. */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = LocalPb.current.primary,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(17.dp), tint = color.copy(alpha = if (enabled) 1f else 0.4f))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = color.copy(alpha = if (enabled) 1f else 0.4f), fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

/** A setting with a title, a description and a switch; the whole row toggles. */
@Composable
fun SwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val pb = LocalPb.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = PbType.label, color = pb.foreground.copy(alpha = if (enabled) 1f else 0.5f))
            if (description != null) {
                Text(description, style = PbType.bodySmall.copy(fontSize = 12.5.sp), color = pb.mutedForeground)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = pb.primary,
                checkedThumbColor = pb.onPrimary,
                uncheckedTrackColor = pb.muted,
                uncheckedBorderColor = pb.input,
                uncheckedThumbColor = pb.mutedForeground,
            ),
        )
    }
}

/** Hatched fill for vacation and sick days, as on the desktop's time card. */
fun Modifier.hatched(color: Color, shape: Shape): Modifier = clip(shape).drawBehind {
    drawRect(color.copy(alpha = 0.08f))
    val step = 12.dp.toPx()
    val width = 6.dp.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(
            color.copy(alpha = 0.16f),
            Offset(x, size.height),
            Offset(x + size.height, 0f),
            strokeWidth = width,
        )
        x += step
    }
}

/** Mono text with tabular figures. */
@Composable
fun MonoText(text: String, modifier: Modifier = Modifier, size: Float = 13f, color: Color = LocalPb.current.foreground, weight: FontWeight = FontWeight.Normal) {
    Text(text, modifier, fontFamily = Mono, fontSize = size.sp, color = color, fontWeight = weight)
}

/** Rows laid out as a segmented control of choices. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val pb = LocalPb.current
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(pb.muted)
            .border(1.dp, pb.border, RoundedCornerShape(14.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (isSelected) pb.card else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = PbType.label.copy(fontSize = 13.sp), color = if (isSelected) pb.foreground else pb.mutedForeground)
            }
        }
    }
}

/** A row-scope helper for equal-width children. */
@Composable
fun RowScope.Cell(content: @Composable () -> Unit) {
    Box(Modifier.weight(1f)) { content() }
}

val InkGlow: (Color) -> Brush = { color ->
    Brush.radialGradient(listOf(color.copy(alpha = 0.26f), Color.Transparent), radius = 700f, center = Offset(900f, -40f))
}
