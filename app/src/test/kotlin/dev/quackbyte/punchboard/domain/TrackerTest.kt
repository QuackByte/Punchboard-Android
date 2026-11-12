package dev.quackbyte.punchboard.domain

import dev.quackbyte.punchboard.data.DataFileCodec
import dev.quackbyte.punchboard.data.DecodeResult
import dev.quackbyte.punchboard.data.JsonMerge
import dev.quackbyte.punchboard.model.DataFile
import dev.quackbyte.punchboard.model.DayException
import dev.quackbyte.punchboard.model.SalarySettings
import dev.quackbyte.punchboard.model.TimeEntry
import dev.quackbyte.punchboard.model.TrackerData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

class TrackerTest {
    private val desktop = (DataFileCodec.decode(javaClass.getResource("/desktop-punchboard.json")!!.readText()) as DecodeResult.Ok).document.data
    private val fixed = LocalDateTime.of(2026, 10, 14, 15, 42)
    private val october = YearMonth.of(2026, 10)
    private fun editor(data: DataFile = desktop) = TrackerEditor(data) { fixed }

    @Test
    fun `punching out closes today's session and re-derives the hours`() {
        val after = editor().punchOut(fixed)
        val day = after.months.getValue("2026-10")
        assertEquals(listOf(TimeEntry("08:57", "12:31"), TimeEntry("13:15", "15:42")), day.timeEntries!!.getValue("2026-9-14"))
        // 3h34m + 2h27m = 6h01m; 6h on the clock triggers the 30 min break, the 44 min lunch covers it.
        assertEquals(361 / 60.0, day.dailyHours.getValue("2026-9-14"), 1e-9)
        assertEquals("Logged 2 sessions on 2026-9-14", after.activityLog.first().message)
        assertEquals("2026-10-14T14:42:00.000Z".length, after.activityLog.first().timestamp.length)
    }

    @Test
    fun `punching in starts a session and refuses a second one`() {
        val out = editor().punchOut(fixed)
        val back = editor(out).punchIn(fixed.plusMinutes(5))
        assertEquals(TimeEntry("15:47", null), back.months.getValue("2026-10").timeEntries!!.getValue("2026-9-14").last())
        assertEquals(back, editor(back).punchIn(fixed.plusMinutes(10)))
    }

    @Test
    fun `a punch within the same minute isn't a session`() {
        val start = editor().punchOut(fixed)
        val inAndOut = editor(editor(start).punchIn(fixed)).punchOut(fixed)
        assertEquals(2, inAndOut.months.getValue("2026-10").timeEntries!!.getValue("2026-9-14").size)
    }

    @Test
    fun `punching in on a new period creates its month with carried-forward pay`() {
        val newPeriod = LocalDateTime.of(2026, 10, 22, 9, 0)
        val clean = editor().punchOut(fixed)
        val after = TrackerEditor(clean) { newPeriod }.punchIn(newPeriod)
        val november = after.months.getValue("2026-11")
        assertEquals(TimeEntry("09:00", null), november.timeEntries!!.getValue("2026-9-22").single())
        assertEquals(24.0, november.hourlyRate, 0.0)
        assertEquals(1.16, november.conversionRate, 0.0)
        assertEquals(listOf("mon", "tue", "wed", "thu", "fri"), november.selectedDays)
        assertTrue("2026-11" in after.savedMonths)
    }

    @Test
    fun `marks and clears exceptions on workdays only`() {
        val marked = editor().setDayException(october, LocalDate.of(2026, 10, 15), DayException.Vacation)
        assertEquals("vacation", marked.months.getValue("2026-10").exceptions["2026-9-15"])
        val cleared = editor(marked).setDayException(october, LocalDate.of(2026, 10, 15), DayException.Vacation)
        assertNull(cleared.months.getValue("2026-10").exceptions["2026-9-15"])
        // Saturday: not a workday, nothing changes.
        assertEquals(desktop, editor().setDayException(october, LocalDate.of(2026, 10, 10), DayException.Sick))
    }

