package dev.quackbyte.punchboard.model

import java.time.LocalDate

/*
 * Timesheet export (`timesheet.ts`): a computed period as rows for the CSV
 * and the PDF, so both always agree with the on-screen totals.
 */

data class TimesheetRow(
    val key: String,
    val date: LocalDate,
    val status: String,
    val sessions: String,
    val notes: String,
    val clockedHours: Double,
    val breakMinutes: Int,
    val hours: Double,
    val overtimeHours: Double,
    /** The hours come from the schedule rather than anything logged. */
    val assumed: Boolean,
)

data class TimesheetMeta(
    val title: String,
    val periodLabel: String,
    val currency: String,
    val taxPercent: Double,
    val overtimeMultiplier: Double,
    /** Money also given in a second currency. */
    val conversion: Pair<String, Double>? = null,
    /** Vacation in the leave year, when an allowance is set: label, used, allowance. */
    val vacation: Triple<String, Int, Double>? = null,
)

/** The payslip's money lines, labelled without a currency. */
fun payLines(pay: PayResult, meta: TimesheetMeta): List<Pair<String, Double>> {
    val lines = mutableListOf<Pair<String, Double>>()
    val multiplier = meta.overtimeMultiplier.js()
    if (pay.salaried) {
        lines += "Yearly salary" to pay.yearly
        lines += "Salary" to pay.regularPay
        if (pay.overtimePay > 0) lines += "Overtime pay ×$multiplier" to pay.overtimePay
    } else if (pay.overtimePay > 0) {
        lines += "Regular pay" to pay.regularPay
        lines += "Overtime pay ×$multiplier" to pay.overtimePay
    }
    lines += "Gross" to pay.gross
    lines += "Tax ${meta.taxPercent.js()}%" to pay.tax
    lines += "Deductions" to pay.deductions
    lines += "Net" to pay.net
    return lines
}

private fun statusOf(day: DayResult) = when {
    day.exception == DayException.Vacation -> "Vacation"
    day.exception == DayException.Sick -> "Sick"
    day.isWorkingDay -> "Workday"
    else -> "Day off"
}

fun buildTimesheetRows(period: PeriodResult, timeEntries: Map<String, List<TimeEntry>>): List<TimesheetRow> =
    period.days.map { day ->
        val entries = timeEntries[day.key].orEmpty()
        TimesheetRow(
            key = day.key,
            date = day.date,
            status = statusOf(day),
            sessions = entries.joinToString(", ") { "${it.start}–${it.end ?: "running"}" },
            notes = entries.mapNotNull { it.note?.trim()?.takeIf(String::isNotEmpty) }.joinToString("; "),
            clockedHours = day.clockedMinutes / 60.0,
            breakMinutes = day.breakMinutes,
            hours = day.hours,
            overtimeHours = day.overtimeHours,
            assumed = day.source == DaySource.Scheduled,
        )
    }

private fun csvCell(value: Any): String {
    val text = when (value) {
        is Double -> round2(value).js()
        is Int -> value.toString()
        else -> value.toString()
    }
    return if (Regex("[\",\n\r]").containsMatchIn(text)) "\"${text.replace("\"", "\"\"")}\"" else text
}

fun timesheetCsv(rows: List<TimesheetRow>, period: PeriodResult, pay: PayResult, meta: TimesheetMeta): String {
    val lines = mutableListOf<List<Any>>()
    lines += listOf("Date", "Weekday", "Status", "Sessions", "Notes", "Clocked (h)", "Break (min)", "Hours", "Overtime (h)", "Source")
    rows.forEach { row ->
        lines += listOf(
            DateText.iso(row.date),
            DateText.weekdayShort(row.date),
            row.status,
            row.sessions,
            row.notes,
            row.clockedHours,
            row.breakMinutes,
            row.hours,
            row.overtimeHours,
            when {
                row.assumed -> "Scheduled"
                row.sessions.isNotEmpty() -> "Punched"
                row.hours > 0 -> "Manual"
                else -> ""
            },
        )
    }
    lines += emptyList<Any>()
    lines += listOf("Period", meta.periodLabel)
    lines += listOf("Hours worked", period.actualHours)
    lines += listOf("Hours scheduled", period.estimatedHours)
    lines += listOf("Overtime hours", period.overtimeHours)
    lines += listOf("Workdays", period.workingDaysCount)
    lines += listOf("Vacation days", period.vacationDays)
    lines += listOf("Sick days", period.sickDays)
    meta.vacation?.let { (label, used, allowance) ->
        lines += listOf("Vacation days used ($label)", used)
        lines += listOf("Vacation allowance", allowance)
    }
    lines += listOf("Breaks deducted (min)", period.breakMinutesTotal)
    lines += listOf("Hourly rate (${meta.currency})", pay.rate)
    if (pay.salaried) {
        lines += listOf("Effective hourly rate (${meta.currency})", pay.effectiveRate)
        lines += listOf("Unpaid overtime (h)", pay.unpaidOvertimeHours)
    }
    val money = payLines(pay, meta)
    money.forEach { (label, amount) -> lines += listOf("$label (${meta.currency})", amount) }
    meta.conversion?.let { (currency, rate) ->
        lines += emptyList<Any>()
        lines += listOf("Converted to $currency", "at ${rate.js()}")
        money.forEach { (label, amount) -> lines += listOf("$label ($currency)", amount * rate) }
    }
    // CRLF and a BOM so Excel opens it as UTF-8 with the en dashes intact.
    return "﻿" + lines.joinToString("\r\n") { line -> line.joinToString(",") { csvCell(it) } }
}
