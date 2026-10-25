package casa.moller.tandem.core.database.outbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Temporary ids inside outbox payloads (plan 5.7 "id-omskrivning"). An object created offline has
 * a negative id; a later row that points at it (an entry in an offline list recipe, a meal with a
 * new meal type, an entry in a new book) carries that negative id in its payload, lists the field in
 * `OutboxEntity.refs` (`{"list_recipe": "shopping_list_recipe"}`) and `depends_on` the CREATE.
 * Just before sending, the sender replaces every negative id with its server id from `id_mapping`
 * ([resolve]). Pure; positive ids and fields not listed stay as they are.
 */
object OutboxRefs {
    private val json = Json { ignoreUnknownKeys = true }

    /** `{"field": "entity"}` for [OutboxEntity.refs][casa.moller.tandem.core.database.entity.OutboxEntity.refs]. */
    fun encode(refs: Map<String, String>): String? {
        if (refs.isEmpty()) return null
        return json.encodeToString(JsonElement.serializer(), JsonObject(refs.mapValues { (_, entity) -> JsonPrimitive(entity) }))
    }

    /** The references of a row, `field or dotted path -> entity`. */
    fun decode(refs: String?): Map<String, String> {
        val obj = refs?.let { json.parseToJsonElement(it) } as? JsonObject ?: return emptyMap()
        return obj.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.content?.let { key to it } }.toMap()
    }

    /**
     * [payload] with every negative id in the fields of [refs] replaced through [lookup]
     * (`entity, temporary id -> server id`). Returns null when a temporary id has no server id
     * yet: the row is not ready (its CREATE has not been sent) and must wait.
     */
    fun resolve(payload: String, refs: String?, lookup: (entity: String, localId: Long) -> Long?): String? {
        val paths = decode(refs)
        if (paths.isEmpty()) return payload
        var root = json.parseToJsonElement(payload) as? JsonObject ?: return payload
        for ((path, entity) in paths) {
            root = replaceAt(root, path.split('.'), entity, lookup) ?: return null
        }
        return json.encodeToString(JsonElement.serializer(), root)
    }

    private fun replaceAt(obj: JsonObject, path: List<String>, entity: String, lookup: (String, Long) -> Long?): JsonObject? {
        val key = path.first()
        val value = obj[key] ?: return obj
        val replaced: JsonElement = if (path.size > 1) {
            (value as? JsonObject)?.let { replaceAt(it, path.drop(1), entity, lookup) ?: return null } ?: value
        } else {
            resolveValue(value, entity, lookup) ?: return null
        }
        return JsonObject(obj + (key to replaced))
    }

    private fun resolveValue(value: JsonElement, entity: String, lookup: (String, Long) -> Long?): JsonElement? = when (value) {
        is JsonArray -> JsonArray(value.map { resolveValue(it, entity, lookup) ?: return null })

        is JsonPrimitive -> {
            val id = value.longOrNull
            if (id == null || id >= 0) value else lookup(entity, id)?.let { JsonPrimitive(it) }
        }

        else -> value
    }
}