    @Test
    fun `quick entry sets sessions or a total, and undo restores the day`() {
        val date = LocalDate.of(2026, 10, 13)
        val snapshot = editor().snapshot(october, "2026-9-13")
        val total = editor().applyDayInput(october, date, "7.5")!!
        assertNull(total.months.getValue("2026-10").timeEntries!!["2026-9-13"])
        assertEquals(7.5, total.months.getValue("2026-10").dailyHours.getValue("2026-9-13"), 0.0)
        assertNull(editor().applyDayInput(october, date, "nonsense"))

        val restored = editor(total).restore(snapshot)
        assertEquals(desktop.months.getValue("2026-10").timeEntries!!["2026-9-13"], restored.months.getValue("2026-10").timeEntries!!["2026-9-13"])
        assertEquals(desktop.months.getValue("2026-10").dailyHours["2026-9-13"], restored.months.getValue("2026-10").dailyHours["2026-9-13"])
    }

    @Test
    fun `sessions on a day off count as extra hours`() {
        val saturday = LocalDate.of(2026, 10, 10)
        val after = editor().setDayEntries(october, saturday, listOf(TimeEntry("10:00", "12:30")))
        assertEquals(2.5, after.months.getValue("2026-10").extraHours.getValue("2026-9-10"), 0.0)
        val reset = editor(after).resetDay(october, saturday)
        assertNull(reset.months.getValue("2026-10").extraHours["2026-9-10"])
    }

    @Test
    fun `pay settings carry forward to later saved months, the schedule doesn't`() {
        val withNovember = TrackerEditor(desktop) { fixed }.setHoursPerDay(YearMonth.of(2026, 11), 7.0)
        val rated = editor(withNovember).setHourlyRate(october, 30.0)
        assertEquals(30.0, rated.months.getValue("2026-10").hourlyRate, 0.0)
        assertEquals(30.0, rated.months.getValue("2026-11").hourlyRate, 0.0)
        assertEquals(24.0, rated.months.getValue("2026-09").hourlyRate, 0.0)
        val hours = editor(rated).setHoursPerDay(october, 6.0)
        assertEquals(7.0, hours.months.getValue("2026-11").hoursPerDay, 0.0)
    }

    @Test
    fun `turning a salary on starts from the schedule's weekly hours`() {
        val after = editor().setSalary(october, { it.copy(enabled = true) }, "Switched to a fixed salary")
        assertEquals(SalarySettings(enabled = true, weeklyHours = 40.0), after.months.getValue("2026-10").salary)
    }

    @Test
    fun `today's target follows the desktop's balanced week`() {
        val target = Tracker(desktop).todayTarget(LocalDate.of(2026, 10, 14))
        assertTrue(target.isWorkday)
        assertEquals((40 - 8 - 463 / 60.0) / 3, target.hours, 1e-9)
        assertEquals(0.0, target.carried, 0.0)
        val fixedDays = desktop.copy(settings = desktop.settings.copy(balanceWeek = false))
        assertEquals(8.0, Tracker(fixedDays).todayTarget(LocalDate.of(2026, 10, 14)).hours, 0.0)
    }

    @Test
    fun `finds the open session`() {
        val open = Tracker(desktop).openSession(october)
        assertNotNull(open)
        assertEquals("2026-9-14", open!!.key)
        assertNull(Tracker(editor().punchOut(fixed)).openSession(october))
    }
}

class MergeTest {
    private val json = Json
    private fun obj(text: String) = json.parseToJsonElement(text) as JsonObject

    @Test
    fun `combines edits to different days from both sides`() {
        val base = obj("""{"version":1,"months":{"2026-10":{"dailyHours":{"a":1}}}}""")
        val local = obj("""{"version":1,"months":{"2026-10":{"dailyHours":{"a":1,"b":2}}}}""")
        val remote = obj("""{"version":1,"months":{"2026-10":{"dailyHours":{"a":1,"c":3}}}}""")
        val merged = JsonMerge.merge(base, local, remote, localIsNewer = false)
        assertEquals(obj("""{"a":1,"b":2,"c":3}"""), merged["months"]!!.jsonObject["2026-10"]!!.jsonObject["dailyHours"])
    }

