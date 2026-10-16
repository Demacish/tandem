package casa.moller.tandem.feature.recipes.list

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.recipe.RecipeSort

/** Grid (photo cards, the default) or list (rows with thumbnails); remembered on the device. */
enum class RecipeListLayout {
    GRID,
    LIST,
    ;

    fun toggled(): RecipeListLayout = if (this == GRID) LIST else GRID
}

/** The server part of a search (plan 6.2: offline FTS at once, server search when online). */
enum class ServerSearch {
    /** No server search for this query (nothing typed or filtered, "only offline", demo mode). */
    IDLE,

    /** The server is being asked. */
    SEARCHING,

    /** The server answered; its extra results are merged into the list. */
    DONE,

    /** Offline: only the recipes stored on the device are searched. */
    OFFLINE,

    /** The server could not be asked (blocked, server error): stored results only. */
    FAILED,
}

/**
 * What the recipe list shows (plan 6.2 "Receptlista", feature #3).
 *
 * @property query the search text, filters and sort.
 * @property recipes stored recipes matching [query], followed by server results that are not
 *   stored yet ([serverOnlyCount] of them).
 * @property loaded the stored recipes were read at least once.
 * @property keywords every stored keyword, for the keyword picker.
 * @property offline the device is offline: recipes without a stored detail are dimmed.
 * @property refreshing a pull to refresh runs.
 * @property syncing a sync runs (e.g. the first one after login).
 * @property syncError why the last sync failed (blocked, server error, unexpected answer...);
 *   null while it runs, after a success, and for "offline" or "log in again", which have their
 *   own notices. An empty list shows it instead of "No recipes yet".
 */
@Immutable
data class RecipeListUiState(
    val query: RecipeQuery = RecipeQuery.All,
    val recipes: List<RecipeOverview> = emptyList(),
    val loaded: Boolean = false,
    val keywords: List<Keyword> = emptyList(),
    val layout: RecipeListLayout = RecipeListLayout.GRID,
    val serverSearch: ServerSearch = ServerSearch.IDLE,
    val serverOnlyCount: Int = 0,
    val offline: Boolean = false,
    val refreshing: Boolean = false,
    val syncing: Boolean = false,
    val syncError: TandemError? = null,
) {
    /** Text, keywords, time, rating or "only offline" narrow the list. */
    val isFiltered: Boolean
        get() = !query.isUnfiltered

    /** The keywords picked in the filter, by name. */
    val selectedKeywords: List<Keyword>
        get() = keywords.filter { it.id in query.keywordIds }

    /** The recipe cannot be opened right now: offline and no stored detail (shown dimmed). */
    fun isDimmed(recipe: RecipeOverview): Boolean = offline && !recipe.availableOffline
}

/** The sort orders offered in the list (plan 6.2; the others stay for Today and later phases). */
val ListSortOrders: List<RecipeSort> = listOf(RecipeSort.NAME, RecipeSort.NEWEST, RecipeSort.RATING, RecipeSort.LAST_COOKED)

/** "Under 30 min" (plan 6.2 filter chips). */
const val QUICK_MAX_MINUTES: Int = 30

/** "Rating 4+". */
const val GOOD_MIN_RATING: Double = 4.0

/** What the recipe list can do. */
sealed interface RecipeListAction {
    data class Search(val text: String) : RecipeListAction

    data class ToggleKeyword(val id: Long) : RecipeListAction

    data object ClearKeywords : RecipeListAction

    data object ToggleQuick : RecipeListAction

    data object ToggleGoodRating : RecipeListAction

    data object ToggleOnlyOffline : RecipeListAction

    data class Sort(val sort: RecipeSort) : RecipeListAction

    /** Clears the text and every filter (keeps the sort). */
    data object ClearFilters : RecipeListAction

    data object ToggleLayout : RecipeListAction

    /** Pull to refresh: a sync with reason USER_REFRESH. */
    data object Refresh : RecipeListAction
}

/** One-off events of the recipe list. */
sealed interface RecipeListEffect {
    data class Message(@StringRes val text: Int) : RecipeListEffect

    /** The sync a pull to refresh asked for failed: "Could not sync: <what happened>". */
    data class SyncFailed(val error: TandemError) : RecipeListEffect
}
