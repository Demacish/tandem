package casa.moller.tandem.feature.recipes.books

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.core.designsystem.icon.CollectionsBookmark
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.feature.recipes.R

// Phase 2 (books lane): the routes feature:shell shows for books (docs/BYGGE.md "Böcker, favoriter
// och Vill laga"): ViewModels on RecipeBookRepository, write buttons only for own books and never
// for a guest (plan 3.7, #42).

/**
 * All books; [onOpenBook], [onOpenFavorites] and [onOpenWantToCook] open one.
 *
 * @param selectedBookId the book open beside the list on wide windows ([FAVORITES_SELECTION] /
 *   [WANT_TO_COOK_SELECTION] for the collections), highlighted; null when none.
 */
@Composable
fun BooksRoute(
    onOpenBook: (Long) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenWantToCook: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    selectedBookId: Long? = null,
) {
    val viewModel: BooksViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val currentOpenBook by rememberUpdatedState(onOpenBook)
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is BooksEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))
                is BooksEffect.Created -> currentOpenBook(effect.bookId)
            }
        }
    }
    BooksScreen(
        state = state,
        onAction = viewModel::onAction,
        onOpenBook = onOpenBook,
        onOpenFavorites = onOpenFavorites,
        onOpenWantToCook = onOpenWantToCook,
        onBack = onBack,
        modifier = modifier,
        selectedBookId = selectedBookId,
        snackbarHostState = snackbarHostState,
    )
}

/** One book's recipes. [bookId] may be a temporary (negative) id of a book created offline. */
@Composable
fun BookDetailRoute(bookId: Long, onOpenRecipe: (Long) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BookRecipesRoute(BookSource.Book(bookId), titleOverride = null, onOpenRecipe, onBack, null, modifier)
}

/** The merged favourites. */
@Composable
fun FavoritesRoute(onOpenRecipe: (Long) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BookRecipesRoute(BookSource.Favorites, stringResource(R.string.books_favorites_title), onOpenRecipe, onBack, null, modifier)
}

/** "Vill laga". [onPlanMeal] plans a recipe from the queue ("Planera", plan 8.3). */
@Composable
fun WantToCookRoute(onOpenRecipe: (Long) -> Unit, onPlanMeal: (Long) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BookRecipesRoute(BookSource.WantToCook, stringResource(R.string.books_want_to_cook_title), onOpenRecipe, onBack, onPlanMeal, modifier)
}

@Composable
private fun BookRecipesRoute(
    source: BookSource,
    titleOverride: String?,
    onOpenRecipe: (Long) -> Unit,
    onBack: () -> Unit,
    onPlanMeal: ((Long) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val viewModel = hiltViewModel<BookRecipesViewModel, BookRecipesViewModel.Factory>(
        key = "book-$source",
        creationCallback = { factory -> factory.create(source) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is BookRecipesEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))
            }
        }
    }
    BookRecipesScreen(
        state = state,
        title = titleOverride ?: state.title.ifEmpty { stringResource(R.string.books_title) },
        onAction = viewModel::onAction,
        onOpenRecipe = onOpenRecipe,
        onBack = onBack,
        modifier = modifier,
        onPlanMeal = onPlanMeal,
        snackbarHostState = snackbarHostState,
    )
}

/**
 * The favourite / "Vill laga" / "Lägg i bok" buttons of recipe [recipeId]'s detail, with their own
 * ViewModel; messages go to the detail's [snackbarHostState].
 */
@Composable
fun RecipeBookButtonsRoute(recipeId: Long, snackbarHostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<RecipeBookButtonsViewModel, RecipeBookButtonsViewModel.Factory>(
        key = "recipe-books-$recipeId",
        creationCallback = { factory -> factory.create(recipeId) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is BookRecipesEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))
            }
        }
    }
    RecipeBookButtons(state = state, onAction = viewModel::onAction, modifier = modifier)
}

/** The empty pane beside the books on wide windows before one is picked ("Välj en bok"). */
@Composable
fun BookPanePlaceholder(modifier: Modifier = Modifier) {
    EmptyState(
        icon = TandemIcons.CollectionsBookmark,
        title = stringResource(R.string.books_pane_empty_title),
        body = stringResource(R.string.books_pane_empty_body),
        modifier = modifier.fillMaxSize(),
    )
}
