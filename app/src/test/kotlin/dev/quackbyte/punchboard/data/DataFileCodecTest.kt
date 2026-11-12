package dev.quackbyte.punchboard.data

import dev.quackbyte.punchboard.model.TimeEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class DataFileCodecTest {
    private val desktopFile = javaClass.getResource("/desktop-punchboard.json")!!.readText()

    private fun decodeOk(text: String) = (DataFileCodec.decode(text) as DecodeResult.Ok).document

    @Test
    fun `reads a desktop file`() {
        val data = decodeOk(desktopFile).data
        assertEquals(21, data.settings.payslipStartDay)
        assertEquals(1.5, data.settings.overtimeMultiplier)
        val october = data.months.getValue("2026-10")
        assertEquals(
            listOf(TimeEntry("08:57", "12:31"), TimeEntry("13:15", null)),
            october.timeEntries!!.getValue("2026-9-14"),
        )
        assertEquals("On call", october.timeEntries.getValue("2026-9-13")[1].note)
        assertNull(data.months.getValue("2026-09").timeEntries)
        assertNull(data.months.getValue("2026-09").salary)
    }

    @Test
    fun `writes running sessions with an explicit null end`() {
        val out = Json.parseToJsonElement(DataFileCodec.encode(decodeOk(desktopFile))).jsonObject
        val today = out["months"]!!.jsonObject["2026-10"]!!.jsonObject["timeEntries"]!!
            .jsonObject["2026-9-14"]!!.jsonArray
        val running = today[1].jsonObject
        assertTrue("end must be present", "end" in running)
        assertEquals(JsonNull, running["end"])
        assertFalse("absent notes stay absent", "note" in running)
    }

    @Test
    fun `round trip keeps fields this version does not know`() {
        val out = Json.parseToJsonElement(DataFileCodec.encode(decodeOk(desktopFile))).jsonObject
        assertEquals("keep me", out["topLevelFromTheFuture"]!!.jsonPrimitive.content)
        assertTrue("someNewerSetting" in out["settings"]!!.jsonObject)
        assertTrue("futureUiFlag" in out["uiState"]!!.jsonObject)
        val october = out["months"]!!.jsonObject["2026-10"]!!.jsonObject
        assertEquals(42, october["futureMonthField"]!!.jsonPrimitive.content.toInt())
        assertEquals("kept", october["salary"]!!.jsonObject["futureSalaryField"]!!.jsonPrimitive.content)
    }

    @Test
    fun `round trip is semantically lossless`() {
        val original = Json.parseToJsonElement(desktopFile)
        val roundTripped = Json.parseToJsonElement(DataFileCodec.encode(decodeOk(desktopFile)))
        assertEquals(normalizeNumbers(original), normalizeNumbers(roundTripped))
    }

    @Test
    fun `deleting a day really deletes it`() {
        val document = decodeOk(desktopFile)
        val october = document.data.months.getValue("2026-10")
        val edited = document.withData(
            document.data.copy(
                months = document.data.months + ("2026-10" to october.copy(dailyHours = october.dailyHours - "2026-9-13")),
            ),
        )
        val out = Json.parseToJsonElement(DataFileCodec.encode(edited)).jsonObject
        val daily = out["months"]!!.jsonObject["2026-10"]!!.jsonObject["dailyHours"]!!.jsonObject
        assertFalse("2026-9-13" in daily)
    }

    @Test
    fun `new files have version 1 and decode again`() {
        val text = DataFileCodec.encode(PunchboardDocument.new())
        assertEquals(1, Json.parseToJsonElement(text).jsonObject["version"]!!.jsonPrimitive.content.toInt())
        assertTrue(DataFileCodec.decode(text) is DecodeResult.Ok)
    }

    @Test
    fun `rejects what the desktop rejects`() {
        assertEquals(DecodeError.NotJson, (DataFileCodec.decode("not json") as DecodeResult.Error).reason)
        assertEquals(DecodeError.NotJson, (DataFileCodec.decode("[1,2]") as DecodeResult.Error).reason)
        assertEquals(DecodeError.NotPunchboard, (DataFileCodec.decode("""{"version":1}""") as DecodeResult.Error).reason)
        assertEquals(
            DecodeError.UnsupportedVersion,
            (DataFileCodec.decode("""{"version":2,"months":{}}""") as DecodeResult.Error).reason,
        )
    }

    @Test
    fun `summarises an import`() {
        val result = ImportResult.of(desktopFile) as ImportResult.Ready
        val summary = result.summary
        assertEquals(2, summary.monthCount)
        assertEquals(YearMonth.of(2026, 9), summary.firstMonth)
        assertEquals(YearMonth.of(2026, 10), summary.lastMonth)
        // 2026-7-24, 2026-7-25, 2026-9-13, 2026-9-14, 2026-9-3
        assertEquals(5, summary.daysLogged)
        assertEquals(5, summary.sessions)
        assertEquals(2, summary.vacationDays)
        assertEquals(1, summary.sickDays)
        assertTrue(summary.hasRunningSession)
        assertEquals("2026-10-14T14:40:12.345Z", summary.savedAt)
    }

    @Test
    fun `import failures map to user-facing reasons`() {
        assertEquals(ImportResult.Failed(ImportFailure.NotPunchboard), ImportResult.of("{}"))
        assertEquals(ImportResult.Failed(ImportFailure.NewerVersion), ImportResult.of("""{"version":3,"months":{}}"""))
    }

    /** 8 and 8.0 are the same number to JavaScript; compare them as such. */
    private fun normalizeNumbers(element: kotlinx.serialization.json.JsonElement): Any? = when (element) {
        is JsonObject -> element.mapValues { normalizeNumbers(it.value) }
        is kotlinx.serialization.json.JsonArray -> element.map(::normalizeNumbers)
        is JsonNull -> null
        is kotlinx.serialization.json.JsonPrimitive ->
            if (element.isString) element.content else element.content.toDoubleOrNull() ?: element.content
    }
}
