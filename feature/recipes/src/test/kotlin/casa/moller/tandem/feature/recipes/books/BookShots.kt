package casa.moller.tandem.feature.recipes.books

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.model.book.RecipeBook
import casa.moller.tandem.core.model.book.RecipeBookKind
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.ui.readonly.ReadOnlySheetContent
import casa.moller.tandem.feature.recipes.list.RecipeListScreen
import casa.moller.tandem.feature.recipes.listState

/** Sample books of a household: own books, a smart book and a partner's shared books (plan 3.7). */
internal object BookSamples {
    val dinners = RecipeBook(id = 11, name = "Vardagsmiddagar", ownerId = 1, ownerName = "Kim", isOwn = true, recipeCount = 3)
    val quick = RecipeBook(id = 12, name = "Snabbt", kind = RecipeBookKind.SMART, ownerId = 1, ownerName = "Kim", isOwn = true)
    val party = RecipeBook(id = 13, name = "Kalas och fest", kind = RecipeBookKind.BOOK, ownerId = 2, ownerName = "Alex", recipeCount = 2)
    val baking = RecipeBook(id = 14, name = "Mormors bakbok", kind = RecipeBookKind.BOOK, ownerId = 2, ownerName = "Alex", recipeCount = 5)

    val recipes = SampleRecipes.overviews.take(4)

    fun books(readOnly: Boolean = false) = BooksUiState(
        loading = false,
        favoriteCount = 6,
        wantToCookCount = 3,
        own = listOf(dinners, quick),
        shared = listOf(party, baking),
        readOnly = readOnly,
    )

    fun ownBook() = BookRecipesUiState(
        loading = false,
        title = dinners.name,
        description = "Det vi lagar en vanlig tisdag.",
        kind = RecipeBookKind.BOOK,
        isOwn = true,
        recipes = recipes.take(3),
        canRemove = true,
    )

    fun sharedBook() = BookRecipesUiState(
        loading = false,
        title = party.name,
        kind = RecipeBookKind.BOOK,
        isOwn = false,
        ownerName = "Alex",
        recipes = recipes.drop(2),
    )

    fun favorites(readOnly: Boolean = false) = BookRecipesUiState(
        loading = false,
        kind = RecipeBookKind.FAVORITES,
        isOwn = false,
        recipes = recipes,
        ownFavoriteIds = recipes.take(2).map { it.id }.toSet(),
        readOnly = readOnly,
        canRemove = !readOnly,
    )

    fun wantToCook() = BookRecipesUiState(loading = false, kind = RecipeBookKind.WANT_TO_COOK, recipes = recipes.take(2), canRemove = true)

    val buttons = RecipeBookButtonsState(
        isFavorite = true,
        wantToCook = false,
        favoriteOf = listOf("Alex"),
        books = listOf(BookChoice(dinners.id, dinners.name, contains = true), BookChoice(15, "Helgmat", contains = false)),
        loaded = true,
    )

    val collections = RecipeCollectionsState(favoriteCount = 6, wantToCookCount = 3, bookCount = 4, favoriteIds = setOf(recipes[0].id))
}

/** Every books screen and state, for the screenshot and accessibility tests (one list, so both cover the same). */
internal enum class BookShot(val content: @Composable () -> Unit) {
    BOOKS({ Books(BookSamples.books()) }),
    BOOKS_GUEST({ Books(BookSamples.books(readOnly = true)) }),
    BOOKS_EMPTY({ Books(BooksUiState(loading = false)) }),
    BOOKS_CREATE({ Books(BookSamples.books().copy(creating = true)) }),
    OWN_BOOK({ Recipes(BookSamples.ownBook(), BookSamples.dinners.name) }),
    SHARED_BOOK({ Recipes(BookSamples.sharedBook(), BookSamples.party.name) }),
    EMPTY_BOOK({ Recipes(BookSamples.ownBook().copy(recipes = emptyList()), BookSamples.dinners.name) }),
    FAVORITES({ Recipes(BookSamples.favorites(), "Favoriter") }),
    FAVORITES_GUEST({ Recipes(BookSamples.favorites(readOnly = true), "Favoriter") }),
    WANT_TO_COOK({ Recipes(BookSamples.wantToCook(), "Vill laga", onPlanMeal = {}) }),
    WANT_TO_COOK_EMPTY({ Recipes(BookSamples.wantToCook().copy(recipes = emptyList()), "Vill laga", onPlanMeal = {}) }),
    MISSING({ Recipes(BookRecipesUiState(loading = false, missing = true), "") }),
    MASTER_DETAIL({ MasterDetail() }),
    BUTTONS({ Surface { RecipeBookButtons(state = BookSamples.buttons, onAction = {}) } }),
    BUTTONS_GUEST({ Surface { RecipeBookButtons(state = BookSamples.buttons.copy(readOnly = true), onAction = {}) } }),
    ADD_TO_BOOK({ Surface { AddToBookContent(state = BookSamples.buttons, onAction = {}) } }),
    LIST_WITH_COLLECTIONS({
        RecipeListScreen(state = listState(), onAction = {}, onOpenRecipe = {}, collections = BookSamples.collections)
    }),
    LIST_WITH_COLLECTIONS_GUEST({
        RecipeListScreen(state = listState(), onAction = {}, onOpenRecipe = {}, collections = BookSamples.collections.copy(readOnly = true))
    }),
    READ_ONLY_SHEET({ Surface { ReadOnlySheetContent(onDismiss = {}) } }),
}

@Composable
private fun Books(state: BooksUiState) = BooksScreen(
    state = state,
    onAction = {},
    onOpenBook = {},
    onOpenFavorites = {},
    onOpenWantToCook = {},
    onBack = {},
)

@Composable
private fun Recipes(state: BookRecipesUiState, title: String, onPlanMeal: ((Long) -> Unit)? = null) = BookRecipesScreen(
    state = state,
    title = title,
    onAction = {},
    onOpenRecipe = {},
    onBack = {},
    onPlanMeal = onPlanMeal,
)

/** The books beside the open book, like the shell's list-detail scene on wide windows (Stitch receptbok_master_detail_surfplatta). */
@Composable
private fun MasterDetail() {
    Row {
        BooksScreen(
            state = BookSamples.books(),
            onAction = {},
            onOpenBook = {},
            onOpenFavorites = {},
            onOpenWantToCook = {},
            onBack = {},
            selectedBookId = BookSamples.dinners.id,
            modifier = Modifier.width(LIST_PANE_WIDTH),
        )
        VerticalDivider(Modifier.fillMaxHeight())
        BookRecipesScreen(
            state = BookSamples.ownBook(),
            title = BookSamples.dinners.name,
            onAction = {},
            onOpenRecipe = {},
            onBack = {},
            modifier = Modifier.weight(1f),
        )
    }
}

private val LIST_PANE_WIDTH = 360.dp
