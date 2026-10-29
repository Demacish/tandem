package casa.moller.tandem.feature.shopping.recipe

import android.content.res.Resources
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.feature.shopping.R
import casa.moller.tandem.feature.shopping.errorText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * "Lägg på inköpslistan" (feature #12) for [recipeId]: the button (with "Ta bort från listan"
 * when the recipe is on the list) and its sheet. The recipe detail shows it under the
 * ingredients, cook mode on its last page; feature:shell passes it to them as a slot, so the
 * features never depend on each other.
 *
 * @param servings the servings shown there (the sheet starts with them), null = the recipe's own.
 * @param snackbarHostState the screen's snackbar ("6 varor från … ligger på inköpslistan" with "Visa listan").
 * @param onOpenShoppingList "Visa listan" (the shell switches to the Handla tab).
 */
@Composable
fun RecipeShoppingRoute(
    recipeId: Long,
    servings: Double?,
    snackbarHostState: SnackbarHostState,
    onOpenShoppingList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = hiltViewModel<RecipeShoppingViewModel, RecipeShoppingViewModel.Factory>(
        key = "recipe-shopping-$recipeId",
        creationCallback = { factory -> factory.create(recipeId) },
    )
    RecipeShoppingRoute(
        viewModel = viewModel,
        servings = servings,
        snackbarHostState = snackbarHostState,
        onOpenShoppingList = onOpenShoppingList,
        modifier = modifier,
    )
}

/** [RecipeShoppingRoute] with a given [viewModel] (tests pass one built on fakes). */
@Composable
internal fun RecipeShoppingRoute(
    viewModel: RecipeShoppingViewModel,
    servings: Double?,
    snackbarHostState: SnackbarHostState,
    onOpenShoppingList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RecipeShoppingEffects(viewModel = viewModel, snackbarHostState = snackbarHostState, onOpenShoppingList = onOpenShoppingList)
    RecipeShoppingButton(state = state, onAction = viewModel::onAction, servings = servings, modifier = modifier)
    RecipeShoppingSheet(state = state, onAction = viewModel::onAction)
}

/** The sheet's snackbars ("6 varor … ligger på inköpslistan" with "Visa listan", "Ångra" after removing). */
@Composable
private fun RecipeShoppingEffects(
    viewModel: RecipeShoppingViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenShoppingList: () -> Unit,
) {
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val currentOnOpenList by rememberUpdatedState(onOpenShoppingList)
    LaunchedEffect(viewModel, locale) { viewModel.onAction(RecipeShoppingAction.SetLocale(locale)) }
    LaunchedEffect(viewModel, snackbarHostState, resources) {
        viewModel.effects.collect { effect ->
            launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                val (text, action) = effect.text(resources)
                val result = snackbarHostState.showSnackbar(
                    message = text,
                    actionLabel = when (action) {
                        SnackbarAction.SHOW_LIST -> resources.getString(R.string.recipe_shopping_show_list)
                        SnackbarAction.UNDO -> resources.getString(R.string.shopping_undo)
                        null -> null
                    },
                    duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    when (action) {
                        SnackbarAction.SHOW_LIST -> currentOnOpenList()
                        SnackbarAction.UNDO -> viewModel.onAction(RecipeShoppingAction.Undo)
                        null -> Unit
                    }
                }
            }
        }
    }
}

/**
 * Fas S (hemma-ui lane): "Lägg det som saknas på listan" from Kan laga nu (plan §8.8): the same
 * sheet as "Lägg på inköpslistan" for [recipeId], with exactly [missingFoodIds] ticked and the rest
 * under "Finns hemma" (the pantry has them), at the recipe's own servings. Opens by itself every
 * time [request] changes (no button); its messages go to [snackbarHostState]. A recipe that is not
 * stored on the phone yet cannot be shown, which a message says.
 */
@Composable
fun RecipeMissingShoppingSheet(
    recipeId: Long,
    missingFoodIds: Set<Long>,
    request: Int,
    snackbarHostState: SnackbarHostState,
    onOpenShoppingList: () -> Unit,
) {
    val viewModel = hiltViewModel<RecipeShoppingViewModel, RecipeShoppingViewModel.Factory>(
        key = "recipe-shopping-missing-$recipeId",
        creationCallback = { factory -> factory.create(recipeId) },
    )
    RecipeMissingShoppingSheet(viewModel, missingFoodIds, request, snackbarHostState, onOpenShoppingList)
}

/** [RecipeMissingShoppingSheet] with a given [viewModel] (tests pass one built on fakes). */
@Composable
internal fun RecipeMissingShoppingSheet(
    viewModel: RecipeShoppingViewModel,
    missingFoodIds: Set<Long>,
    request: Int,
    snackbarHostState: SnackbarHostState,
    onOpenShoppingList: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    RecipeShoppingEffects(viewModel = viewModel, snackbarHostState = snackbarHostState, onOpenShoppingList = onOpenShoppingList)
    // Each request opens the sheet once (not again after a rotation recreates this composable).
    var handled by rememberSaveable(viewModel) { mutableIntStateOf(0) }
    val currentMissing by rememberUpdatedState(missingFoodIds)
    LaunchedEffect(viewModel, request) {
        if (handled == request) return@LaunchedEffect
        handled = request
        val missingFoodIds = currentMissing
        val loaded = viewModel.uiState.first { !it.loading }
        if (loaded.canAdd) {
            viewModel.onAction(RecipeShoppingAction.Open(servings = null, missingFoodIds = missingFoodIds))
        } else if (!loaded.readOnly) {
            snackbarHostState.showSnackbar(resources.getString(R.string.recipe_shopping_not_stored))
        }
    }
    RecipeShoppingSheet(state = state, onAction = viewModel::onAction)
}

private enum class SnackbarAction { SHOW_LIST, UNDO }

/** The snackbar text, and its action: "Visa listan" after adding, "Ångra" after removing (fix-ui). */
private fun RecipeShoppingEffect.text(resources: Resources): Pair<String, SnackbarAction?> = when (this) {
    is RecipeShoppingEffect.Added ->
        resources.getQuantityString(R.plurals.recipe_shopping_added, count, count, recipeName) to SnackbarAction.SHOW_LIST

    is RecipeShoppingEffect.Removed ->
        resources.getQuantityString(R.plurals.recipe_shopping_removed, count, count, recipeName) to SnackbarAction.UNDO

    is RecipeShoppingEffect.Failed -> resources.errorText(error) to null
}
