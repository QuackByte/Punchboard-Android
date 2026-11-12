package dev.quackbyte.punchboard.data

import dev.quackbyte.punchboard.model.DataFile
import dev.quackbyte.punchboard.model.DayException
import dev.quackbyte.punchboard.model.MonthKeys
import java.time.YearMonth

/** What an import would bring over, shown before the user confirms it. */
data class ImportSummary(
    val monthCount: Int,
    val firstMonth: YearMonth?,
    val lastMonth: YearMonth?,
    /** Days with any hours or sessions logged. */
    val daysLogged: Int,
    val sessions: Int,
    val vacationDays: Int,
    val sickDays: Int,
    /** A session was still running on the desktop when the file was saved. */
    val hasRunningSession: Boolean,
    /** ISO timestamp the desktop wrote, if any. */
    val savedAt: String?,
) {
    companion object {
        fun of(data: DataFile): ImportSummary {
            val monthKeys = data.months.keys.mapNotNull(MonthKeys::parse).sorted()
            val loggedDays = mutableSetOf<String>()
            var sessions = 0
            var running = false
            var vacation = 0
            var sick = 0

            for (month in data.months.values) {
                month.dailyHours.filterValues { it > 0 }.keys.forEach(loggedDays::add)
                month.extraHours.filterValues { it > 0 }.keys.forEach(loggedDays::add)
                month.timeEntries.orEmpty().forEach { (day, entries) ->
                    if (entries.isNotEmpty()) loggedDays += day
                    sessions += entries.size
                    if (entries.any { it.isRunning }) running = true
                }
                month.exceptions.values.forEach {
                    when (DayException.fromWire(it)) {
                        DayException.Vacation -> vacation++
                        DayException.Sick -> sick++
                        null -> Unit
                    }
                }
            }

            return ImportSummary(
                monthCount = data.months.size,
                firstMonth = monthKeys.firstOrNull(),
                lastMonth = monthKeys.lastOrNull(),
                daysLogged = loggedDays.size,
                sessions = sessions,
                vacationDays = vacation,
                sickDays = sick,
                hasRunningSession = running,
                savedAt = data.savedAt.ifBlank { null },
            )
        }
    }
}
