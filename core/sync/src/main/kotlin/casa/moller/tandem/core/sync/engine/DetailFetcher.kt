package casa.moller.tandem.core.sync.engine

import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.mapper.toDetailWrite
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.model.sync.SyncStop
import casa.moller.tandem.core.network.dto.RecipeDto
import casa.moller.tandem.core.network.json.TandoorJson
import casa.moller.tandem.core.sync.SyncSchedule
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.account.identity
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.remote.ImageResolver
import casa.moller.tandem.core.sync.remote.RemoteCaller
import casa.moller.tandem.core.sync.remote.toRecipe
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject

/**
 * Downloads one recipe detail (`GET /api/recipe/{id}/`) and stores it in one transaction with its
 * raw JSON (plan 3.6, 5.7: a later app version can map it again without downloading).
 *
 * - Background fetches (prefetch, plan 3.5) run at most [SyncSchedule.MAX_PARALLEL_DETAIL_FETCHES]
 *   at a time with a short pause after each; a fetch the user started (opening a recipe, pull to
 *   refresh) does not wait for them (the shared client still limits the server to 2 requests).
 * - 404 means the recipe was deleted (plan 4.1: never retried): it is removed locally with its
 *   unsent outbox rows, so the detail screen shows "deleted". Another member's private recipe
 *   (JSON 403 with a working token) is treated the same way.
 * - An answer that arrives after the active account changed (logout during the fetch) is not
 *   stored, so a logged-out account's recipe never comes back.
 * - A recipe created in the app (Fas 4 review, fix-data): a temporary id is followed to its server
 *   id, and one that has none yet is not on the server: nothing is fetched (plan 4.1), which also
 *   keeps the 404 from wiping the only copy.
 */
@Singleton
internal class DetailFetcher @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val clock: SyncClock,
) {
    private val background = Semaphore(SyncSchedule.MAX_PARALLEL_DETAIL_FETCHES)

    /**
     * Fetches and stores recipe [recipeId] of [remote]; [isBackground] for the prefetch. Fas 3
     * (redigerare lane): a recipe with an edit of this device still to send keeps its stored copy
     * (the server's would hide the edit until it is sent; the sender stores the answer itself);
     * only its deletion (404) is applied. [force] stores anyway (the senders).
     */
    suspend fun fetch(remote: ActiveSource.Remote, recipeId: Long, isBackground: Boolean, force: Boolean = false): Outcome<Unit> =
        if (isBackground) {
            background.withPermit {
                fetchNow(remote, recipeId, background = true, force = force).also { delay(SyncSchedule.DETAIL_FETCH_PAUSE) }
            }
        } else {
            fetchNow(remote, recipeId, background = false, force = force)
        }

    private suspend fun fetchNow(remote: ActiveSource.Remote, requested: Long, background: Boolean, force: Boolean): Outcome<Unit> =
        catchingUnexpected {
            val recipeId = databases.get(remote.kind).idRewriteDao().current(remote.accountId, OutboxEntities.RECIPE_EDIT, requested)
            if (recipeId < 0) return@catchingUnexpected Outcome.Success(Unit)
            val response = caller.call(remote, background, confirmUnauthorized = true) { recipes.recipeJson(recipeId) }
            if (sources.source.value.identity != remote.identity) return@catchingUnexpected Outcome.Failure(SyncStop.SourceChanged)
            val json = when (response) {
                is Outcome.Failure -> {
                    if (response.error == TandemError.NotFound) {
                        databases.get(remote.kind).recipeDao().deleteRecipes(remote.accountId, listOf(recipeId))
                    }
                    return@catchingUnexpected response
                }

                is Outcome.Success -> response.value
            }
            store(remote, recipeId, json, force)
        }

    /**
     * Stores [json] (a full recipe as `GET /api/recipe/{id}/` answers it, or the answer of a recipe
     * PATCH, Phase 2 "Vill laga") as recipe [recipeId]'s detail, in one transaction.
     */
    suspend fun store(remote: ActiveSource.Remote, recipeId: Long, json: JsonObject, force: Boolean = false): Outcome<Unit> =
        catchingUnexpected {
            if (sources.source.value.identity != remote.identity) return@catchingUnexpected Outcome.Failure(SyncStop.SourceChanged)
            if (!force && hasUnsentEdit(remote, recipeId)) return@catchingUnexpected Outcome.Success(Unit)
            val dto = try {
                TandoorJson.decodeFromJsonElement(RecipeDto.serializer(), json)
            } catch (e: SerializationException) {
                return@catchingUnexpected Outcome.Failure(TandemError.Decode(DECODE_DETAIL))
            } catch (e: IllegalArgumentException) {
                return@catchingUnexpected Outcome.Failure(TandemError.Decode(DECODE_DETAIL))
            }
            val recipe = dto.toRecipe(ImageResolver.forBaseUrl(remote.baseUrl))
                ?.takeIf { it.id == recipeId }
                ?: return@catchingUnexpected Outcome.Failure(TandemError.Decode(DECODE_DETAIL))
            val write = recipe.toDetailWrite(remote.accountId, rawJson = json.toString(), fetchedAtEpochMillis = clock.nowMillis())
            val database = databases.get(remote.kind)
            database.recipeDao().replaceDetail(remote.accountId, write)
            // The detail's rating does not know this device's unsent "Klart!" entries yet.
            database.cookLogDao().reapplyUnsent(remote.accountId, recipeId, remote.cookStatsRules)
            Outcome.Success(Unit)
        }

    /** Fas 3: whether this device has an edit of [recipeId] queued (`recipe_edit`, not FAILED). */
    private suspend fun hasUnsentEdit(remote: ActiveSource.Remote, recipeId: Long): Boolean =
        recipeId in databases.get(remote.kind).recipeDao().getIdsWithUnsentEdits(remote.accountId)

    private companion object {
        const val DECODE_DETAIL = "recipe detail"
    }
}
