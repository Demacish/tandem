package casa.moller.tandem.core.model.recipe

import casa.moller.tandem.core.model.error.TandemError
import kotlin.time.Instant

/**
 * What the recipe list shows (plan 6.2 "Receptlista"). The same query runs offline against Room
 * (FTS over name, description, keywords and ingredient names; `unicode61 remove_diacritics 2`)
 * and, when online, against `GET /api/recipe/` with the matching server filters.
 *
 * @property text free-text search; blank means "all recipes".
 * @property keywordIds only recipes that have ALL of these keywords (`keywords_and`).
 * @property maxTotalMinutes only recipes whose working + waiting time is at most this, e.g. 30.
 * @property minRating only recipes rated at least this (`rating_gte`), e.g. 4.0.
 * @property sort the order of the results.
 * @property onlyOffline only recipes that can be opened without a connection
 *   ([RecipeOverview.availableOffline]); local only, ignored by the server search.
 */
data class RecipeQuery(
    val text: String = "",
    val keywordIds: Set<Long> = emptySet(),
    val maxTotalMinutes: Int? = null,
    val minRating: Double? = null,
    val sort: RecipeSort = RecipeSort.NAME,
    val onlyOffline: Boolean = false,
) {
    /** True when nothing narrows the result (any sort): the list shows every recipe. */
    val isUnfiltered: Boolean
        get() = text.isBlank() && keywordIds.isEmpty() && maxTotalMinutes == null && minRating == null && !onlyOffline

    companion object {
        /** Every recipe, by name. */
        val All: RecipeQuery = RecipeQuery()
    }
}

/**
 * Sort orders of the recipe list. All of them work offline on the stored overviews;
 * [TIMES_COOKED] falls back to [LAST_COOKED] order for recipes whose count is unknown.
 */
enum class RecipeSort {
    /** A to Z, locale aware (å, ä, ö after z in Swedish). */
    NAME,

    /** Highest rating first; unrated last. */
    RATING,

    /** Most recently cooked first; never cooked last. */
    LAST_COOKED,

    /** Most often cooked first (server `-times_cooked`). */
    TIMES_COOKED,

    /** Newest first (server `-created_at`, the `new` flag first). */
    NEWEST,

    /** Shortest working + waiting time first; unknown times last. */
    TOTAL_TIME,
}

/**
 * The servings the user picked for one recipe (plan 6.2: the detail screen remembers the choice
 * per recipe). Stored locally, never sent to the server.
 *
 * @property recipeId the recipe.
 * @property servings the chosen servings (> 0; may be fractional, e.g. 1.5).
 */
data class ServingsChoice(val recipeId: Long, val servings: Double)

/**
 * The recipe detail as the data layer streams it (stale-while-revalidate, plan 3.5): the stored
 * copy first, then the same state again with `refreshing = true` while the detail is fetched,
 * then the fresh copy (or the old one plus [error]).
 *
 * @property recipeId the recipe this state is about.
 * @property overview the stored list entry, so the header (name, image, times) can be shown at
 *   once even before a detail exists. Null when the recipe is unknown locally.
 * @property recipe the stored detail, null until it was fetched once.
 * @property fetchedAt when [recipe] was fetched from the server, null when never.
 * @property refreshing a refresh from the server is running.
 * @property error why the last refresh failed, cleared by the next success. With a stored
 *   [recipe] the screen keeps showing it (offline, server down); [TandemError.NotFound] means the
 *   recipe was deleted on the server.
 */
data class RecipeDetailState(
    val recipeId: Long,
    val overview: RecipeOverview? = null,
    val recipe: Recipe? = null,
    val fetchedAt: Instant? = null,
    val refreshing: Boolean = false,
    val error: TandemError? = null,
) {
    /** Nothing to show yet: no stored detail and no overview. */
    val isEmpty: Boolean
        get() = recipe == null && overview == null

    /** The server says the recipe no longer exists (deleted or no longer shared). */
    val isDeleted: Boolean
        get() = error == TandemError.NotFound
}
