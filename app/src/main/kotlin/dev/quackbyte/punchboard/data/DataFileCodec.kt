package dev.quackbyte.punchboard.data

import dev.quackbyte.punchboard.model.DataFile
import dev.quackbyte.punchboard.model.SalarySettings
import dev.quackbyte.punchboard.model.TrackerData
import dev.quackbyte.punchboard.model.TrackerSettings
import dev.quackbyte.punchboard.model.TrackerUiState
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * A data file plus the JSON it was read from. Keeping the original lets a
 * save carry over fields this version doesn't know about yet (a newer
 * desktop may add settings), the same courtesy the desktop extends.
 */
class PunchboardDocument internal constructor(
    val data: DataFile,
    internal val original: JsonObject?,
) {
    fun withData(data: DataFile) = PunchboardDocument(data, original)

    companion object {
        fun new(data: DataFile = DataFile()) = PunchboardDocument(data, null)
    }
}

sealed interface DecodeResult {
    data class Ok(val document: PunchboardDocument) : DecodeResult
    data class Error(val reason: DecodeError, val detail: String? = null) : DecodeResult
}

enum class DecodeError {
    /** Not JSON at all (or not a JSON object). */
    NotJson,

    /** JSON, but not shaped like a Punchboard data file. */
    NotPunchboard,

    /** A Punchboard file from a newer, incompatible format version. */
    UnsupportedVersion,
}

object DataFileCodec {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        coerceInputValues = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun decode(text: String): DecodeResult {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return DecodeResult.Error(DecodeError.NotJson)

        // Same gate as the desktop's parseDataFile: version 1 with a months object.
        val version = (root["version"] as? JsonPrimitive)?.intOrNull
        if (root["months"] !is JsonObject) return DecodeResult.Error(DecodeError.NotPunchboard)
        if (version != DataFile.SUPPORTED_VERSION) {
            return if (version != null) {
                DecodeResult.Error(DecodeError.UnsupportedVersion, "version $version")
            } else {
                DecodeResult.Error(DecodeError.NotPunchboard)
            }
        }

        return try {
            DecodeResult.Ok(PunchboardDocument(json.decodeFromJsonElement(DataFile.serializer(), root), root))
        } catch (error: SerializationException) {
            DecodeResult.Error(DecodeError.NotPunchboard, error.message)
        } catch (error: IllegalArgumentException) {
            DecodeResult.Error(DecodeError.NotPunchboard, error.message)
        }
    }

    fun encode(document: PunchboardDocument): String {
        val encoded = json.encodeToJsonElement(DataFile.serializer(), document.data).jsonObject
        val original = document.original ?: return json.encodeToString(JsonObject.serializer(), encoded)
        return json.encodeToString(JsonObject.serializer(), preserveUnknown(encoded, original))
    }

    /**
     * Copies keys this version doesn't model back into the fixed-shape
     * objects (the root, settings, uiState, each month and its salary).
     * Maps such as `months` or `dailyHours` are never merged, so deleting
     * a day or a month really deletes it.
     */
    private fun preserveUnknown(encoded: JsonObject, original: JsonObject): JsonObject {
        val result = withExtras(encoded, original, DataFile.serializer()).toMutableMap()

        (original["settings"] as? JsonObject)?.let { old ->
            result["settings"] = withExtras(result["settings"].obj(), old, TrackerSettings.serializer())
        }
        (original["uiState"] as? JsonObject)?.let { old ->
            result["uiState"] = withExtras(result["uiState"].obj(), old, TrackerUiState.serializer())
        }
        val oldMonths = original["months"] as? JsonObject
        val months = result["months"].obj()
        if (oldMonths != null) {
            result["months"] = JsonObject(
                months.mapValues { (key, month) ->
                    val oldMonth = oldMonths[key] as? JsonObject ?: return@mapValues month
                    val merged = withExtras(month.obj(), oldMonth, TrackerData.serializer()).toMutableMap()
                    val oldSalary = oldMonth["salary"] as? JsonObject
                    val salary = merged["salary"] as? JsonObject
                    if (oldSalary != null && salary != null) {
                        merged["salary"] = withExtras(salary, oldSalary, SalarySettings.serializer())
                    }
                    JsonObject(merged)
                },
            )
        }
        return JsonObject(result)
    }

    private fun withExtras(encoded: JsonObject, original: JsonObject, serializer: KSerializer<*>): JsonObject {
        val known = (0 until serializer.descriptor.elementsCount).map(serializer.descriptor::getElementName).toSet()
        val extras = original.filterKeys { it !in known && it !in encoded }
        return if (extras.isEmpty()) encoded else JsonObject(encoded + extras)
    }

    private fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
}
