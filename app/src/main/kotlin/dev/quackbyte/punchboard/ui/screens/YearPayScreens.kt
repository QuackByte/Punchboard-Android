package dev.quackbyte.punchboard.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.quackbyte.punchboard.model.DateText
import dev.quackbyte.punchboard.model.formatDuration
import dev.quackbyte.punchboard.model.formatMoney
import dev.quackbyte.punchboard.model.js
import dev.quackbyte.punchboard.ui.TrackerSnapshot
import dev.quackbyte.punchboard.ui.components.LeaderRow
import dev.quackbyte.punchboard.ui.components.Lucide
import dev.quackbyte.punchboard.ui.components.MonoText
import dev.quackbyte.punchboard.ui.components.Panel
import dev.quackbyte.punchboard.ui.components.Perforation
import dev.quackbyte.punchboard.ui.components.SquareIconButton
import dev.quackbyte.punchboard.ui.components.Stamp
import dev.quackbyte.punchboard.ui.components.TextAction
import dev.quackbyte.punchboard.ui.theme.LocalPb
import dev.quackbyte.punchboard.ui.theme.Mono
import dev.quackbyte.punchboard.ui.theme.PbType
import java.time.YearMonth
import kotlin.math.roundToInt

@Composable
fun YearScreen(snapshot: TrackerSnapshot, onYear: (Int) -> Unit, onSelectMonth: (YearMonth) -> Unit) {
    val pb = LocalPb.current
    val yearly = snapshot.yearly
    val maxHours = maxOf(yearly.maxOfOrNull { it.hours } ?: 0.0, 1.0)
    val tracked = yearly.filter { it.hours > 0 }
    val total = tracked.sumOf { it.hours }
    val average = if (tracked.isNotEmpty()) total / tracked.size else 0.0
    val vacation = snapshot.vacationYear
    val used = vacation.taken + vacation.booked
    val left = vacation.allowance - used

    Panel(Modifier.fillMaxWidth()) {
        Stamp("05 · Year")
        Row(verticalAlignment = Alignment.CenterVertically) {
            SquareIconButton(Lucide.ChevronLeft, "Previous year", { onYear(snapshot.graphYear - 1) }, border = pb.card)
            Text(
                snapshot.graphYear.toString(),
                Modifier.width(84.dp),
                fontFamily = Mono,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                textAlign = TextAlign.Center,
                color = pb.foreground,
            )
            SquareIconButton(Lucide.ChevronRight, "Next year", { onYear(snapshot.graphYear + 1) }, border = pb.card)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Column {
                Stamp("Total")
                Text(formatDuration(total), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = pb.foreground)
            }
            Column {
                Stamp("Avg / month")
                Text(formatDuration(average), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = pb.foreground)
            }
            if (vacation.allowance > 0 || used > 0) {
                Column {
                    Stamp(if (vacation.label != snapshot.graphYear.toString()) "Vacation ${vacation.label}" else "Vacation")
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(used.toString(), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = pb.foreground)
                        MonoText(if (vacation.allowance > 0) " / ${vacation.allowance.js()}d" else "d", size = 13f, color = pb.mutedForeground)
                    }
                    Text(
                        (if (vacation.booked > 0) "${vacation.booked} booked · " else "") + when {
                            vacation.allowance <= 0 -> "no allowance set"
                            left < 0 -> "${(-left).js()} over"
                            else -> "${left.js()} left"
                        },
                        style = PbType.bodySmall.copy(fontSize = 11.sp),
                        color = if (vacation.allowance > 0 && left < 0) pb.vacationText else pb.mutedForeground,
                    )
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth().height(190.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
            yearly.forEach { point ->
                val selected = point.month == snapshot.viewMonth
                val fraction = if (point.hours > 0) maxOf(point.hours / maxHours, 0.04).toFloat() else 0f
                val animated by animateFloatAsState(fraction, tween(500), label = "bar")
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { onSelectMonth(point.month) }
                        .semantics { contentDescription = "${DateText.monthLong(point.month)} ${point.month.year}: ${formatDuration(point.hours)}" },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    if (point.hours > 0 || selected) {
                        Text(
                            "${point.hours.roundToInt()}h",
                            fontFamily = Mono,
                            fontSize = 9.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) pb.foreground else pb.mutedForeground,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Box(Modifier.weight(1f, fill = true).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        if (point.hours > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(animated)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) pb.primary else pb.foreground.copy(alpha = 0.15f)),
                            )
                        } else {
                            Box(
                                Modifier.fillMaxWidth().height(2.dp).drawBehind {
                                    drawLine(pb.mutedForeground.copy(alpha = 0.4f), Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        DateText.monthShort(point.month).take(3).uppercase(),
                        fontFamily = Mono,
                        fontSize = 9.sp,
                        letterSpacing = 0.05.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) pb.foreground else pb.mutedForeground,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Hours per payslip period; tap a month to open it.", style = PbType.bodySmall.copy(fontSize = 12.sp), color = pb.mutedForeground)
    }
}

@Composable
fun PayScreen(snapshot: TrackerSnapshot, onExportCsv: () -> Unit, onExportPdf: () -> Unit) {
    val pb = LocalPb.current
    val pay = snapshot.pay
    val month = snapshot.monthData
    val currency = month.currency
    val multiplier = snapshot.rules.overtimeMultiplier.js()
    val conversion = snapshot.showConversion
    fun money(value: Double, sign: String = "") = sign + formatMoney(value, currency)
    fun converted(value: Double, sign: String = "") =
        if (conversion) sign + formatMoney(value * month.conversionRate, month.secondaryCurrency) else null

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stamp("03 · Payslip")
                MonoText(dev.quackbyte.punchboard.model.periodLabel(snapshot.viewMonth, snapshot.tracker.payslipStartDay), size = 11f, color = pb.mutedForeground)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append("${formatMoney(pay.rate, currency)}/h")
                    if (pay.salaried && pay.effectiveRate > 0) append(" · ${formatMoney(pay.effectiveRate, currency)}/h worked")
                    if (!pay.salaried && pay.yearly > 0) append(" · ≈${formatMoney(pay.yearly, currency)}/yr")
                },
                fontFamily = Mono,
                fontSize = 11.5.sp,
                color = if (pay.salaried && pay.effectiveRate < pay.rate - 0.005) pb.extraText else pb.mutedForeground,
            )
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (conversion) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Stamp(currency)
                        Stamp(month.secondaryCurrency, Modifier.width(92.dp).padding(start = 10.dp))
                    }
                }
                if (pay.salaried) {
                    LeaderRow("Yearly", money(pay.yearly), valueColor = pb.mutedForeground, trailing = converted(pay.yearly))
                    LeaderRow("Salary ÷ 12", money(pay.regularPay), valueColor = pb.mutedForeground, trailing = converted(pay.regularPay))
                    if (pay.overtimePay > 0) {
                        LeaderRow("Overtime ${formatDuration(pay.overtimeHours)} ×$multiplier", money(pay.overtimePay, "+"), valueColor = pb.extraText, trailing = converted(pay.overtimePay, "+"))
                    }
                    if (pay.unpaidOvertimeHours > 0) {
                        LeaderRow("Unpaid overtime ${formatDuration(pay.unpaidOvertimeHours)}", money(0.0), valueColor = pb.mutedForeground, trailing = converted(0.0))
                    }
                } else if (pay.overtimePay > 0) {
                    LeaderRow("Regular ${formatDuration(pay.regularHours)}", money(pay.regularPay), valueColor = pb.mutedForeground, trailing = converted(pay.regularPay))
                    LeaderRow("Overtime ${formatDuration(pay.overtimeHours)} ×$multiplier", money(pay.overtimePay, "+"), valueColor = pb.extraText, trailing = converted(pay.overtimePay, "+"))
                }
                LeaderRow("Gross", money(pay.gross), trailing = converted(pay.gross))
                LeaderRow("Tax ${month.taxPercent.js()}%", money(pay.tax, "−"), valueColor = pb.mutedForeground, trailing = converted(pay.tax, "−"))
                LeaderRow("Deductions", money(pay.deductions, "−"), valueColor = pb.mutedForeground, trailing = converted(pay.deductions, "−"))
            }
            Spacer(Modifier.height(18.dp))
            Perforation()
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Stamp("Net pay", Modifier.padding(bottom = 6.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatMoney(pay.net, currency), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, color = pb.foreground)
                    if (conversion) MonoText("≈ ${formatMoney(pay.net * month.conversionRate, month.secondaryCurrency)}", size = 12f, color = pb.mutedForeground)
                }
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Stamp("Timesheet")
            MonoText(dev.quackbyte.punchboard.model.periodLabel(snapshot.viewMonth, snapshot.tracker.payslipStartDay, withYear = true), size = 11f, color = pb.mutedForeground)
            Spacer(Modifier.height(6.dp))
            TextAction("Share as CSV", onExportCsv, icon = Lucide.Sheet)
            TextAction("Share as PDF", onExportPdf, icon = Lucide.Printer)
        }
    }
}
