package casa.moller.tandem.core.sync.recipeedit

import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.network.api.RecipeListQuery
import casa.moller.tandem.core.network.api.RecipeSortOrder
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

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
 *   recipes of that name created since the change was made (as a drink's CREATE does). A lookup
 *   that fails holds the row back (Fas 4 review, fix-data): no answer is not "nothing found".
 * - **PATCH** reads the recipe fresh first. When its `updated_at` differs from the copy the edit
 *   started from (`base_snapshot`), the edit is NOT sent: the server's version is stored and the
 *   row becomes FAILED with [RecipeEditPayloads.CONFLICT] (the detail says "Ändrad på två
 *   ställen", the editor can open the kept edit). Otherwise `PATCH /api/recipe/{id}/` with the
 *   whole recipe, and the answer is stored. A 404 drops the row and the local recipe.
 *   Fas 4 review (fix-data): this device's own writes are no conflict. A server that already says
 *   what the edit says (a PATCH whose answer was lost) is the edit done, and after an answer the
 *   edits queued behind it start from it (`rebaseQueuedEdits`).
 * - **Keywords** go with the server's current name, on a CREATE too: the fresh read gives it for
 *   the keywords the recipe has, `GET /api/keyword/{id}/` for the rest (Fas 4 review, fix-data: the
 *   device's cached name would rename the keyword for everyone). A keyword deleted on the server
 *   goes by name; a failed read holds the row back.
 * - **A value's property type** goes with the server's current name for the same reason.
 * - **A step's file** chosen in the editor stands in the row by its path until it is uploaded,
 *   right before the POST or PATCH and after the conflict check ([withUploadedFiles]).
 * - **DELETE** `DELETE /api/recipe/{id}/` (204); a 404 is dropped (already gone). Either way a copy
 *   stored meanwhile goes.
 * - **RECIPE_IMAGE** `PUT /api/recipe/{id}/image/` with the app-private copy, deleted afterwards, or
 *   with `image_url` (an import's picture on the web, Fas 3 import lane) for the server to download,
 *   or with an empty `image_url` to remove the picture (`{"remove": true}`, Fas 3's leftovers).
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
        lostAnswer(remote, row, name, marker)?.let { return it }
        val uploaded = when (val files = withUploadedFiles(remote, row, body)) {
            is Outcome.Failure -> return OutboxResult.Failed(files.error)
            is Outcome.Success -> files.value
        }
        val sendBody = when (val named = withServerNames(remote, uploaded, fresh = null)) {
            is Outcome.Failure -> return OutboxResult.Failed(named.error)
            is Outcome.Success -> RecipeEditPayloads.forSending(named.value)
        }
        // From here on the recipe may reach the server even when its answer is lost: a retry looks first.
        database.syncStateDao().upsert(SyncStateEntity(accountId = remote.accountId, entity = marker))
        val created = when (val sent = caller.write(remote) { drinks.createRecipe(sendBody) }) {
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
        if (changedSinceBase(row, fresh)) {
            // Changed on the server since the edit started: the server's version wins on the device,
            // the edit is kept for the editor (FAILED with CONFLICT, never retried by itself), unless
            // the server already says what the edit says: then that change was the edit itself.
            details.store(remote, recipeId, fresh, force = true)
            return if (RecipeEditPayloads.sameContent(fresh, body)) OutboxResult.Sent else OutboxResult.Failed(CONFLICT_ERROR)
        }
        val uploaded = when (val files = withUploadedFiles(remote, row, body)) {
            is Outcome.Failure -> return OutboxResult.Failed(files.error)
            is Outcome.Success -> files.value
        }
        // A known keyword goes with the name the server has for it right now: another name would rename it.
        val sendBody = when (val named = withServerNames(remote, uploaded, fresh)) {
            is Outcome.Failure -> return OutboxResult.Failed(named.error)
            is Outcome.Success -> RecipeEditPayloads.forSending(named.value)
        }
        val answer = when (val sent = caller.write(remote) { recipes.updateRecipe(recipeId, sendBody) }) {
            is Outcome.Failure -> return OutboxResult.Failed(sent.error)
            is Outcome.Success -> sent.value
        }
        details.store(remote, recipeId, answer, force = true)
        database.outboxDao().rebaseQueuedEdits(remote.accountId, recipeId, before = fresh, after = answer, keepKeywords = false)
        return OutboxResult.Sent
    }

    /**
     * Fas 4 (#22): uploads the files chosen for [body]'s steps (`POST /api/user-file/`, FACTS "Fas 1
     * m"), one at a time. Each one that is up goes into the row at once and its copy is deleted, so
     * a retry after a failure sends only what is left. A copy that is gone (the account's data was
     * cleared) leaves the step's file as the server has it. A refused file (a space's file limit is a
     * 400) fails the row, which waits in "Väntande ändringar" with its copy.
     */
    private suspend fun withUploadedFiles(remote: ActiveSource.Remote, row: OutboxEntity, body: JsonObject): Outcome<JsonObject> {
        var current = body
        (body["steps"] as? JsonArray).orEmpty().forEachIndexed { index, step ->
            val path = RecipeEditPayloads.filePathOf(step) ?: return@forEachIndexed
            val file = File(path)
            current = if (!file.isFile) {
                RecipeEditPayloads.withoutFile(current, index)
            } else {
                val name = RecipeEditPayloads.fileNameOf(step).toRequestBody(TEXT)
                val part = MultipartBody.Part.createFormData("file", file.name, file.asRequestBody(BINARY))
                val fileId = when (val sent = caller.write(remote) { recipes.uploadUserFile(name, part) }) {
                    is Outcome.Failure -> return sent
                    is Outcome.Success -> sent.value.id ?: return Outcome.Failure(TandemError.Decode("user-file-without-id"))
                }
                RecipeEditPayloads.withUploadedFile(current, index, fileId)
            }
            databases.get(remote.kind).outboxDao().updatePayload(row.id, current.toString())
            images.delete(path)
        }
        return Outcome.Success(current)
    }

    /** Whether [fresh] moved on from the copy the edit started from (Fas 3's leftovers: a change of only a step leaves `updated_at`). */
    private fun changedSinceBase(row: OutboxEntity, fresh: JsonObject): Boolean {
        val base = RecipeEditPayloads.baseUpdatedAt(row.baseSnapshot)
        val theirs = RecipeEditPayloads.updatedAtOf(fresh)
        if (base != null && theirs != null && theirs != base) return true
        val baseSteps = RecipeEditPayloads.baseStepsFingerprint(row.baseSnapshot)
        return baseSteps != null && baseSteps != RecipeEditPayloads.stepsFingerprint(fresh)
    }

    private suspend fun delete(remote: ActiveSource.Remote, row: OutboxEntity): OutboxResult {
        val recipeId = row.serverId ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        val sent = caller.write(remote) { recipes.deleteRecipe(recipeId) }
        // Fas 4 review (fix-data): an index read before this DELETE may have stored the recipe again.
        if (sent is Outcome.Success || (sent as? Outcome.Failure)?.error == TandemError.NotFound) {
            databases.get(remote.kind).recipeDao().deleteRecipes(remote.accountId, listOf(recipeId))
        }
        return when (sent) {
            is Outcome.Failure -> OutboxResult.Failed(sent.error)
            is Outcome.Success -> OutboxResult.Sent
        }
    }

    private suspend fun image(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult {
        val recipeId = row.serverId ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        if (RecipeEditPayloads.isImageRemoval(payload)) return removeImage(remote, recipeId)
        RecipeEditPayloads.imageUrlOf(payload)?.let { return imageFromWeb(remote, recipeId, it) }
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
     * Fas 3's leftovers ("Ta bort bilden"): the image endpoint with an EMPTY `image_url` removes the
     * picture (200 `{"image": null}`; what the web sends, an empty form, does too; a blank `image`
     * part is a 500; FACTS "Fas 4: Fas 3:s rester" c). A gone recipe drops the row.
     */
    private suspend fun removeImage(remote: ActiveSource.Remote, recipeId: Long): OutboxResult =
        when (val sent = caller.write(remote) { imports.setImageUrl(recipeId, "".toRequestBody(TEXT)) }) {
            is Outcome.Failure -> if (sent.error == TandemError.NotFound) OutboxResult.Dropped else OutboxResult.Failed(sent.error)

            is Outcome.Success -> {
                details.fetch(remote, recipeId, isBackground = true, force = true)
                OutboxResult.Sent
            }
        }

    /**
     * Fas 3 (import lane): the picture an import offered, downloaded by the server (`image_url`).
     * A picture the server cannot fetch (a 400, or a gone recipe) is dropped: the recipe keeps
     * none, which the user can fix in the editor.
     */
    private suspend fun imageFromWeb(remote: ActiveSource.Remote, recipeId: Long, url: String): OutboxResult =
        when (val sent = caller.write(remote) { imports.setImageUrl(recipeId, url.toRequestBody(TEXT)) }) {
            is Outcome.Failure -> when (sent.error) {
                TandemError.NotFound, is TandemError.BadRequest -> OutboxResult.Dropped
                else -> OutboxResult.Failed(sent.error)
            }

            is Outcome.Success -> {
                details.fetch(remote, recipeId, isBackground = true, force = true)
                OutboxResult.Sent
            }
        }

    /** What a retry's lookup settles: the lost recipe, or a failed lookup that holds the row back; null to POST. */
    private suspend fun lostAnswer(remote: ActiveSource.Remote, row: OutboxEntity, name: String, marker: String): OutboxResult? {
        val states = databases.get(remote.kind).syncStateDao()
        if (row.attempts == 0 || states.get(remote.accountId, marker) == null) return null
        return when (val lost = findLostCreate(remote, row, name)) {
            is Outcome.Failure -> OutboxResult.Failed(lost.error)

            is Outcome.Success -> lost.value?.let { found ->
                states.delete(remote.accountId, marker)
                OutboxResult.Created(found)
            }
        }
    }

    /**
     * A recipe with this name, created by this user since the change was made, that no other
     * create of this device claimed: the answer of an earlier attempt was lost, so the retry never
     * makes a second one. The lowest such id wins (deterministic). Fas 4 review (fix-data): newest
     * first, so it is on the page among many recipes of a similar name; a failed lookup is a
     * Failure, and the row waits instead of POSTing a second recipe.
     */
    private suspend fun findLostCreate(remote: ActiveSource.Remote, row: OutboxEntity, name: String): Outcome<Long?> {
        if (name.isEmpty()) return Outcome.Success(null)
        val query = RecipeListQuery(text = name, pageSize = LOST_LOOKUP_PAGE, sortOrder = RecipeSortOrder.CREATED_DESC)
        val listed = when (val read = caller.call(remote, background = true) { recipes.list(query) }) {
            is Outcome.Failure -> return read
            is Outcome.Success -> read.value
        }
        val claimed = claimedServerIds(databases, remote, OutboxEntities.RECIPE_EDIT)
        val notBefore = row.createdAtEpochMillis - SyncSchedule.CLOCK_SKEW_TOLERANCE.inWholeMilliseconds
        val id = listed.results.filter { recipe ->
            val createdAt = TandoorDates.parseInstantOrNull(recipe.createdAt)?.toEpochMilli()
            recipe.name == name && recipe.id != null && recipe.id !in claimed &&
                recipe.createdBy?.id == remote.account.userId && createdAt != null && createdAt >= notBefore
        }.mapNotNull { it.id }.minOrNull() ?: return Outcome.Success(null)
        details.fetch(remote, id, isBackground = true, force = true)
        return Outcome.Success(id)
    }

    private fun JsonObject.id(): Long? = (this["id"] as? JsonPrimitive)?.longOrNull

    /**
     * [body] with every `keywords[].name` of a keyword with `id` replaced by the server's current
     * name: from [fresh] (the recipe as just read, null for a new one), else `GET /api/keyword/{id}/`.
     * A keyword gone from the server goes by name alone (get-or-create); any other failed read fails.
     */
    private suspend fun withServerKeywordNames(remote: ActiveSource.Remote, body: JsonObject, fresh: JsonObject?): Outcome<JsonObject> {
        val keywords = (body["keywords"] as? JsonArray) ?: return Outcome.Success(body)
        val names = (fresh?.get("keywords") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .mapNotNull { keyword -> keyword.id()?.let { id -> id to (keyword["name"] as? JsonPrimitive)?.contentOrNull } }
            .toMap()
        val renamed = keywords.map { element ->
            val keyword = element as? JsonObject ?: return@map element
            val id = keyword.id() ?: return@map element
            val name = names[id] ?: when (val read = caller.call(remote, background = true) { drinks.keyword(id) }) {
                is Outcome.Success -> read.value.name

                is Outcome.Failure -> {
                    if (read.error != TandemError.NotFound) return read
                    return@map JsonObject(keyword - "id")
                }
            }
            JsonObject(keyword + ("name" to JsonPrimitive(name)))
        }
        return Outcome.Success(JsonObject(body + ("keywords" to JsonArray(renamed))))
    }

    private suspend fun withServerNames(remote: ActiveSource.Remote, body: JsonObject, fresh: JsonObject?): Outcome<JsonObject> =
        when (val named = withServerKeywordNames(remote, body, fresh)) {
            is Outcome.Failure -> named
            is Outcome.Success -> withServerPropertyTypeNames(remote, named.value, fresh)
        }

    /**
     * Fas 4 (#22): every `properties[].property_type.name` as the server has it right now, from
     * [fresh], else `GET /api/property-type/{id}/`: like a keyword, the name a type goes with renames
     * it for the whole space (FACTS "Fas 4: receptredigeraren"). A value of a type gone from the
     * server is dropped, by name it would make the type anew; any other failed read fails.
     */
    private suspend fun withServerPropertyTypeNames(
        remote: ActiveSource.Remote,
        body: JsonObject,
        fresh: JsonObject?,
    ): Outcome<JsonObject> {
        val properties = (body["properties"] as? JsonArray) ?: return Outcome.Success(body)
        val names = (fresh?.get("properties") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("property_type") as? JsonObject }
            .mapNotNull { type -> type.id()?.let { id -> id to (type["name"] as? JsonPrimitive)?.contentOrNull } }
            .toMap()
        val named = properties.mapNotNull { element ->
            val property = element as? JsonObject ?: return@mapNotNull element
            val type = property["property_type"] as? JsonObject ?: return@mapNotNull element
            val id = type.id() ?: return@mapNotNull element
            val name = names[id] ?: when (val read = caller.call(remote, background = true) { recipes.propertyType(id) }) {
                is Outcome.Success -> read.value.name

                is Outcome.Failure -> {
                    if (read.error != TandemError.NotFound) return read
                    return@mapNotNull null
                }
            }
            JsonObject(property + ("property_type" to JsonObject(type + ("name" to JsonPrimitive(name)))))
        }
        return Outcome.Success(JsonObject(body + ("properties" to JsonArray(named))))
    }

    /** `sync_state` key: the recipe POST of [localId] was started (its answer may be lost). */
    private fun postedKey(localId: Long): String = "$POSTED_PREFIX$localId"

    companion object {
        /** The error an edit held back by a conflict fails with (`last_error` = [RecipeEditPayloads.CONFLICT]). */
        val CONFLICT_ERROR: TandemError = TandemError.Unknown(RecipeEditPayloads.CONFLICT)
        private val JPEG = "image/jpeg".toMediaType()
        private val TEXT = "text/plain".toMediaType()
        private val BINARY = "application/octet-stream".toMediaType()
        private const val LOST_LOOKUP_PAGE = 10
        private const val POSTED_PREFIX = "recipe_posted:"
    }
}
