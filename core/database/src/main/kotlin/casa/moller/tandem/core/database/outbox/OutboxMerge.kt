package casa.moller.tandem.core.database.outbox

import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The outbox's merge ("coalescing") rules for Phase 2 (plan 3.5 "Ändringar på samma entitet slås
 * ihop", 5.7). Pure: `OutboxDao.enqueue` applies the decision in one transaction.
 *
 * [decide] gets the new row and the UNSENT rows (PENDING or PAUSED; never IN_FLIGHT or FAILED) of
 * the same object (same `entity` and `local_id` or `server_id`), oldest first:
 *
 * - `PATCH` after a `CREATE`: the CREATE gets the merged payload (one POST with the final values).
 * - `PATCH` after a `PATCH` (no CREATE): the newest PATCH gets the merged payload; its
 *   `base_snapshot` (what the first change started from) stays.
 * - `DELETE` after a `CREATE`: everything cancels. The CREATE, the object's other rows and every
 *   row that depends on them are removed; nothing is sent.
 * - `DELETE` after a `DELETE`: nothing new (the first DELETE stays).
 * - `DELETE` after `PATCH`/`BULK_CHECK`/`RECIPE_KEYWORDS` rows: those rows (and their
 *   dependents) are removed, the DELETE is added.
 * - `BULK_CHECK` after a `CREATE`: `checked` is merged into the CREATE.
 * - `BULK_CHECK` or `RECIPE_KEYWORDS` after the same op: merged; when the result equals the older
 *   row's `base_snapshot` (checked, then unchecked again) both cancel.
 * - Anything else is inserted as it is (`CREATE`, `VIEW_LOG`, `COOK_LOG` are never merged here).
 *
 * Payloads are JSON objects and are merged deeply (objects field by field, other values replaced).
 */
object OutboxMerge {
    /** What `OutboxDao.enqueue` does with a new row. */
    sealed interface Decision {
        /** Insert the new row as it is. */
        data object Insert : Decision

        /** Do not insert; write [payload] (and [batchKey]) into the existing row [id] instead. */
        data class Update(val id: Long, val payload: String, val batchKey: String?) : Decision

        /** Do not insert; remove the rows [ids] and every row that depends on them: nothing is sent. */
        data class Cancel(val ids: List<Long>) : Decision

        /** Remove the rows [ids] (and their dependents), then insert the new row. */
        data class Replace(val ids: List<Long>) : Decision
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun decide(new: OutboxEntity, unsent: List<OutboxEntity>): Decision {
        val create = unsent.firstOrNull { it.op == OutboxOps.CREATE }
        return when (new.op) {
            OutboxOps.PATCH -> decidePatch(new, unsent, create)
            OutboxOps.DELETE -> decideDelete(unsent, create)
            OutboxOps.BULK_CHECK, OutboxOps.RECIPE_KEYWORDS -> decideToggle(new, unsent, create)
            else -> Decision.Insert
        }
    }

    private fun decidePatch(new: OutboxEntity, unsent: List<OutboxEntity>, create: OutboxEntity?): Decision {
        val target = create ?: unsent.lastOrNull { it.op == OutboxOps.PATCH } ?: return Decision.Insert
        return Decision.Update(target.id, mergePayload(target.payload, new.payload), target.batchKey)
    }

    private fun decideDelete(unsent: List<OutboxEntity>, create: OutboxEntity?): Decision {
        val pendingDelete = unsent.lastOrNull { it.op == OutboxOps.DELETE }
        return when {
            create != null -> Decision.Cancel(unsent.map { it.id })
            // Deleted twice offline: the first DELETE stays, unchanged.
            pendingDelete != null -> Decision.Update(pendingDelete.id, pendingDelete.payload, pendingDelete.batchKey)
            unsent.isEmpty() -> Decision.Insert
            else -> Decision.Replace(unsent.map { it.id })
        }
    }

    private fun decideToggle(new: OutboxEntity, unsent: List<OutboxEntity>, create: OutboxEntity?): Decision {
        if (create != null && new.op == OutboxOps.BULK_CHECK) {
            return Decision.Update(create.id, mergePayload(create.payload, new.payload), create.batchKey)
        }
        val prior = unsent.lastOrNull { it.op == new.op } ?: return Decision.Insert
        val merged = mergePayload(prior.payload, new.payload)
        val mergedObject = parseObject(merged)
        val base = prior.baseSnapshot?.let { parseObject(it) }
        // Back to where the first change started (every base field has its old value again): both cancel.
        return if (base != null && mergeObjects(mergedObject, base) == mergedObject) {
            Decision.Cancel(listOf(prior.id))
        } else {
            Decision.Update(prior.id, merged, new.batchKey)
        }
    }

    /** [new] merged into [old] (both JSON objects): objects field by field, other values replaced. */
    fun mergePayload(old: String, new: String): String =
        json.encodeToString(JsonElement.serializer(), mergeObjects(parseObject(old), parseObject(new)))

    private fun mergeObjects(old: JsonObject, new: JsonObject): JsonObject {
        val merged = LinkedHashMap<String, JsonElement>(old)
        for ((key, value) in new) {
            val previous = merged[key]
            merged[key] = if (previous is JsonObject && value is JsonObject) mergeObjects(previous, value) else value
        }
        return JsonObject(merged)
    }

    private fun parseObject(text: String): JsonObject = json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
}
