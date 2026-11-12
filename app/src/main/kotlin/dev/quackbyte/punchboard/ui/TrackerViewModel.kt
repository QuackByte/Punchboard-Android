package dev.quackbyte.punchboard.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.quackbyte.punchboard.data.DataFileCodec
import dev.quackbyte.punchboard.data.ImportResult
import dev.quackbyte.punchboard.data.PunchboardDocument
import dev.quackbyte.punchboard.data.PunchboardRepository
import dev.quackbyte.punchboard.data.PunchboardStore
import dev.quackbyte.punchboard.data.SyncStatus
import dev.quackbyte.punchboard.domain.DaySnapshot
import dev.quackbyte.punchboard.domain.OpenSession
import dev.quackbyte.punchboard.domain.TodayTarget
import dev.quackbyte.punchboard.domain.Tracker
import dev.quackbyte.punchboard.domain.TrackerEditor
import dev.quackbyte.punchboard.model.ActivityLogEntry
import dev.quackbyte.punchboard.model.DataFile
import dev.quackbyte.punchboard.model.DayException
import dev.quackbyte.punchboard.model.DayKeys
import dev.quackbyte.punchboard.model.MonthKeys
import dev.quackbyte.punchboard.model.PayResult
import dev.quackbyte.punchboard.model.PayslipPeriods
import dev.quackbyte.punchboard.model.PeriodResult
import dev.quackbyte.punchboard.model.TimeEntry
import dev.quackbyte.punchboard.model.TrackerData
import dev.quackbyte.punchboard.model.TrackerSettings
import dev.quackbyte.punchboard.model.VacationCount
import dev.quackbyte.punchboard.model.balanceNow
import dev.quackbyte.punchboard.model.carriedBalance
import dev.quackbyte.punchboard.model.computePay
import dev.quackbyte.punchboard.model.countVacation
import dev.quackbyte.punchboard.model.formatLeaveYear
import dev.quackbyte.punchboard.model.leaveYearRange
import dev.quackbyte.punchboard.model.mondayOf
import dev.quackbyte.punchboard.model.workedTime
import dev.quackbyte.punchboard.reminders.ReminderEngine
import dev.quackbyte.punchboard.reminders.ReminderPreferences
import dev.quackbyte.punchboard.reminders.ReminderSettings
import dev.quackbyte.punchboard.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

data class YearPoint(val month: YearMonth, val hours: Double)

data class VacationYear(val label: String, val allowance: Double, val taken: Int, val booked: Int)

data class ToDate(val logged: Double, val scheduled: Double, val daysElapsed: Int)

/** Everything the screens show, computed from the card for the period in view. */
data class TrackerSnapshot(
    val data: DataFile,
    val tracker: Tracker,
    val now: LocalDateTime,
    val today: LocalDate,
    val currentPeriod: YearMonth,
    val viewMonth: YearMonth,
    val monthData: TrackerData,
    val period: PeriodResult,
    val pay: PayResult,
    val target: TodayTarget,
    val openSession: OpenSession?,
    val todayEntries: List<TimeEntry>,
    val cells: List<LocalDate?>,
    /** Carried balance after each week, by the week's Monday. */
    val weekBalances: Map<LocalDate, Double>,
    val graphYear: Int,
    val yearly: List<YearPoint>,
    val vacationYear: VacationYear,
    val periodVacation: VacationYear,
    val toDate: ToDate?,
    val savedMonths: Set<YearMonth>,
) {
    val isViewingCurrentPeriod get() = viewMonth == currentPeriod
    val rules get() = tracker.rules
    val timeEntries: Map<String, List<TimeEntry>> get() = monthData.timeEntries.orEmpty()
    val recentActivity: List<ActivityLogEntry> get() = data.activityLog.take(8)
    val showConversion get() = monthData.currencyConversionEnabled && monthData.currency != monthData.secondaryCurrency
}

sealed interface Screen {
    data object Loading : Screen
    data object Welcome : Screen
    data class Ready(val snapshot: TrackerSnapshot) : Screen
}

data class UndoOffer(val id: Long, val message: String, val snapshot: DaySnapshot)

class TrackerViewModel(app: Application) : AndroidViewModel(app) {
    val repository = PunchboardRepository.get(app)
    private val uiPrefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val reminderPrefs = ReminderPreferences(app)

    private val viewMonth = MutableStateFlow<YearMonth?>(null)
    private val graphYear = MutableStateFlow<Int?>(null)

    private val _theme = MutableStateFlow(
        runCatching { ThemeMode.valueOf(uiPrefs.getString("theme", null) ?: "") }.getOrDefault(ThemeMode.System),
    )
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _reminders = MutableStateFlow(reminderPrefs.settings)
    val reminders: StateFlow<ReminderSettings> = _reminders.asStateFlow()

    private val _undo = MutableStateFlow<UndoOffer?>(null)
    val undo: StateFlow<UndoOffer?> = _undo.asStateFlow()
    private var undoTimer: Job? = null

    val syncStatus: StateFlow<SyncStatus> = repository.syncStatus

