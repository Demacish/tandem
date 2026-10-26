package casa.moller.tandem.feature.shopping.recipe

import android.content.res.Resources
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.feature.shopping.R
import casa.moller.tandem.feature.shopping.errorText
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
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val currentOnOpenList by rememberUpdatedState(onOpenShoppingList)
    LaunchedEffect(viewModel, locale) { viewModel.onAction(RecipeShoppingAction.SetLocale(locale)) }
    LaunchedEffect(viewModel, snackbarHostState, resources) {
        viewModel.effects.collect { effect ->
            launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                val (text, showList) = effect.text(resources)
                val result = snackbarHostState.showSnackbar(
                    message = text,
                    actionLabel = if (showList) resources.getString(R.string.recipe_shopping_show_list) else null,
                    duration = if (showList) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) currentOnOpenList()
            }
        }
    }
    RecipeShoppingButton(state = state, onAction = viewModel::onAction, servings = servings, modifier = modifier)
    RecipeShoppingSheet(state = state, onAction = viewModel::onAction)
}

/** The snackbar text, and whether it offers "Visa listan". */
private fun RecipeShoppingEffect.text(resources: Resources): Pair<String, Boolean> = when (this) {
    is RecipeShoppingEffect.Added -> resources.getQuantityString(R.plurals.recipe_shopping_added, count, count, recipeName) to true
    is RecipeShoppingEffect.Removed -> resources.getQuantityString(R.plurals.recipe_shopping_removed, count, count, recipeName) to false
    is RecipeShoppingEffect.Failed -> resources.errorText(error) to false
}
