package casa.moller.tandem.core.data.recipe

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.model.recipe.RecipeDetailState
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.recipe.ServingsChoice
import kotlinx.coroutines.flow.Flow

/**
 * Recipes for the recipe list, the detail screen and cook mode (plan 3.5, 5.3, features #3-#5).
 * Offline first: Room is the single source of truth, the network only refreshes it.
 *
 * Contract for every implementation (the real one in core:data/core:sync, the demo one, and the
 * fakes in core:testing):
 * - **Threading:** every function is main-safe. Flows do their work on background dispatchers and
 *   may be collected on the main thread; suspend functions switch dispatchers themselves.
 * - **Account:** everything is scoped to the active account (plan 5.3, B14). After an account
 *   switch or logout the flows emit the new account's data (or empty lists).
 * - **Errors:** flows never throw for server or network problems; failures are values
 *   ([Outcome.Failure], [RecipeDetailState.error]). Only cancellation propagates.
 * - **No 404 storms (plan 4.1):** implementations never retry a 404 and keep to the shared rate
 *   limit (at most 2 detail fetches at a time).
 */
interface RecipeRepository {

    /**
     * The stored recipes matching [query], from Room, re-emitted whenever the stored data changes.
     * Works offline. Text search uses FTS (diacritics-insensitive: "akta" finds "äkta", "å" and
     * "a" match); [RecipeQuery.sort] is applied locally. Never touches the network.
     */
    fun observeRecipes(query: RecipeQuery): Flow<List<RecipeOverview>>

    /**
     * Searches on the server (`GET /api/recipe/` with the query's filters, first page of at most
     * 100) and stores the returned overviews, so they also show up in [observeRecipes].
     *
     * Fails with the server/network [casa.moller.tandem.core.model.error.TandemError] when
     * offline or when the server refuses; the UI then keeps showing the local results.
     */
    suspend fun searchServer(query: RecipeQuery): Outcome<List<RecipeOverview>>

    /**
     * One recipe for the detail screen, stale-while-revalidate (plan 3.5): emits the stored copy
     * at once, starts a refresh in the background when online (at most once per collection, not
     * when the stored copy is younger than a few minutes), emits `refreshing = true`, then the
     * fresh copy or the old copy with [RecipeDetailState.error] set. Offline it emits the stored
     * copy (or an empty state) with the network error. Collecting it does not count as a view;
     * call [markViewed].
     */
    fun observeRecipe(id: Long): Flow<RecipeDetailState>

    /** Fetches the detail now (pull to refresh) and stores it. Observers of [observeRecipe] see the result. */
    suspend fun refreshRecipe(id: Long): Outcome<Unit>

    /**
     * Pins (`true`) or unpins a recipe for offline use (feature #5): a pinned recipe's detail and
     * image are downloaded at once if possible, kept, and refreshed every 24 h. Succeeds locally
     * even offline; the download then happens with the next sync.
     */
    suspend fun setAvailableOffline(id: Long, available: Boolean): Outcome<Unit>

    /** The servings the user picked for recipe [id], null when never changed (use the recipe's own). Local only. */
    fun observeServingsChoice(id: Long): Flow<ServingsChoice?>

    /** Remembers [servings] for recipe [id]; null forgets the choice. Local only, never fails for network reasons. */
    suspend fun setServingsChoice(id: Long, servings: Double?): Outcome<Unit>

    /**
     * The recipe was opened: records it locally ("recently viewed" on Today) and queues
     * `POST /api/view-log/` in the outbox (plan 3.3), so the web app's list stays in sync. Never
     * fails; in safe mode or offline the queued entry waits.
     */
    suspend fun markViewed(id: Long)

    /** All stored keywords, by name, for the filter chips. Works offline. */
    fun observeKeywords(): Flow<List<Keyword>>

    /**
     * The foods the recipe list can filter on ([RecipeQuery.foodIds], plan 6.2 "livsmedel"), by
     * name: the food list from the last sync plus the foods of stored recipes. Works offline.
     */
    fun observeFoods(): Flow<List<Food>>
}
