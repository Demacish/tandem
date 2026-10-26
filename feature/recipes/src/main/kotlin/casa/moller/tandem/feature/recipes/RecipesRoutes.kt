package casa.moller.tandem.feature.recipes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.toErrorText
import casa.moller.tandem.feature.recipes.books.BookRecipesEffect
import casa.moller.tandem.feature.recipes.books.RecipeBookButtonsRoute
import casa.moller.tandem.feature.recipes.books.RecipeCollection
import casa.moller.tandem.feature.recipes.books.RecipeCollectionsViewModel
import casa.moller.tandem.feature.recipes.detail.RecipeDetailEffect
import casa.moller.tandem.feature.recipes.detail.RecipeDetailScreen
import casa.moller.tandem.feature.recipes.detail.RecipeDetailViewModel
import casa.moller.tandem.feature.recipes.list.RecipeListEffect
import casa.moller.tandem.feature.recipes.list.RecipeListScreen
import casa.moller.tandem.feature.recipes.list.RecipeListViewModel

/**
 * The Recipes tab's first screen: the recipe list (plan 6.2 "Receptlista", feature #3).
 *
 * Navigation contract (docs/BYGGE.md "Navigering"): the shell shows this as the list pane of a
 * list-detail scene; [onOpenRecipe] opens the recipe detail full screen on phones and in the
 * detail pane beside the list on wider windows (the shell decides).
 *
 * @param selectedRecipeId the recipe open in the detail pane beside the list, highlighted in
 *   the list (wide windows); null when none is open.
 * @param onOpenCollection Phase 2: opens Favoriter, Vill laga or the books from the collections row.
 */
@Composable
fun RecipesRoute(
    onOpenRecipe: (recipeId: Long) -> Unit,
    modifier: Modifier = Modifier,
    selectedRecipeId: Long? = null,
    onOpenCollection: (RecipeCollection) -> Unit = {},
) {
    val viewModel: RecipeListViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Phase 2 (books lane): the collections row and the hearts (their own ViewModel).
    val collections: RecipeCollectionsViewModel = hiltViewModel()
    val collectionsState by collections.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(collections, snackbarHostState) {
        collections.effects.collect { effect ->
            when (effect) {
                is BookRecipesEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))
            }
        }
    }
    // The error's title is a composable text (core:ui toErrorText), so a failed sync is shown
    // from composition: the effect stores it, the LaunchedEffect below shows the snackbar.
    var syncFailure by remember { mutableStateOf<SyncFailure?>(null) }
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is RecipeListEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))
                is RecipeListEffect.SyncFailed -> syncFailure = SyncFailure(effect.error)
            }
        }
    }
    syncFailure?.let { failure ->
        val message = stringResource(R.string.recipes_refresh_failed, failure.error.toErrorText().title)
        LaunchedEffect(failure) {
            snackbarHostState.showSnackbar(message)
            syncFailure = null
        }
    }
    RecipeListScreen(
        state = state,
        onAction = viewModel::onAction,
        onOpenRecipe = onOpenRecipe,
        modifier = modifier,
        selectedRecipeId = selectedRecipeId,
        snackbarHostState = snackbarHostState,
        collections = collectionsState,
        onOpenCollection = onOpenCollection,
        onToggleFavorite = collections::toggleFavorite,
    )
}

/** One failed pull-to-refresh sync; a new instance per failure, so the same error shows again. */
private class SyncFailure(val error: TandemError)

/**
 * The recipe detail (plan 6.2 "Receptdetalj", feature #4) for [recipeId].
 *
 * @param onBack closes the detail (system back is handled by the shell).
 * @param onOpenRecipe opens another recipe, e.g. a step's sub-recipe (pushed on top).
 * @param onOpenOnWeb opens a URL in the browser ("Open on the web" for editing, the source URL).
 * @param onStartCooking "Börja laga" (Phase 1b): opens cook mode for the recipe at the servings
 *   shown (null: the recipe's own). Null hides the button.
 * @param shoppingAction Phase 2 (koppling lane, #12): "Lägg på inköpslistan" under the ingredients,
 *   given the servings shown and the screen's snackbar (feature:shopping's route, passed in by the
 *   shell). Null shows nothing.
 */
@Composable
fun RecipeDetailRoute(
    recipeId: Long,
    onBack: () -> Unit,
    onOpenRecipe: (recipeId: Long) -> Unit,
    onOpenOnWeb: (url: String) -> Unit,
    modifier: Modifier = Modifier,
    onStartCooking: ((recipeId: Long, servings: Double?) -> Unit)? = null,
    shoppingAction: (@Composable (servings: Double?, snackbarHostState: SnackbarHostState) -> Unit)? = null,
) {
    val viewModel = hiltViewModel<RecipeDetailViewModel, RecipeDetailViewModel.Factory>(
        key = "recipe-$recipeId",
        creationCallback = { factory -> factory.create(recipeId) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is RecipeDetailEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))
            }
        }
    }
    RecipeDetailScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = onBack,
        onOpenUrl = onOpenOnWeb,
        onOpenRecipe = onOpenRecipe,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        onStartCooking = onStartCooking?.let { start -> { start(recipeId, state.servings?.current) } },
        // Phase 2 (books lane): "Favorit", "Vill laga" and "Lägg i bok" under the title.
        bookActions = { RecipeBookButtonsRoute(recipeId = recipeId, snackbarHostState = snackbarHostState) },
        shoppingActions = shoppingAction?.let { action -> { action(state.servings?.current, snackbarHostState) } },
    )
}

/**
 * The empty detail pane beside the list on medium/expanded widths before a recipe is picked
 * ("Välj ett recept").
 */
@Composable
fun RecipeDetailPanePlaceholder(modifier: Modifier = Modifier) {
    EmptyState(
        icon = TandemIcons.MenuBook,
        title = stringResource(R.string.recipe_pane_empty_title),
        body = stringResource(R.string.recipe_pane_empty_body),
        modifier = modifier.fillMaxSize(),
    )
}
