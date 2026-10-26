package casa.moller.tandem.feature.recipes.books

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import casa.moller.tandem.core.model.book.RecipeBook
import casa.moller.tandem.core.model.book.RecipeBookKind
import casa.moller.tandem.core.model.recipe.RecipeOverview

// Phase 2 (books lane): the states of the books screens (plan 3.7, 6.2 "Böcker/Favoriter", #17;
// Stitch receptbok_master_detail_surfplatta, recept_s_k_filter_samlingar).

/**
 * All books (Recept → Böcker, Mer → Receptböcker).
 *
 * @property favoriteCount recipes in everyone's favourites (merged).
 * @property wantToCookCount recipes marked "Vill laga".
 * @property own the user's own plain and smart books (favourites and "Vill laga" are the two
 *   cards above them).
 * @property shared books other members own and shared with the user: read-only (plan 3.7).
 * @property readOnly guest (#42): no "Ny bok", the read-only banner.
 * @property creating the "Ny bok" dialog is open.
 */
@Immutable
data class BooksUiState(
    val loading: Boolean = true,
    val favoriteCount: Int = 0,
    val wantToCookCount: Int = 0,
    val own: List<RecipeBook> = emptyList(),
    val shared: List<RecipeBook> = emptyList(),
    val readOnly: Boolean = false,
    val creating: Boolean = false,
) {
    val canCreate: Boolean
        get() = !readOnly
}

sealed interface BooksAction {
    data object Refresh : BooksAction

    data object OpenCreate : BooksAction

    data object DismissCreate : BooksAction

    data class Create(val name: String) : BooksAction
}

sealed interface BooksEffect {
    data class Message(@StringRes val text: Int) : BooksEffect

    /** A new book was created (offline too): open it. */
    data class Created(val bookId: Long) : BooksEffect
}

/** Which recipes a [BookRecipesViewModel] shows. */
sealed interface BookSource {
    /** One book (its temporary id while it is not sent). */
    data class Book(val id: Long) : BookSource

    /** Everyone's favourites, merged (plan 3.7 A). */
    data object Favorites : BookSource

    /** "Vill laga" (plan 3.7 B). */
    data object WantToCook : BookSource
}

/**
 * One book's recipes, the merged favourites or "Vill laga".
 *
 * @property title the book's name ("" for the two collections: the screen uses its own title).
 * @property kind what the book is ([RecipeBookKind.FAVORITES] / [RecipeBookKind.WANT_TO_COOK] for
 *   the collections).
 * @property ownerName the owner of someone else's book (shown with the read-only explanation).
 * @property canRemove the user may take recipes out: an own plain or favourites book, the own
 *   favourites in the merged view ([ownFavoriteIds]) or "Vill laga" (never for a guest).
 * @property ownFavoriteIds which of the merged favourites are the user's own (only those can be
 *   taken out of the merged view).
 * @property missing the book is gone (deleted on the server).
 */
@Immutable
data class BookRecipesUiState(
    val loading: Boolean = true,
    val title: String = "",
    val description: String = "",
    val kind: RecipeBookKind = RecipeBookKind.BOOK,
    val isOwn: Boolean = true,
    val ownerName: String? = null,
    val recipes: List<RecipeOverview> = emptyList(),
    val ownFavoriteIds: Set<Long> = emptySet(),
    val readOnly: Boolean = false,
    val canRemove: Boolean = false,
    val offline: Boolean = false,
    val missing: Boolean = false,
) {
    /** Someone else's book: the plan 3.7 explanation instead of write buttons. */
    val sharedByOther: Boolean
        get() = !isOwn && kind != RecipeBookKind.WANT_TO_COOK

    fun canRemove(recipe: RecipeOverview): Boolean = canRemove && (kind != RecipeBookKind.FAVORITES || isOwn || recipe.id in ownFavoriteIds)
}

sealed interface BookRecipesAction {
    data object Refresh : BookRecipesAction

    data class Remove(val recipeId: Long) : BookRecipesAction

    /** fix-ui: "Ångra" after [Remove] puts the recipe back (in the book, Favoriter or "Vill laga"). */
    data object Undo : BookRecipesAction
}

sealed interface BookRecipesEffect {
    data class Message(@StringRes val text: Int) : BookRecipesEffect

    /**
     * fix-ui: [recipeName] left the book of [kind] ("Vill laga" is the household's shared queue),
     * shown with "Ångra" ([BookRecipesAction.Undo]).
     */
    data class Removed(val recipeName: String, val kind: RecipeBookKind) : BookRecipesEffect
}

/**
 * The favourite / "Vill laga" / book buttons of the recipe detail (plan 6.2 "Favorit/bok (bara i
 * böcker du äger, §3.7)").
 *
 * @property favoriteOf other members whose favourites hold the recipe ("Favorit hos Alex").
 * @property books the user's own plain books, with whether the recipe is in each (the "Lägg i bok" sheet).
 * @property readOnly guest: no buttons, only [favoriteOf].
 */
@Immutable
data class RecipeBookButtonsState(
    val isFavorite: Boolean = false,
    val wantToCook: Boolean = false,
    val favoriteOf: List<String> = emptyList(),
    val books: List<BookChoice> = emptyList(),
    val readOnly: Boolean = false,
    val sheetOpen: Boolean = false,
    val loaded: Boolean = false,
)

/** An own book in the "Lägg i bok" sheet. */
@Immutable
data class BookChoice(val id: Long, val name: String, val contains: Boolean)

sealed interface RecipeBookButtonsAction {
    data object ToggleFavorite : RecipeBookButtonsAction

    data object ToggleWantToCook : RecipeBookButtonsAction

    data object OpenBooks : RecipeBookButtonsAction

    data object DismissBooks : RecipeBookButtonsAction

    data class ToggleBook(val bookId: Long) : RecipeBookButtonsAction

    data class CreateBook(val name: String) : RecipeBookButtonsAction
}

/**
 * The collections row above the recipe list (Stitch recept_s_k_filter_samlingar: "Favoriter 24",
 * "Vill laga", plan 6.1 "böcker som chips") and the hearts on the recipe cards.
 *
 * @property favoriteIds the user's OWN favourites (filled hearts).
 * @property readOnly guest: hearts are shown filled or not but cannot be tapped.
 */
@Immutable
data class RecipeCollectionsState(
    val favoriteCount: Int = 0,
    val wantToCookCount: Int = 0,
    val bookCount: Int = 0,
    val favoriteIds: Set<Long> = emptySet(),
    val readOnly: Boolean = false,
)

/** A collection opened from the Recipes tab. */
enum class RecipeCollection { FAVORITES, WANT_TO_COOK, BOOKS }