    @Test
    fun `keeps deletions made on one side`() {
        val base = obj("""{"m":{"a":1,"b":2}}""")
        val local = obj("""{"m":{"a":1}}""")
        val remote = obj("""{"m":{"a":1,"b":2,"c":3}}""")
        assertEquals(obj("""{"a":1,"c":3}"""), JsonMerge.merge(base, local, remote, false)["m"])
    }

    @Test
    fun `a day changed on both sides goes to the newer save`() {
        val base = obj("""{"t":{"d":[{"start":"09:00","end":null}]}}""")
        val local = obj("""{"t":{"d":[{"start":"09:00","end":"17:00"}]}}""")
        val remote = obj("""{"t":{"d":[{"start":"09:00","end":"16:00"}]}}""")
        assertEquals(local["t"], JsonMerge.merge(base, local, remote, localIsNewer = true)["t"])
        assertEquals(remote["t"], JsonMerge.merge(base, local, remote, localIsNewer = false)["t"])
    }

    @Test
    fun `unions the activity log and saved months`() {
        val base = obj("""{"activityLog":[],"savedMonths":["2026-09"],"months":{}}""")
        val local = obj("""{"activityLog":[{"id":"1","timestamp":"2026-10-14T10:00:00.000Z"}],"savedMonths":["2026-09","2026-10"],"months":{"2026-10":{}}}""")
        val remote = obj("""{"activityLog":[{"id":"2","timestamp":"2026-10-14T11:00:00.000Z"}],"savedMonths":["2026-09","2026-11"],"months":{"2026-11":{}}}""")
        val merged = JsonMerge.merge(base, local, remote, false)
        assertEquals(listOf("2", "1"), merged["activityLog"]!!.jsonArray.map { it.jsonObject["id"].toString().trim('"') })
        assertEquals("""["2026-09","2026-10","2026-11"]""", merged["savedMonths"].toString())
    }

    @Test
    fun `without a base, both sides' days are kept`() {
        val local = obj("""{"m":{"a":1}}""")
        val remote = obj("""{"m":{"b":2}}""")
        assertEquals(obj("""{"a":1,"b":2}"""), JsonMerge.merge(null, local, remote, false)["m"])
        assertFalse(JsonMerge.merge(null, local, remote, false).containsKey("x"))
    }

    @Test
    fun `a real desktop file merges with a phone punch and a desktop edit`() {
        val text = javaClass.getResource("/desktop-punchboard.json")!!.readText()
        val base = (DataFileCodec.decode(text) as DecodeResult.Ok).document
        val now = LocalDateTime.of(2026, 10, 14, 17, 0)
        val phone = base.withData(TrackerEditor(base.data) { now }.punchOut(now))
        val desk = base.withData(
            TrackerEditor(base.data) { now }.setDayException(YearMonth.of(2026, 10), LocalDate.of(2026, 10, 16), DayException.Vacation),
        )
        fun j(d: dev.quackbyte.punchboard.data.PunchboardDocument) = json.parseToJsonElement(DataFileCodec.encode(d)) as JsonObject
        val merged = JsonMerge.merge(j(base), j(phone), j(desk), localIsNewer = true)
        val data = (DataFileCodec.decode(merged.toString()) as DecodeResult.Ok).document.data
        val month: TrackerData = data.months.getValue("2026-10")
        assertEquals("17:00", month.timeEntries!!.getValue("2026-9-14").last().end)
        assertEquals("vacation", month.exceptions["2026-9-16"])
        assertEquals(base.data.activityLog.size + 2, data.activityLog.size)
        assertTrue("someNewerSetting" in merged["settings"]!!.jsonObject)
    }
}
