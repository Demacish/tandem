package casa.moller.tandem.core.sync.drink

import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.domain.drink.DraftKeywordRole
import casa.moller.tandem.core.model.drink.DrinkKeywords
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.network.api.RecipeListQuery
import casa.moller.tandem.core.network.api.list
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.engine.DetailFetcher
import casa.moller.tandem.core.sync.engine.OutboxHandler
import casa.moller.tandem.core.sync.engine.OutboxResult
import casa.moller.tandem.core.sync.remote.RemoteCaller
import casa.moller.tandem.core.sync.shopping.claimedServerIds
import java.io.File
import javax.inject.Inject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody

/** Error code of a row this version cannot read (kept FAILED for "Pending changes"). */
private const val UNSUPPORTED: String = "tandem.sync.unsupported-outbox-op"

/**
 * Fas S (drycker lane): the `drink` rows (docs/BYGGE.md "Fas S – kontrakt", table "Outbox"; FACTS
 * "Fas S f"):
 *
 * - **CREATE** reads its `drink_draft` row (so pairings added while offline go along), finds or
 *   creates "Dryck", the types under it and the drink's pairings under "Passar med"
 *   ([PairingKeywords]), then `POST /api/recipe/ {name, description, keywords: [{id, name}...],
 *   servings, steps: [], internal: true}`. The answer is stored as the recipe's detail (the drink
 *   is in the list at once); `completeCreate` removes the draft and gives the photo row its id.
 *   A retry after a lost answer first looks for the drink by name.
 * - **RECIPE_IMAGE** `PUT /api/recipe/{id}/image/` (multipart `image`) with the app-private copy,
 *   deleted afterwards; the detail is read again for the image URL.
 */
internal class DrinkOutboxHandler @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val details: DetailFetcher,
    private val keywords: PairingKeywords,
    private val images: DrinkImageStore,
) : OutboxHandler {
    override suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult = when (row.op) {
        OutboxOps.CREATE -> create(remote, row)
        OutboxOps.RECIPE_IMAGE -> image(remote, row, payload)
        else -> OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
    }

    private suspend fun create(remote: ActiveSource.Remote, row: OutboxEntity): OutboxResult {
        val localId = row.localId ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        val draft = databases.get(remote.kind).drinkDraftDao().get(remote.accountId, localId) ?: return OutboxResult.Dropped
        if (row.attempts > 0) findLostCreate(remote, draft.name)?.let { return OutboxResult.Created(it) }
        val names = DrinkPayloads.decodeDraftKeywords(draft.keywords)
        val linked = mutableListOf<Pair<Long, String>>()
        val root = when (val found = keywords.drinkRoot(remote)) {
            is Outcome.Failure -> return OutboxResult.Failed(found.error)
            is Outcome.Success -> found.value
        }
        linked += root to DrinkKeywords.DRINK
        for (keyword in names) {
            val id = when (keyword.role) {
                DraftKeywordRole.DRINK -> continue
                DraftKeywordRole.TYPE -> keywords.type(remote, keyword.name)
                DraftKeywordRole.PAIRING -> keywords.pairing(remote, keyword.name)
            }
            when (id) {
                is Outcome.Failure -> return OutboxResult.Failed(id.error)
                is Outcome.Success -> if (linked.none { it.first == id.value }) linked += id.value to keyword.name
            }
        }
        val body = buildJsonObject {
            put("name", draft.name)
            put("description", draft.description)
            put(
                "keywords",
                buildJsonArray {
                    linked.forEach { (id, name) ->
                        add(
                            buildJsonObject {
                                put("id", id)
                                put("name", name)
                            },
                        )
                    }
                },
            )
            put("servings", draft.servings.coerceAtLeast(1))
            put("steps", JsonArray(emptyList()))
            put("internal", true)
        }
        val created = when (val sent = caller.write(remote) { drinks.createRecipe(body) }) {
            is Outcome.Failure -> return OutboxResult.Failed(sent.error)
            is Outcome.Success -> sent.value
        }
        val recipeId = (created["id"] as? JsonPrimitive)?.longOrNull ?: return OutboxResult.Failed(TandemError.Decode("recipe-without-id"))
        details.store(remote, recipeId, created)
        return OutboxResult.Created(recipeId)
    }

    /**
     * A drink with this name that no other create of this device claimed and that carries a
     * drink keyword: the answer of an earlier attempt was lost, so the retry never makes a second one.
     */
    private suspend fun findLostCreate(remote: ActiveSource.Remote, name: String): Long? {
        val listed = caller.call(remote, background = true) { recipes.list(RecipeListQuery(text = name, pageSize = LOST_LOOKUP_PAGE)) }
        val claimed = claimedServerIds(databases, remote, OutboxEntities.DRINK)
        val found = (listed as? Outcome.Success)?.value?.results.orEmpty().firstOrNull { recipe ->
            recipe.name == name && recipe.id != null && recipe.id !in claimed &&
                recipe.keywords.any { DrinkKeywords.isDrinkRoot(it.label) }
        } ?: return null
        val id = found.id ?: return null
        details.fetch(remote, id, isBackground = true)
        return id
    }

    private suspend fun image(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult {
        val recipeId = row.serverId ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        val path = DrinkPayloads.imagePathOf(payload) ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
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
                details.fetch(remote, recipeId, isBackground = true)
                OutboxResult.Sent
            }
        }
    }

    private companion object {
        val JPEG = "image/jpeg".toMediaType()
        const val LOST_LOOKUP_PAGE = 10
    }
}
