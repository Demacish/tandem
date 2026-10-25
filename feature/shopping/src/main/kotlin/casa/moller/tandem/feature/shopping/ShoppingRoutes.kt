// Placeholder routes (Phase 2 grund lane): the parameters are the contract with feature:shell and
// are used once the lane builds the real screens.
@file:Suppress("UnusedParameter")

package casa.moller.tandem.feature.shopping

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.feature.shopping.navigation.StoreModeKey
import casa.moller.tandem.feature.shopping.ui.ShoppingPlaceholderScreen

// Phase 2 (grund lane): the routes feature:shell shows for the Handla tab (docs/BYGGE.md "Fas 2 –
// kontrakt", navigation pattern "Navigering"). The signatures are the contract with the shell;
// the shopping lane replaces the placeholder bodies with the real screens (ViewModels via
// hiltViewModel(), stateless XScreen(state, onAction)).

/**
 * The shopping list (Handla tab root, or a [casa.moller.tandem.feature.shopping.navigation.ShoppingListKey]).
 *
 * @param listId the named list to show, null = every entry (the tab root).
 * @param supermarketId the store to sort by, null = the device's chosen store.
 * @param onOpenStoreMode "Butiksläge" with the list and store shown.
 * @param onOpenListChoice the list / store chooser.
 * @param onOpenHistory the local history.
 * @param onOpenRecipe a recipe label on an entry.
 */
@Composable
fun ShoppingRoute(
    onOpenStoreMode: (StoreModeKey) -> Unit,
    onOpenListChoice: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenRecipe: (Long) -> Unit,
    modifier: Modifier = Modifier,
    listId: Long? = null,
    supermarketId: Long? = null,
) {
    ShoppingPlaceholderScreen(
        title = stringResource(R.string.shopping_title),
        body = stringResource(R.string.shopping_placeholder_body),
        icon = TandemIcons.ShoppingCart,
        modifier = modifier,
    )
}

/** Store mode (full screen on every width); [onClose] goes back to the list. */
@Composable
fun StoreModeRoute(key: StoreModeKey, onClose: () -> Unit, modifier: Modifier = Modifier) {
    ShoppingPlaceholderScreen(
        title = stringResource(R.string.shopping_store_mode_title),
        body = stringResource(R.string.shopping_placeholder_body),
        icon = TandemIcons.ShoppingCart,
        onNavigateBack = onClose,
        modifier = modifier,
    )
}

/**
 * Choosing the named list and store. [onChosen] gets the list (null = all) and store (null = by
 * category name); the shell then shows the list with them. [onDismiss] closes without a choice.
 */
@Composable
fun ShoppingListChoiceRoute(onChosen: (listId: Long?, supermarketId: Long?) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    ShoppingPlaceholderScreen(
        title = stringResource(R.string.shopping_list_choice_title),
        body = stringResource(R.string.shopping_placeholder_body),
        icon = TandemIcons.ShoppingCart,
        onNavigateBack = onDismiss,
        modifier = modifier,
    )
}

/** The local history of bought items. */
@Composable
fun ShoppingHistoryRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    ShoppingPlaceholderScreen(
        title = stringResource(R.string.shopping_history_title),
        body = stringResource(R.string.shopping_placeholder_body),
        icon = TandemIcons.ShoppingCart,
        onNavigateBack = onBack,
        modifier = modifier,
    )
}