    /** Minute ticks: totals, cells and week balances follow a running clock. */
    private val minutes = flow {
        while (true) {
            emit(LocalDateTime.now())
            delay(30_000)
        }
    }

    private val now = MutableStateFlow(LocalDateTime.now())

    init {
        viewModelScope.launch { minutes.collect { now.value = it } }
    }

    val screen: StateFlow<Screen> = combine(
        repository.document,
        repository.loaded,
        viewMonth,
        graphYear,
        now,
    ) { document, loaded, month, year, at ->
        when {
            !loaded -> Screen.Loading
            document == null -> Screen.Welcome
            else -> Screen.Ready(snapshot(document.data, month, year, at))
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, Screen.Loading)

    fun refreshNow() {
        now.value = LocalDateTime.now()
    }

    // Navigation within the card.

    fun showMonth(month: YearMonth) {
        val current = (screen.value as? Screen.Ready)?.snapshot?.currentPeriod
        viewMonth.value = if (month == current) null else month
    }

    fun moveMonth(step: Long) {
        val snapshot = (screen.value as? Screen.Ready)?.snapshot ?: return
        showMonth(snapshot.viewMonth.plusMonths(step))
    }

    fun goToCurrentPeriod() {
        viewMonth.value = null
    }

    fun setGraphYear(year: Int) {
        graphYear.value = year
    }

    // Edits.

    private fun edit(transform: (TrackerEditor) -> DataFile) {
        viewModelScope.launch { repository.edit { transform(TrackerEditor(it)) } }
    }

    /** An edit that can be undone from the toast. */
    private fun editDay(month: YearMonth, date: LocalDate, message: String?, transform: (TrackerEditor) -> DataFile?) {
        viewModelScope.launch {
            var snapshot: DaySnapshot? = null
            repository.edit { data ->
                val editor = TrackerEditor(data)
                snapshot = editor.snapshot(month, DayKeys.of(date))
                transform(editor) ?: data
            }
            val taken = snapshot
            if (message != null && taken != null && !taken.isEmpty) offerUndo(message, taken)
        }
    }

    private fun offerUndo(message: String, snapshot: DaySnapshot) {
        undoTimer?.cancel()
        val offer = UndoOffer(System.nanoTime(), message, snapshot)
        _undo.value = offer
        undoTimer = viewModelScope.launch {
            delay(7_000)
            if (_undo.value?.id == offer.id) _undo.value = null
        }
    }

    fun undo() {
        val offer = _undo.value ?: return
        _undo.value = null
        edit { it.restore(offer.snapshot) }
    }

    fun dismissUndo() {
        _undo.value = null
    }

    fun punchIn() = edit { it.punchIn() }

    fun punchOut() = edit { it.punchOut() }

    fun applyDayInput(month: YearMonth, date: LocalDate, text: String): Boolean {
        val data = (screen.value as? Screen.Ready)?.snapshot?.data ?: return false
        if (TrackerEditor(data).applyDayInput(month, date, text) == null) return false
        editDay(month, date, "${dayLabel(date)} replaced") { it.applyDayInput(month, date, text) }
        return true
    }

    fun setDayEntries(month: YearMonth, date: LocalDate, entries: List<TimeEntry>) =
        edit { it.setDayEntries(month, date, entries) }

    fun removeSession(month: YearMonth, date: LocalDate, index: Int, entry: TimeEntry) =
        editDay(month, date, "Removed ${entry.start}–${entry.end ?: "now"} on ${dayLabel(date)}") {
            it.removeSession(month, date, index)
        }

    fun resetDay(month: YearMonth, date: LocalDate, isWorkday: Boolean) =
        editDay(month, date, if (isWorkday) "${dayLabel(date)} reset to schedule" else "${dayLabel(date)} cleared") {
            it.resetDay(month, date)
        }

    fun setException(month: YearMonth, date: LocalDate, type: DayException?, current: DayException?) {
        val clearing = type == null || type == current
        editDay(month, date, if (clearing) "Cleared ${dayLabel(date)}" else "${dayLabel(date)} marked ${type!!.wire}") {
            it.setDayException(month, date, type)
        }
    }

    fun editSettings(block: (TrackerEditor, YearMonth) -> DataFile) {
        val month = (screen.value as? Screen.Ready)?.snapshot?.viewMonth ?: return
        edit { block(it, month) }
    }

    fun updateSettings(message: String, transform: (TrackerSettings) -> TrackerSettings) =
        editSettings { editor, month -> editor.updateSettings(month, message, transform) }

    // Preferences.

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        uiPrefs.edit().putString("theme", mode.name).apply()
    }

    fun setReminders(settings: ReminderSettings) {
        _reminders.value = settings
        reminderPrefs.settings = settings
        viewModelScope.launch(Dispatchers.IO) { ReminderEngine.check(getApplication()) }
    }

    // Data.

    suspend fun readImport(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        PunchboardStore.readForImport(getApplication<Application>().contentResolver, uri)
    }

    fun importDocument(document: PunchboardDocument, keepInSync: Uri?) {
        viewModelScope.launch {
            if (keepInSync != null) {
                repository.connect(keepInSync, adoptFile = true)
            } else {
                repository.replace(document)
            }
        }
    }

