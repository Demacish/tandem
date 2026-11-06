package casa.moller.tandem.core.sync.recipeedit

import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.dao.OutboxDao
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxOps
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject

// Fas 4 review (fix-data): a recipe created in the app has a temporary (negative) id until its
// CREATE is answered, and an open screen may hold that id longer (docs/BYGGE.md "Fas 4-granskningen").
// Every writer that names a recipe goes through these: the id is followed to the server id first,
// and a row that still names a temporary id lists it in `refs` and waits for the CREATE.

/** [id], or the server id it got. */
internal suspend fun TandemDatabase.currentRecipeId(accountId: String, id: Long): Long =
    idRewriteDao().current(accountId, OutboxEntities.RECIPE_EDIT, id)

/** [id] as [currentRecipeId], following a CREATE answered while a screen is open. */
internal fun TandemDatabase.observeCurrentRecipeId(accountId: String, id: Long): Flow<Long> = if (id >= 0) {
    flowOf(id)
} else {
    idRewriteDao().observeResolved(accountId, OutboxEntities.RECIPE_EDIT, id).map { it ?: id }.distinctUntilChanged()
}

/** The outbox row a row naming recipe [recipeId] must wait for: its CREATE while it has no server id. */
internal suspend fun TandemDatabase.recipeCreateRow(accountId: String, recipeId: Long?): Long? =
    recipeId?.takeIf { it < 0 }?.let { outboxDao().getCreate(accountId, OutboxEntities.RECIPE_EDIT, it)?.id }

/**
 * This device's own write moved recipe [recipeId] from [before] to [after] on the server (both as
 * the server's JSON): the edits queued for it that started from [before] start from [after] now,
 * so nobody is told it changed in two places. An edit that started anywhere else keeps its base
 * and is held back as it should. [keepKeywords] (a "Vill laga" or a pairing, which never reach the
 * edit's whole keyword list otherwise): the change of the recipe's keywords goes into the edit too.
 */
internal suspend fun OutboxDao.rebaseQueuedEdits(
    accountId: String,
    recipeId: Long,
    before: JsonObject,
    after: JsonObject,
    keepKeywords: Boolean,
) {
    for (row in getUnsentFor(accountId, OutboxEntities.RECIPE_EDIT, localId = null, serverId = recipeId)) {
        if (row.op != OutboxOps.PATCH) continue
        val base = RecipeEditPayloads.rebased(row.baseSnapshot, before, after) ?: continue
        val payload = if (keepKeywords) RecipeEditPayloads.withKeywordChange(row.payload, before, after) else row.payload
        updateMerged(row.id, payload, row.batchKey, row.refs, row.dependsOn, row.createdAtEpochMillis, base)
    }
}
