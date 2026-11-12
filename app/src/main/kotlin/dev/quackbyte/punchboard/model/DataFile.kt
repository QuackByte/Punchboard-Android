package dev.quackbyte.punchboard.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/*
 * The Punchboard data file, exactly as the desktop app writes it
 * (`src/components/workTracker/types.ts` in QuackByte/punchboard). The phone
 * keeps its data in this same format so a file can move between desktop and
 * phone in either direction without conversion.
 *
 * Key formats are inherited from the desktop and must not change:
 * - month keys are "YYYY-MM" with a one-based month (see [MonthKeys]);
 * - day keys are "YYYY-M-D" with a ZERO-based month and no padding
 *   (see [DayKeys]), e.g. "2026-9-14" is 14 October 2026.
 */

@Serializable
data class DataFile(
    val version: Int = SUPPORTED_VERSION,
    val savedAt: String = "",
    val uiState: TrackerUiState = TrackerUiState(),
    val settings: TrackerSettings = TrackerSettings(),
    val savedMonths: List<String> = emptyList(),
    val activityLog: List<ActivityLogEntry> = emptyList(),
    /** Keyed by the payslip period's end month ("YYYY-MM"). */
    val months: Map<String, TrackerData> = emptyMap(),
) {
    companion object {
        const val SUPPORTED_VERSION = 1
    }
}

@Serializable
data class TrackerUiState(
    val monthKey: String = "",
    val graphYear: Int = 0,
)

/**
 * Settings shared by every month. The work rules are optional in the file
 * (older versions never wrote them); [WorkRules.from] fills the defaults.
 */
@Serializable
data class TrackerSettings(
    val payslipStartDay: Int = 21,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val vacationDaysPerYear: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val leaveYearStartMonth: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val breakMinutes: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val breakAfterHours: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val roundingMinutes: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val overtimeMultiplier: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val overtimeIncludesLongDays: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val balanceWeek: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val carryBalance: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val balanceResetsYearly: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val balanceCapHours: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val balanceStartDate: String? = null,
)

/** One payslip period's schedule, pay settings and logged time. */
@Serializable
data class TrackerData(
    /** Workdays as "mon".."sun". */
    val selectedDays: List<String> = listOf("mon", "tue", "wed", "thu", "fri"),
    val hoursPerDay: Double = 8.0,
    val hourlyRate: Double = 15.0,
    val currency: String = "GBP",
    val secondaryCurrency: String = "EUR",
    val conversionRate: Double = 1.0,
    val currencyConversionEnabled: Boolean = false,
    val taxPercent: Double = 0.0,
    val extraDeduction: Double = 0.0,
    val defaultHours: Double = 8.0,
    /** Day key → "vacation" | "sick". */
    val exceptions: Map<String, String> = emptyMap(),
    /** Day key → hours on a workday. Derived from [timeEntries] when present. */
    val dailyHours: Map<String, Double> = emptyMap(),
    /** Day key → hours on a day off. Derived from [timeEntries] when present. */
    val extraHours: Map<String, Double> = emptyMap(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val timeEntries: Map<String, List<TimeEntry>>? = null,
    /** Missing in older files, which were always paid by the hour. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val salary: SalarySettings? = null,
)

/**
 * One clocked session as "HH:MM" wall-clock times. A null [end] means the
 * session is still running; an [end] before [start] ran past midnight.
 *
 * [end] has no default on purpose: the desktop tells a running session apart
 * by `end === null`, so it must always be written, null included.
 */
@Serializable
data class TimeEntry(
    val start: String,
    val end: String?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val note: String? = null,
) {
    val isRunning: Boolean get() = end == null
}

@Serializable
data class SalarySettings(
    val enabled: Boolean = false,
    /** "weekly" | "yearly". */
    val basis: String = "weekly",
    val weeklyHours: Double = 0.0,
    val yearlyAmount: Double = 0.0,
    val overtimePaid: Boolean = false,
)

@Serializable
data class ActivityLogEntry(
    val id: String,
    val monthKey: String,
    val timestamp: String,
    val type: String,
    val message: String,
)

enum class DayException(val wire: String) {
    Vacation("vacation"),
    Sick("sick");

    companion object {
        fun fromWire(value: String?): DayException? = entries.firstOrNull { it.wire == value }
    }
}
