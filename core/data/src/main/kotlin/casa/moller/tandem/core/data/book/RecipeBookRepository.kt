package casa.moller.tandem.core.data.book

import casa.moller.tandem.core.model.book.RecipeBook
import casa.moller.tandem.core.model.book.RecipeBookStatus
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.recipe.RecipeOverview
import kotlinx.coroutines.flow.Flow

/**
 * Recipe books, favourites and "Vill laga" (plan 3.7, 6.2 "Böcker/Favoriter", feature #17),
 * offline first (`recipe_book` / `recipe_book_entry` / `RECIPE_KEYWORDS` outbox rows).
 * Implemented in core:sync by the books lane (until then a temporary binding in :app). Same
 * contract as `ShoppingRepository` (main-safe, account-scoped, demo, flows never throw).
 *
 * **Owner only (plan 3.7):** Tandoor lets a shared member read a book but answers 403 to every
 * write; a write to a book the user does not own fails with `DataProblem.NOT_OWNER` before anything
 * is queued (and `DataProblem.READ_ONLY` for a guest). **Favourites** = one book per person
 * ("Favoriter (Anna)", created by the app on the first "Favorit", shared read-only with the
 * household); [observeFavorites] merges them. **"Vill laga"** = the keyword
 * `WantToCook.KEYWORD` on the recipe, which every member can set (a recipe PATCH with the whole
 * keyword list), and a smart book on it for the web.
 */
interface RecipeBookRepository {
    /** Every book the user can see (own and shared), by order and name. */
    fun observeBooks(): Flow<List<RecipeBook>>

    /** One book, null when it is gone. */
    fun observeBook(id: Long): Flow<RecipeBook?>

    /** The recipes of a book: entries of a plain book, the filter's recipes of a smart book (stored ones offline). */
    fun observeRecipes(bookId: Long): Flow<List<RecipeOverview>>

    /** Every member's favourites, merged (each recipe once). */
    fun observeFavorites(): Flow<List<RecipeOverview>>

    /** The recipes with the "Vill laga" keyword. */
    fun observeWantToCook(): Flow<List<RecipeOverview>>

    /** Favourite / "Vill laga" / books state of one recipe, for the detail's buttons. */
    fun observeStatus(recipeId: Long): Flow<RecipeBookStatus>

    /** Creates an own book. Returns its (negative, until sent) id. */
    suspend fun createBook(name: String, description: String = ""): Outcome<Long>

    /** Adds a recipe to an own book (`DataProblem.NOT_OWNER` otherwise). Adding it twice does nothing. */
    suspend fun addToBook(bookId: Long, recipeId: Long): Outcome<Unit>

    suspend fun removeFromBook(bookId: Long, recipeId: Long): Outcome<Unit>

    /** Puts the recipe in (or takes it out of) the user's own favourites book, creating the book when needed. */
    suspend fun setFavorite(recipeId: Long, favorite: Boolean): Outcome<Unit>

    /** Sets or removes the "Vill laga" keyword on the recipe (creating the keyword and smart book when needed). */
    suspend fun setWantToCook(recipeId: Long, wantToCook: Boolean): Outcome<Unit>

    /** Re-reads books and entries now. */
    suspend fun refresh(): Outcome<Unit>
}
