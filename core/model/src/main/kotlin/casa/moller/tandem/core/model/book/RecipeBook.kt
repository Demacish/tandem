package casa.moller.tandem.core.model.book

import casa.moller.tandem.core.model.recipe.RecipeOverview

/**
 * How the app treats a recipe book (plan 3.7, 6.2 "Böcker/Favoriter", feature #17).
 *
 * - [BOOK]: a plain Tandoor book with entries.
 * - [SMART]: a book with a `CustomFilter`; its recipes come from `GET /api/recipe/?filter=` and it
 *   has no entries.
 * - [FAVORITES]: one person's "Favoriter" book (plan 3.7 alternative A). The Favorites view merges
 *   every member's favourites; only the own book can be changed.
 * - [WANT_TO_COOK]: "Vill laga", the shared queue: recipes with the keyword [WantToCook.KEYWORD]
 *   (plan 3.7 alternative B), which every member (not a guest) can set on any recipe.
 */
enum class RecipeBookKind { BOOK, SMART, FAVORITES, WANT_TO_COOK }

/**
 * A recipe book.
 *
 * @property id local id: the server id, or negative while the book is created offline.
 * @property ownerId the owner's user id (`created_by`); null when unknown.
 * @property ownerName the owner's display name.
 * @property isOwn the active user owns it: ONLY then may the app show write buttons (the server
 *   answers 403 to everyone else, plan 3.7; FACTS "Fas 2").
 * @property sharedWith user ids it is shared with (read-only for them).
 * @property recipeCount stored entries (0 for a smart book).
 */
data class RecipeBook(
    val id: Long,
    val name: String,
    val description: String = "",
    val kind: RecipeBookKind = RecipeBookKind.BOOK,
    val ownerId: Long? = null,
    val ownerName: String? = null,
    val isOwn: Boolean = false,
    val sharedWith: Set<Long> = emptySet(),
    val recipeCount: Int = 0,
) {
    /** Recipes can be added and removed here by the active user (own plain or favourites book). */
    val canEdit: Boolean
        get() = isOwn && (kind == RecipeBookKind.BOOK || kind == RecipeBookKind.FAVORITES)
}

/** A recipe in a book; [id] is the entry's local id (negative while offline). */
data class RecipeBookEntry(val id: Long, val bookId: Long, val recipe: RecipeOverview)

/**
 * The favourites and "Vill laga" state of one recipe for the detail screen's buttons.
 *
 * @property isFavorite in the active user's own favourites book.
 * @property favoriteOf display names of other members whose favourites contain it.
 * @property wantToCook has the "Vill laga" keyword.
 */
data class RecipeBookStatus(
    val recipeId: Long,
    val isFavorite: Boolean = false,
    val favoriteOf: List<String> = emptyList(),
    val wantToCook: Boolean = false,
    val bookIds: Set<Long> = emptySet(),
)

/** "Vill laga" (plan 3.7 B, §8.3): the keyword on the recipe is the shared queue. */
object WantToCook {
    /** The keyword's name in Tandoor (created on first use, shown among the recipe's keywords on the web). */
    const val KEYWORD: String = "vill-laga"

    /** `CustomFilter.search` of the smart book: keyword ids, `{"keywords_or":[<id>]}` (objects give a 500, FACTS "Fas 2"). */
    fun filterSearch(keywordId: Long): String = """{"keywords_or":[$keywordId]}"""
}
