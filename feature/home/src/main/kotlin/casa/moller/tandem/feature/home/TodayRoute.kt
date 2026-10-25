package casa.moller.tandem.feature.home

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.feature.home.ui.TodayScreen

/**
 * The Today tab (plan 6.2 "Idag"): tonight and tomorrow from the meal plan, the carousels, and
 * the empty state "Nothing planned today. Plan the week →".
 *
 * Navigation contract (docs/BYGGE.md "Navigering"):
 * @param onOpenRecipe opens a recipe's detail (full screen on phones, beside the rail on tablets).
 * @param onOpenRecipes switches to the Recipes tab (e.g. "All recipes" after a carousel).
 * @param onOpenWeek switches to the Week tab ("Plan the week", Phase 2 content).
 * @param viewModel injected by Hilt; tests pass one with fake repositories.
 * @param onStartCooking "Laga" on tonight's meal (Phase 1b): cook mode for the recipe at the
 *   planned servings.
 * @param onOpenWantToCook Phase 2 (books lane): "Visa alla" on the "Vill laga" row.
 * @param booksViewModel Phase 2 (books lane): the "Vill laga" row and the guest banner.
 */
@Composable
fun TodayRoute(
    onOpenRecipe: (recipeId: Long) -> Unit,
    onOpenRecipes: () -> Unit,
    onOpenWeek: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
    onStartCooking: (recipeId: Long, servings: Double?) -> Unit = { _, _ -> },
    onOpenWantToCook: () -> Unit = {},
    booksViewModel: TodayBooksViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val books by booksViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val currentOpenRecipe by rememberUpdatedState(onOpenRecipe)
    val currentOpenRecipes by rememberUpdatedState(onOpenRecipes)
    val currentOpenWeek by rememberUpdatedState(onOpenWeek)
    val currentStartCooking by rememberUpdatedState(onStartCooking)

    LaunchedEffect(viewModel, resources) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is HomeEffect.RefreshFailed -> snackbarHostState.showSnackbar(resources.getString(R.string.home_refresh_failed))
            }
        }
    }

    TodayScreen(
        state = state,
        onAction = { action ->
            when (action) {
                is HomeAction.OpenRecipe -> currentOpenRecipe(action.recipeId)
                HomeAction.OpenRecipes -> currentOpenRecipes()
                HomeAction.OpenWeek -> currentOpenWeek()
                is HomeAction.StartCooking -> currentStartCooking(action.recipeId, action.servings)
                HomeAction.Refresh -> viewModel.onAction(action)
            }
        },
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        books = books,
        onOpenWantToCook = onOpenWantToCook,
    )
}
