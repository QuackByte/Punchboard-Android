package dev.quackbyte.punchboard.export

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import dev.quackbyte.punchboard.R
import dev.quackbyte.punchboard.model.DateText
import dev.quackbyte.punchboard.model.PayResult
import dev.quackbyte.punchboard.model.PeriodResult
import dev.quackbyte.punchboard.model.TimesheetMeta
import dev.quackbyte.punchboard.model.TimesheetRow
import dev.quackbyte.punchboard.model.formatDuration
import dev.quackbyte.punchboard.model.formatMoney
import dev.quackbyte.punchboard.model.js
import dev.quackbyte.punchboard.model.payLines
import dev.quackbyte.punchboard.model.timesheetCsv
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Writes timesheets to the cache and hands them to the share sheet. */
object TimesheetExport {
    private fun exportFile(context: Context, name: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, name)
    }

    fun shareCsv(context: Context, monthKey: String, rows: List<TimesheetRow>, period: PeriodResult, pay: PayResult, meta: TimesheetMeta) {
        val file = exportFile(context, "punchboard-timesheet-$monthKey.csv")
        file.writeText(timesheetCsv(rows, period, pay, meta))
        share(context, file, "text/csv", meta.title)
    }

    fun sharePdf(context: Context, monthKey: String, rows: List<TimesheetRow>, period: PeriodResult, pay: PayResult, meta: TimesheetMeta) {
        val file = exportFile(context, "Punchboard timesheet $monthKey.pdf")
        file.outputStream().use { out -> renderPdf(context, rows, period, pay, meta).apply { writeTo(out); close() } }
        share(context, file, "application/pdf", meta.title)
    }

    fun shareJson(context: Context, text: String) {
        val stamp = java.time.LocalDate.now().toString()
        val file = exportFile(context, "punchboard-$stamp.json")
        file.writeText(text)
        share(context, file, "application/json", "Punchboard backup $stamp")
    }

    private fun share(context: Context, file: File, mime: String, subject: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** One A4 page (more if a period is unusually long), laid out like the desktop's print sheet. */
    private fun renderPdf(context: Context, rows: List<TimesheetRow>, period: PeriodResult, pay: PayResult, meta: TimesheetMeta): PdfDocument {
        val sans = runCatching { ResourcesCompat.getFont(context, R.font.bricolage_grotesque) }.getOrNull() ?: Typeface.SANS_SERIF
        val mono = runCatching { ResourcesCompat.getFont(context, R.font.jetbrains_mono) }.getOrNull() ?: Typeface.MONOSPACE
        val ink = Color.rgb(23, 23, 23)
        val grey = Color.rgb(115, 115, 115)
        val light = Color.rgb(163, 163, 163)
        val rule = Color.rgb(229, 229, 229)
        fun paint(face: Typeface, size: Float, color: Int = ink, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = if (bold) Typeface.create(face, Typeface.BOLD) else face
            textSize = size
            this.color = color
        }

        val width = 595
        val height = 842
        val margin = 36f
        val document = PdfDocument()
        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(width, height, pageNumber).create())
        var canvas: Canvas = page.canvas
        var y = margin

        fun newPage() {
            document.finishPage(page)
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(width, height, pageNumber).create())
            canvas = page.canvas
            y = margin
        }

        fun line(color: Int = rule, weight: Float = 0.6f) {
            canvas.drawLine(margin, y, width - margin, y, Paint().apply { this.color = color; strokeWidth = weight })
        }

        fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
            if (paint.measureText(text) <= maxWidth) return text
            var end = text.length
            while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
            return text.substring(0, end) + "…"
        }

        // Header
        canvas.drawText("TIMESHEET", margin, y + 8, paint(mono, 7f, grey).apply { letterSpacing = 0.2f })
        canvas.drawText(meta.title, margin, y + 28, paint(sans, 18f, bold = true))
        canvas.drawText(meta.periodLabel, margin, y + 42, paint(mono, 8.5f, grey))
        val brand = paint(sans, 11f, bold = true)
        canvas.drawText("Punchboard", width - margin - brand.measureText("Punchboard"), y + 12, brand)
        val generated = "Generated " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK))
        val small = paint(mono, 7f, grey)
        canvas.drawText(generated, width - margin - small.measureText(generated), y + 24, small)
        y += 58f
        line(ink, 1f)

        // Table
        val columns = listOf(margin, margin + 78, margin + 128, margin + 262, margin + 400, margin + 445, margin + 485)
        val headers = listOf("Date", "Status", "Sessions", "Notes", "Break", "Hours", "Overtime")
        val head = paint(sans, 7.5f, grey)
        y += 11f
        headers.forEachIndexed { index, header ->
            if (index >= 4) {
                val right = if (index == 6) width - margin else columns[index + 1] - 6
                canvas.drawText(header, right - head.measureText(header), y, head)
            } else {
                canvas.drawText(header, columns[index], y, head)
            }
        }
        y += 4f
        line(ink, 0.6f)

        val cellMono = paint(mono, 7.2f)
        val cellSans = paint(sans, 7.2f)
        val cellNote = paint(sans, 7.2f, grey)
        val quietMono = paint(mono, 7.2f, light)
        rows.forEach { row ->
            if (y > height - 190) newPage()
            y += 11.5f
            val quiet = row.status == "Day off" && row.hours == 0.0 && row.sessions.isEmpty()
            val m = if (quiet) quietMono else cellMono
            canvas.drawText("${DateText.weekdayShort(row.date)} ${DateText.iso(row.date)}", columns[0], y, m)
            if (!quiet) canvas.drawText(row.status, columns[1], y, cellSans)
            val sessions = row.sessions.ifEmpty { if (row.assumed) "scheduled" else "" }
            canvas.drawText(ellipsize(sessions, m, columns[3] - columns[2] - 6), columns[2], y, if (row.sessions.isEmpty()) quietMono else m)
            canvas.drawText(ellipsize(row.notes, cellNote, columns[4] - columns[3] - 6), columns[3], y, cellNote)
            fun right(text: String, col: Int, p: Paint) {
                val edge = if (col == 6) width - margin else columns[col + 1] - 6
                canvas.drawText(text, edge - p.measureText(text), y, p)
            }
            if (row.breakMinutes > 0) right("${row.breakMinutes}m", 4, m)
            right(if (row.hours > 0) formatDuration(row.hours) else "", 5, paint(mono, 7.2f, if (quiet) light else ink, bold = true))
            right(if (row.overtimeHours > 0) formatDuration(row.overtimeHours) else "", 6, m)
            y += 3.5f
            line()
        }
        y += 12f
        val totals = paint(mono, 8f, bold = true)
        canvas.drawText("Total", columns[0], y, paint(sans, 8f, bold = true))
        fun totalRight(text: String, col: Int) {
            val edge = if (col == 6) width - margin else columns[col + 1] - 6
            canvas.drawText(text, edge - totals.measureText(text), y, totals)
        }
        if (period.breakMinutesTotal > 0) totalRight(formatDuration(period.breakMinutesTotal / 60.0), 4)
        totalRight(formatDuration(period.actualHours), 5)
        if (period.overtimeHours > 0) totalRight(formatDuration(period.overtimeHours), 6)
        y += 6f
        line(ink, 1f)

        // Summary, two columns.
        if (y > height - 150) newPage()
        y += 18f
        val money: (Double) -> String = { amount ->
            formatMoney(amount, meta.currency) + (meta.conversion?.let { (code, rate) -> " (≈ ${formatMoney(amount * rate, code)})" } ?: "")
        }
        val left = mutableListOf(
            "Scheduled" to formatDuration(period.estimatedHours),
            "Worked" to formatDuration(period.actualHours),
            "Workdays" to period.workingDaysCount.toString(),
            "Vacation days" to period.vacationDays.toString(),
            "Sick days" to period.sickDays.toString(),
        )
        meta.vacation?.let { (label, used, allowance) -> left += "Vacation $label" to "$used of ${allowance.js()}" }
        left += "Days-off hours" to formatDuration(period.extraHoursTotal)
        val rightLines = mutableListOf("Rate" to "${formatMoney(pay.rate, meta.currency)}/h")
        if (pay.salaried) {
            rightLines += "Effective rate" to "${formatMoney(pay.effectiveRate, meta.currency)}/h"
            if (pay.unpaidOvertimeHours > 0) rightLines += "Unpaid overtime" to formatDuration(pay.unpaidOvertimeHours)
        }
        payLines(pay, meta).forEach { (label, amount) -> rightLines += label to money(amount) }

        val labelPaint = paint(sans, 8f, grey)
        val valuePaint = paint(mono, 8f)
        val colWidth = (width - margin * 2 - 24) / 2
        fun summary(lines: List<Pair<String, String>>, x: Float, startY: Float) {
            var lineY = startY
            lines.forEach { (label, value) ->
                val bold = label == "Net"
                canvas.drawText(label, x, lineY, if (bold) paint(sans, 9f, bold = true) else labelPaint)
                val p = if (bold) paint(mono, 9f, bold = true) else valuePaint
                canvas.drawText(value, x + colWidth - p.measureText(value), lineY, p)
                lineY += 13f
            }
        }
        summary(left, margin, y)
        summary(rightLines, margin + colWidth + 24, y)

        document.finishPage(page)
        return document
    }
}
