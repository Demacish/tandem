package casa.moller.tandem.feature.cook.kitchen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.core.model.timer.CookTimer

/**
 * Fas 5 (kok lane, plan §8.7 "Köksskärmsläge", #41): the kitchen display for `KitchenKey`.
 *
 * Navigation contract (docs/BYGGE.md "Köksskärmen"): the shell pushes `KitchenKey` from Idag's
 * "Köksskärm" and from Mer; [onStartCooking] pushes cook mode on top (back returns here) and
 * [onOpenTimer] opens a timer's cook mode like the timer list does.
 */
@Composable
fun KitchenRoute(
    onClose: () -> Unit,
    onStartCooking: (recipeId: Long, servings: Double?) -> Unit,
    onOpenTimer: (CookTimer) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: KitchenViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    KitchenScreen(
        state = state,
        onAction = viewModel::onAction,
        onClose = onClose,
        onStartCooking = onStartCooking,
        onOpenTimer = onOpenTimer,
        modifier = modifier,
    )
}
