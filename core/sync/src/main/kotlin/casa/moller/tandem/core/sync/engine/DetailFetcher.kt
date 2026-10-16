package casa.moller.tandem.core.sync.engine

import casa.moller.tandem.core.database.TandemDatabaseFactory
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
 */
@Singleton
internal class DetailFetcher @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val clock: SyncClock,
) {
    private val background = Semaphore(SyncSchedule.MAX_PARALLEL_DETAIL_FETCHES)

    /** Fetches and stores recipe [recipeId] of [remote]; [isBackground] for the prefetch. */
    suspend fun fetch(remote: ActiveSource.Remote, recipeId: Long, isBackground: Boolean): Outcome<Unit> = if (isBackground) {
        background.withPermit {
            fetchNow(remote, recipeId, background = true).also { delay(SyncSchedule.DETAIL_FETCH_PAUSE) }
        }
    } else {
        fetchNow(remote, recipeId, background = false)
    }

    private suspend fun fetchNow(remote: ActiveSource.Remote, recipeId: Long, background: Boolean): Outcome<Unit> = catchingUnexpected {
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
        databases.get(remote.kind).recipeDao().replaceDetail(remote.accountId, write)
        Outcome.Success(Unit)
    }

    private companion object {
        const val DECODE_DETAIL = "recipe detail"
    }
}
