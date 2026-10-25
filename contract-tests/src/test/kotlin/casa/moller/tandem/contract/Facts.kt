package casa.moller.tandem.contract

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Observed facts of one contract run, written to `observed-facts.json` next to the recordings.
 * api-schema/fixtures/<version>/FACTS.md summarises this file for the app lanes.
 */
internal class Facts {
    private val facts = LinkedHashMap<String, JsonElement>()

    operator fun set(key: String, value: Any?) {
        facts[key] = toJson(value)
    }

    /** The recorded fact, or null when [key] was never recorded. */
    operator fun get(key: String): JsonElement? = facts[key]

    fun write(file: File, sanitizer: Sanitizer) {
        file.parentFile.mkdirs()
        val json = sanitizer.sanitize(JsonObject(facts))
        file.writeText(TandoorHttp.PrettyJson.encodeToString(JsonElement.serializer(), json) + "\n")
    }

    companion object {
        fun toJson(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
            is Iterable<*> -> JsonArray(value.map { toJson(it) })
            else -> JsonPrimitive(value.toString())
        }

        /** JSON type of every top-level field, e.g. `{"id": "number", "name": "string"}`. */
        fun fieldTypes(obj: JsonObject): Map<String, String> = obj.mapValues { (_, value) -> typeOf(value) }

        fun typeOf(value: JsonElement): String = when (value) {
            is JsonObject -> "object"

            is JsonArray -> "array"

            JsonNull -> "null"

            is JsonPrimitive -> when {
                value.isString -> "string"
                value.content == "true" || value.content == "false" -> "boolean"
                else -> "number"
            }
        }
    }
}
