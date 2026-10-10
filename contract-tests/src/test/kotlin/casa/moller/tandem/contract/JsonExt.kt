package casa.moller.tandem.contract

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Null-tolerant field access for the Fas 1 contract tests (raw JSON, no DTOs). */
internal fun JsonObject.stringAt(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

internal fun JsonObject.longAt(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.doubleAt(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.boolAt(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.objAt(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arrAt(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

internal fun JsonElement?.objects(): List<JsonObject> = (this as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
