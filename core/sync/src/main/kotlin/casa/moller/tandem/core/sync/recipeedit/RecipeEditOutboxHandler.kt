package casa.moller.tandem.core.sync.recipeedit

import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.network.api.RecipeListQuery
import casa.moller.tandem.core.network.api.list
import casa.moller.tandem.core.network.json.TandoorDates
import casa.moller.tandem.core.sync.SyncSchedule
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.drink.DrinkImageStore
import casa.moller.tandem.core.sync.engine.DetailFetcher
import casa.moller.tandem.core.sync.engine.OutboxHandler
import casa.moller.tandem.core.sync.engine.OutboxResult
import casa.moller.tandem.core.sync.remote.RemoteCaller
import casa.moller.tandem.core.sync.shopping.claimedServerIds
import java.io.File
import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody

/** Error code of a row this version cannot read (kept FAILED for "Pending changes"). */
private const val UNSUPPORTED: String = "tandem.sync.unsupported-outbox-op"

/**
 * Fas 3 (redigerare lane): the `recipe_edit` rows (docs/BYGGE.md "Fas 3 – kontrakt", table
 * "Outbox"; FACTS "Fas 3"):
 *
 * - **CREATE** `POST /api/recipe/` with the payload as it is (the whole recipe). The answer is
 *   stored as the recipe's detail under its server id; `completeCreate` then drops the rows under
 *   the temporary id. A retry looks for a lost answer first, only when the POST of an earlier
 *   attempt may have gone out (a per-row marker in `sync_state`) and only among this user's
 *   recipes of that name created since the change was made (as a drink's CREATE does).
 * - **PATCH** reads the recipe fresh first. When its `updated_at` differs from the copy the edit
 *   started from (`base_snapshot`), the edit is NOT sent: the server's version is stored and the
 *   row becomes FAILED with [RecipeEditPayloads.CONFLICT] (the detail says "Ändrad på två
 *   ställen", the editor can open the kept edit). Otherwise `PATCH /api/recipe/{id}/` with the
 *   whole recipe, and the answer is stored. A 404 drops the row and the local recipe.
 * - **DELETE** `DELETE /api/recipe/{id}/` (204); a 404 is dropped (already gone).
 * - **RECIPE_IMAGE** `PUT /api/recipe/{id}/image/` with the app-private copy, deleted afterwards.
 */
