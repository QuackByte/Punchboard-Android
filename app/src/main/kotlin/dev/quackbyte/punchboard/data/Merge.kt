package dev.quackbyte.punchboard.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Three-way merge of two data files that both started from [base] (the
 * contents last synced). Works on the raw JSON so fields this version
 * doesn't know about merge too.
 *
 * Objects merge key by key, so edits to different days, months or settings
 * combine. A value both sides changed differently goes to the side saved
 * last ([localIsNewer]). A day's sessions are one value: two devices
 * editing the same day don't interleave sessions.
 *
 * `activityLog` and `savedMonths` are unions; the activity log is kept
 * newest first and capped like the desktop's.
 */
object JsonMerge {
    private const val ACTIVITY_LIMIT = 200

    fun merge(base: JsonObject?, local: JsonObject, remote: JsonObject, localIsNewer: Boolean): JsonObject {
        val merged = mergeObject(base ?: JsonObject(emptyMap()), local, remote, localIsNewer).toMutableMap()

        val logs = listOfNotNull(local["activityLog"] as? JsonArray, remote["activityLog"] as? JsonArray)
        if (logs.isNotEmpty()) {
            val byId = linkedMapOf<String, JsonElement>()
            logs.flatten().forEach { entry ->
                val id = ((entry as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull ?: return@forEach
                byId.putIfAbsent(id, entry)
            }
            merged["activityLog"] = JsonArray(
                byId.values.sortedByDescending { ((it as JsonObject)["timestamp"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
                    .take(ACTIVITY_LIMIT),
            )
        }

        val months = listOfNotNull(local["savedMonths"] as? JsonArray, remote["savedMonths"] as? JsonArray)
        if (months.isNotEmpty()) {
            val present = (merged["months"] as? JsonObject)?.keys.orEmpty()
            merged["savedMonths"] = JsonArray(
                months.flatten().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .plus(present)
                    .distinct()
                    .sorted()
                    .map(::JsonPrimitive),
            )
        }
        return JsonObject(merged)
    }

    private fun mergeObject(base: JsonObject, local: JsonObject, remote: JsonObject, localIsNewer: Boolean): JsonObject {
        val result = linkedMapOf<String, JsonElement>()
        val keys = LinkedHashSet<String>().apply {
            addAll(remote.keys)
            addAll(local.keys)
        }
        for (key in keys) {
            mergeValue(base[key], local[key], remote[key], localIsNewer)?.let { result[key] = it }
        }
        return JsonObject(result)
    }

    /** Null means "absent". */
    private fun mergeValue(base: JsonElement?, local: JsonElement?, remote: JsonElement?, localIsNewer: Boolean): JsonElement? {
        if (local == remote) return local
        if (local == base) return remote
        if (remote == base) return local
        if (local is JsonObject && remote is JsonObject) {
            return mergeObject(base as? JsonObject ?: JsonObject(emptyMap()), local, remote, localIsNewer)
        }
        return if (localIsNewer) local else remote
    }
}