    fun startFresh() {
        viewModelScope.launch { repository.replace(PunchboardDocument.new()) }
    }

    fun connectSync(uri: Uri, adoptFile: Boolean) {
        viewModelScope.launch { repository.connect(uri, adoptFile) }
    }

    fun disconnectSync() = repository.disconnect()

    fun syncNow() {
        viewModelScope.launch { repository.sync() }
    }

    suspend fun writeTo(uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.encodeToByteArray()) }
        }.isSuccess
    }

    fun backupText(): String? = repository.exportText()

    fun restoreBackup(document: PunchboardDocument) {
        viewModelScope.launch {
            val current = repository.document.value
            // Keep it one card: a restored backup replaces the data, not the file it syncs with.
            repository.replace(current?.withData(document.data) ?: document)
        }
    }

    companion object {
        fun dayLabel(date: LocalDate) = dev.quackbyte.punchboard.model.DateText.weekdayDayMonth(date)

        fun snapshot(data: DataFile, month: YearMonth?, year: Int?, now: LocalDateTime): TrackerSnapshot {
            val tracker = Tracker(data)
            val today = now.toLocalDate()
            val nowMinutes = now.hour * 60 + now.minute
            val currentPeriod = tracker.currentPeriod(today)
            val viewMonth = month ?: currentPeriod
            val monthData = tracker.monthData(viewMonth)
            val period = tracker.period(viewMonth)
            val pay = computePay(period, monthData, tracker.rules)
            val target = tracker.todayTarget(today)
            val openSession = tracker.openSession(currentPeriod)
            val todayEntries = tracker.todayEntries(today)

            val range = PayslipPeriods.range(viewMonth, tracker.payslipStartDay)
            val cells = buildList<LocalDate?> {
                repeat(range.start.dayOfWeek.value - 1) { add(null) }
                var date = range.start
                while (date <= range.endInclusive) {
                    add(date)
                    date = date.plusDays(1)
                }
                while (size % 7 != 0) add(null)
            }

            val sources = tracker.balanceSources(viewMonth)
            val weekBalances = mutableMapOf<LocalDate, Double>()
            if (tracker.rules.balanceWeek && tracker.rules.carryBalance) {
                val thisMonday = mondayOf(today)
                cells.chunked(7).forEach { week ->
                    val first = week.firstOrNull { it != null } ?: return@forEach
                    val monday = mondayOf(first)
                    if (monday > today) return@forEach
                    weekBalances[monday] = if (monday == thisMonday) {
                        val runningMinutes = workedTime(todayEntries, tracker.rules, nowMinutes).workedMinutes -
                            workedTime(todayEntries, tracker.rules).workedMinutes
                        balanceNow(sources, today, tracker.rules, target.hours, target.isWorkday, runningMinutes / 60.0)
                    } else {
                        carriedBalance(sources, minOf(monday.plusDays(7), today), tracker.rules)
                    }
                }
            }

            val graphYear = year ?: viewMonth.year
            val yearly = (1..12).map { m ->
                val ym = YearMonth.of(graphYear, m)
                YearPoint(ym, if (tracker.isSaved(ym) || ym == viewMonth) tracker.period(ym).actualHours else 0.0)
            }

            fun vacationIn(leaveStartYear: Int): VacationYear {
                val leave = leaveYearRange(leaveStartYear, tracker.leaveYearStartMonth)
                val start = YearMonth.from(leave.start)
                val days = (0L until 13L).flatMap { i ->
                    val ym = start.plusMonths(i)
                    if (tracker.isSaved(ym)) tracker.period(ym).days else emptyList()
                }
                val count: VacationCount = countVacation(days, leave, today)
                return VacationYear(formatLeaveYear(leave), tracker.vacationDaysPerYear, count.taken, count.booked)
            }
            val periodEnd = range.endInclusive
            val periodVacation = vacationIn(periodEnd.year - if (periodEnd.monthValue >= tracker.leaveYearStartMonth) 0 else 1)

            val toDate = if (viewMonth == currentPeriod) {
                var logged = 0.0
                var scheduled = 0.0
                var elapsed = 0
                period.days.filter { it.date <= today }.forEach { day ->
                    logged += day.hours
                    if (day.isWorkingDay) {
                        scheduled += monthData.hoursPerDay
                        elapsed++
                    }
                }
                ToDate(logged, scheduled, elapsed)
            } else {
                null
            }

            return TrackerSnapshot(
                data = data,
                tracker = tracker,
                now = now,
                today = today,
                currentPeriod = currentPeriod,
                viewMonth = viewMonth,
                monthData = monthData,
                period = period,
                pay = pay,
                target = target,
                openSession = openSession,
                todayEntries = todayEntries,
                cells = cells,
                weekBalances = weekBalances,
                graphYear = graphYear,
                yearly = yearly,
                vacationYear = vacationIn(graphYear),
                periodVacation = periodVacation,
                toDate = toDate,
                savedMonths = data.months.keys.mapNotNull(MonthKeys::parse).toSet(),
            )
        }
    }
}

/** The card as text for a backup file. */
fun PunchboardDocument.toBackupText(): String = DataFileCodec.encode(this)