internal class RecipeEditOutboxHandler @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val details: DetailFetcher,
    private val images: DrinkImageStore,
) : OutboxHandler {
    override suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult = when (row.op) {
        OutboxOps.CREATE -> create(remote, row, payload)
        OutboxOps.PATCH -> patch(remote, row, payload)
        OutboxOps.DELETE -> delete(remote, row)
        OutboxOps.RECIPE_IMAGE -> image(remote, row, payload)
        else -> OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
    }

    private suspend fun create(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult {
        val localId = row.localId ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        val body = RecipeEditPayloads.parse(payload) ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        val name = (body["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val database = databases.get(remote.kind)
        val marker = postedKey(localId)
        if (row.attempts > 0 && database.syncStateDao().get(remote.accountId, marker) != null) {
            findLostCreate(remote, row, name)?.let { found ->
                database.syncStateDao().delete(remote.accountId, marker)
                return OutboxResult.Created(found)
            }
        }
        // From here on the recipe may reach the server even when its answer is lost: a retry looks first.
        database.syncStateDao().upsert(SyncStateEntity(accountId = remote.accountId, entity = marker))
        val created = when (val sent = caller.write(remote) { drinks.createRecipe(body) }) {
            is Outcome.Failure -> return OutboxResult.Failed(sent.error)
            is Outcome.Success -> sent.value
        }
        val recipeId = created.id() ?: return OutboxResult.Failed(TandemError.Decode("recipe-without-id"))
        database.syncStateDao().delete(remote.accountId, marker)
        details.store(remote, recipeId, created, force = true)
        return OutboxResult.Created(recipeId)
    }

    private suspend fun patch(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult {
        val recipeId = row.serverId ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        val body = RecipeEditPayloads.parse(payload) ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        val database = databases.get(remote.kind)
        val fresh = when (val read = caller.call(remote, background = true, confirmUnauthorized = true) { recipes.recipeJson(recipeId) }) {
            is Outcome.Failure -> {
                if (read.error == TandemError.NotFound) database.recipeDao().deleteRecipes(remote.accountId, listOf(recipeId))
                return OutboxResult.Failed(read.error)
            }

            is Outcome.Success -> read.value
        }
        val base = RecipeEditPayloads.baseUpdatedAt(row.baseSnapshot)
        val theirs = TandoorDates.parseInstantOrNull((fresh["updated_at"] as? JsonPrimitive)?.contentOrNull)?.toEpochMilli()
        if (base != null && theirs != null && theirs != base.toEpochMilliseconds()) {
            // Changed on the server since the edit started: the server's version wins on the device,
            // the edit is kept for the editor (FAILED with CONFLICT, never retried by itself).
            details.store(remote, recipeId, fresh, force = true)
            return OutboxResult.Failed(CONFLICT_ERROR)
        }
        val answer = when (val sent = caller.write(remote) { recipes.updateRecipe(recipeId, body) }) {
            is Outcome.Failure -> return OutboxResult.Failed(sent.error)
            is Outcome.Success -> sent.value
        }
        details.store(remote, recipeId, answer, force = true)
        return OutboxResult.Sent
    }

    private suspend fun delete(remote: ActiveSource.Remote, row: OutboxEntity): OutboxResult {
        val recipeId = row.serverId ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        return when (val sent = caller.write(remote) { recipes.deleteRecipe(recipeId) }) {
            is Outcome.Failure -> OutboxResult.Failed(sent.error)
            is Outcome.Success -> OutboxResult.Sent
        }
    }

    private suspend fun image(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult {
        val recipeId = row.serverId ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        val path = RecipeEditPayloads.imagePathOf(payload) ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        val file = File(path)
        if (!file.isFile) return OutboxResult.Dropped
        val part = MultipartBody.Part.createFormData("image", file.name, file.asRequestBody(JPEG))
        return when (val sent = caller.write(remote) { drinks.uploadImage(recipeId, part) }) {
            is Outcome.Failure -> if (sent.error == TandemError.NotFound) {
                images.delete(path)
                OutboxResult.Dropped
            } else {
                OutboxResult.Failed(sent.error)
            }

            is Outcome.Success -> {
                images.delete(path)
                // The stored detail learns the photo's URL (best effort: the next sync brings it too).
                details.fetch(remote, recipeId, isBackground = true, force = true)
                OutboxResult.Sent
            }
        }
    }

    /**
     * A recipe with this name, created by this user since the change was made, that no other
     * create of this device claimed: the answer of an earlier attempt was lost, so the retry never
     * makes a second one. The lowest such id wins (deterministic).
     */
    private suspend fun findLostCreate(remote: ActiveSource.Remote, row: OutboxEntity, name: String): Long? {
        if (name.isEmpty()) return null
        val listed = caller.call(remote, background = true) { recipes.list(RecipeListQuery(text = name, pageSize = LOST_LOOKUP_PAGE)) }
        val claimed = claimedServerIds(databases, remote, OutboxEntities.RECIPE_EDIT)
        val notBefore = row.createdAtEpochMillis - SyncSchedule.CLOCK_SKEW_TOLERANCE.inWholeMilliseconds
        val found = (listed as? Outcome.Success)?.value?.results.orEmpty().filter { recipe ->
            val createdAt = TandoorDates.parseInstantOrNull(recipe.createdAt)?.toEpochMilli()
            recipe.name == name && recipe.id != null && recipe.id !in claimed &&
                recipe.createdBy?.id == remote.account.userId && createdAt != null && createdAt >= notBefore
        }.minByOrNull { it.id ?: Long.MAX_VALUE } ?: return null
        val id = found.id ?: return null
        details.fetch(remote, id, isBackground = true, force = true)
        return id
    }

    private fun JsonObject.id(): Long? = (this["id"] as? JsonPrimitive)?.longOrNull

    /** `sync_state` key: the recipe POST of [localId] was started (its answer may be lost). */
    private fun postedKey(localId: Long): String = "$POSTED_PREFIX$localId"

    companion object {
        /** The error an edit held back by a conflict fails with (`last_error` = [RecipeEditPayloads.CONFLICT]). */
        val CONFLICT_ERROR: TandemError = TandemError.Unknown(RecipeEditPayloads.CONFLICT)
        private val JPEG = "image/jpeg".toMediaType()
        private const val LOST_LOOKUP_PAGE = 10
        private const val POSTED_PREFIX = "recipe_posted:"
    }
}
