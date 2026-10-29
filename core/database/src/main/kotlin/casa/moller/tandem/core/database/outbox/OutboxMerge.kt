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
 * - `PATCH` after a `PATCH` (no CREATE): the newest PATCH gets the merged payload and the merged
 *   `base_snapshot`: every field the older change started from keeps its value (the earliest base
 *   wins), and fields only the new change touched add their own (0.3.0 review, fix-data: otherwise
 *   the sender's field compare would overwrite another member's edit of such a field unseen).
 * - `DELETE` after a `CREATE`: everything cancels. The CREATE, the object's other rows and every
 *   row that depends on them are removed; nothing is sent.
 * - `DELETE` after a `DELETE`: nothing new (the first DELETE stays).
 * - `DELETE` after `PATCH`/`BULK_CHECK`/`RECIPE_KEYWORDS` rows: those rows (and their
 *   dependents) are removed, the DELETE is added.
 * - `BULK_CHECK` after a `CREATE`: `checked` is merged into the CREATE.
 * - `BULK_CHECK` or `RECIPE_KEYWORDS` after the same op: merged; when the result equals the older
 *   row's `base_snapshot` (checked, then unchecked again) both cancel.
 * - Fas S: `BATCH_ON_HAND` merges like `BULK_CHECK` (never into a CREATE), `RECIPE_IMAGE` after an
 *   unsent `RECIPE_IMAGE` replaces its payload (the newest photo is sent once).
 * - Anything else is inserted as it is (`CREATE`, `VIEW_LOG`, `COOK_LOG` are never merged here).
 *
 * **A CREATE that was attempted before** (`attempts > 0`: a timeout, a 5xx or a process death) may
 * already exist on the server, so nothing merges into it or cancels it (0.3.0 review, fix-data): a
 * later PATCH, check or DELETE becomes its own row that waits for the CREATE and is sent by the
 * server id once the CREATE is settled (a DELETE then also removes the copy a lost answer left on
 * the server). Its payload stays as first sent, so the retry's lost-answer lookup still matches.
 *
 * Payloads are JSON objects and are merged at the top level only: a field of the new row replaces
 * the old value as a whole. A nested object (`food`, `unit`, `meal_type`, `recipe`) is never merged
 * field by field, so a CREATE's `food {name: "mjölk", id: 5}` edited to `food {name: "grädde"}`
 * never becomes `{name: "grädde", id: 5}` (0.3.0 review, fix-data).
 */
object OutboxMerge {
    /** What `OutboxDao.enqueue` does with a new row. */
    sealed interface Decision {
        /** Insert the new row as it is. */
        data object Insert : Decision

        /**
         * Do not insert; write [payload] (and [batchKey]) into the existing row [id] instead. A
         * non-null [baseSnapshot] replaces the row's `base_snapshot` (PATCH into PATCH); null keeps it.
         */
        data class Update(val id: Long, val payload: String, val batchKey: String?, val baseSnapshot: String? = null) : Decision

        /** Do not insert; remove the rows [ids] and every row that depends on them: nothing is sent. */
        data class Cancel(val ids: List<Long>) : Decision

        /** Remove the rows [ids] (and their dependents), then insert the new row. */
        data class Replace(val ids: List<Long>) : Decision
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Ops whose later value replaces the earlier one (Fas S: on-hand in batch, and a newer photo replaces an unsent one). */
    private val toggleOps = setOf(OutboxOps.BULK_CHECK, OutboxOps.RECIPE_KEYWORDS, OutboxOps.BATCH_ON_HAND, OutboxOps.RECIPE_IMAGE)

    fun decide(new: OutboxEntity, unsent: List<OutboxEntity>): Decision {
        val create = unsent.firstOrNull { it.op == OutboxOps.CREATE && it.attempts == 0 }
        return when (new.op) {
            OutboxOps.PATCH -> decidePatch(new, unsent, create)
            OutboxOps.DELETE -> decideDelete(unsent, create, attemptedCreateOf(unsent))
            in toggleOps -> decideToggle(new, unsent, create)
            else -> Decision.Insert
        }
    }

    /**
     * The object's CREATE that was attempted before (`attempts > 0`) and is waiting again: it may
     * have reached the server, so later rows of the object wait for it instead of merging.
     */
    fun attemptedCreateOf(unsent: List<OutboxEntity>): OutboxEntity? = unsent.firstOrNull { it.op == OutboxOps.CREATE && it.attempts > 0 }

    private fun decidePatch(new: OutboxEntity, unsent: List<OutboxEntity>, create: OutboxEntity?): Decision {
        if (create != null) return Decision.Update(create.id, mergePayload(create.payload, new.payload), create.batchKey)
        val target = unsent.lastOrNull { it.op == OutboxOps.PATCH } ?: return Decision.Insert
        return Decision.Update(
            target.id,
            mergePayload(target.payload, new.payload),
            target.batchKey,
            baseSnapshot = mergeBase(target.baseSnapshot, new.baseSnapshot),
        )
    }

    private fun decideDelete(unsent: List<OutboxEntity>, create: OutboxEntity?, attempted: OutboxEntity?): Decision {
        val pendingDelete = unsent.lastOrNull { it.op == OutboxOps.DELETE }
        // Deleted twice offline: the first DELETE stays, unchanged.
        val others = unsent.filter { it.id != attempted?.id }
        return when {
            create != null -> Decision.Cancel(unsent.map { it.id })

            pendingDelete != null -> Decision.Update(pendingDelete.id, pendingDelete.payload, pendingDelete.batchKey)

            others.isEmpty() -> Decision.Insert

            // A CREATE that may have reached the server stays; the DELETE follows it by server id.
            else -> Decision.Replace(others.map { it.id })
        }
    }

    /** The older base with the fields only the newer one has added (the earliest start value wins). */
    private fun mergeBase(old: String?, new: String?): String? {
        if (new == null) return old
        if (old == null) return new
        val older = parseObject(old)
        val merged = LinkedHashMap<String, JsonElement>(older)
        for ((key, value) in parseObject(new)) {
            if (key !in older) merged[key] = value
        }
        return json.encodeToString(JsonElement.serializer(), JsonObject(merged))
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
            // Fas S: the bases merge too (the earliest value of each field wins), so the toggles of
            // different fields in one row ("Vill laga" and a pairing) never cancel each other.
            Decision.Update(prior.id, merged, new.batchKey, baseSnapshot = mergeBase(prior.baseSnapshot, new.baseSnapshot))
        }
    }

    /** [new] merged into [old] (both JSON objects) at the top level: each field of [new] replaces the old value as a whole. */
    fun mergePayload(old: String, new: String): String =
        json.encodeToString(JsonElement.serializer(), mergeObjects(parseObject(old), parseObject(new)))

    private fun mergeObjects(old: JsonObject, new: JsonObject): JsonObject {
        val merged = LinkedHashMap<String, JsonElement>(old)
        merged.putAll(new)
        return JsonObject(merged)
    }

    private fun parseObject(text: String): JsonObject = json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
}
