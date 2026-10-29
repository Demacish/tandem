package casa.moller.tandem.feature.shopping

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.isConnectivityProblem
import casa.moller.tandem.core.model.shopping.ShoppingChange
import casa.moller.tandem.feature.shopping.navigation.ShoppingListKey
import casa.moller.tandem.feature.shopping.navigation.StoreModeKey
import casa.moller.tandem.feature.shopping.ui.ShoppingHistoryScreen
import casa.moller.tandem.feature.shopping.ui.ShoppingScreen
import casa.moller.tandem.feature.shopping.ui.StoreChoiceScreen
import casa.moller.tandem.feature.shopping.ui.StoreModeScreen
import java.util.Locale

// Phase 2 (inköp-ui lane): the routes feature:shell shows for the Handla tab (docs/BYGGE.md "Fas 2
// – kontrakt", navigation pattern "Navigering"). Each route gets its ViewModel from Hilt, shows a
// stateless XScreen(state, onAction) and turns navigation actions into the shell's callbacks.

/**
 * The shopping list (Handla tab root, or a [ShoppingListKey]).
 *
 * @param listId the named list to show, null = every entry (the tab root).
 * @param supermarketId the store to sort by, null = the device's chosen store.
 * @param onOpenStoreMode "Butiksläge" with the list and store shown.
 * @param onOpenListChoice the store chooser ([casa.moller.tandem.feature.shopping.navigation.ShoppingListChoiceKey]).
 * @param onOpenHistory the local history.
 * @param onOpenRecipe the recipe an entry came from (edit sheet).
 * @param onOpenUnpack Fas S (hemma-ui lane, #46): "Packa upp" (feature:pantry's UnpackKey, opened by
 *   the shell) when something was checked off today; null shows no prompt.
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
    onOpenUnpack: (() -> Unit)? = null,
) {
    val key = ShoppingListKey(listId, supermarketId)
    val viewModel = hiltViewModel<ShoppingViewModel, ShoppingViewModel.Factory>(
        key = "shopping-$listId-$supermarketId",
        creationCallback = { factory -> factory.create(key) },
    )
    ShoppingRoute(
        viewModel = viewModel,
        onOpenStoreMode = onOpenStoreMode,
        onOpenListChoice = onOpenListChoice,
        onOpenHistory = onOpenHistory,
        onOpenRecipe = onOpenRecipe,
        modifier = modifier,
        unpackPrompt = { if (onOpenUnpack != null) UnpackPromptRoute(onUnpack = onOpenUnpack) },
    )
}

/** [ShoppingRoute] with a given [viewModel] (tests pass one built on fakes). */
@Composable
internal fun ShoppingRoute(
    viewModel: ShoppingViewModel,
    onOpenStoreMode: (StoreModeKey) -> Unit,
    onOpenListChoice: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenRecipe: (Long) -> Unit,
    modifier: Modifier = Modifier,
    unpackPrompt: @Composable () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val currentState by rememberUpdatedState(state)
    val scope = rememberCoroutineScope()
    val snackbars = remember(snackbarHostState, scope) { ShoppingSnackbars(snackbarHostState, scope) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, snackbars, resources) {
        viewModel.effects.collect { effect -> snackbars.show(effect, resources) { viewModel.onAction(ShoppingAction.Undo) } }
    }
    // fix-ui: other members' changes only while the list is visible (none pile up while hidden).
    LaunchedEffect(viewModel, snackbars, resources, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.otherChanges.collect { snackbars.show(it, resources) {} }
        }
    }
    ShoppingScreen(
        state = state,
        onAction = { action ->
            when (action) {
                ShoppingAction.OpenStoreMode -> onOpenStoreMode(StoreModeKey(currentState.selectedListId, null))

                ShoppingAction.OpenStoreChoice -> onOpenListChoice()

                ShoppingAction.OpenHistory -> onOpenHistory()

                is ShoppingAction.OpenRecipe -> {
                    viewModel.onAction(ShoppingAction.CloseEdit)
                    onOpenRecipe(action.recipeId)
                }

                ShoppingAction.Share -> {
                    val title = currentState.lists.firstOrNull { it.id == currentState.selectedListId }?.name
                        ?: resources.getString(R.string.shopping_title)
                    val text = shareText(title, currentState.grouped.sections, currentState.amountStyle, locale, resources)
                    context.shareText(text, resources)
                }

                else -> viewModel.onAction(action)
            }
        },
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        unpackPrompt = unpackPrompt,
    )
}

/** Store mode (full screen on every width); [onClose] goes back to the list. */
@Composable
fun StoreModeRoute(key: StoreModeKey, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<StoreModeViewModel, StoreModeViewModel.Factory>(
        key = "store-mode-${key.listId}-${key.supermarketId}",
        creationCallback = { factory -> factory.create(key) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val currentState by rememberUpdatedState(state)
    val scope = rememberCoroutineScope()
    val snackbars = remember(snackbarHostState, scope) { ShoppingSnackbars(snackbarHostState, scope) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, snackbars, resources) {
        viewModel.effects.collect { effect -> snackbars.show(effect, resources) { viewModel.onAction(StoreModeAction.Undo) } }
    }
    LaunchedEffect(viewModel, snackbars, resources, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.otherChanges.collect { snackbars.show(it, resources) {} }
        }
    }
    StoreModeScreen(
        state = state,
        onAction = { action ->
            when (action) {
                StoreModeAction.Close -> onClose()

                StoreModeAction.Share -> {
                    val title = currentState.store?.name ?: resources.getString(R.string.shopping_title)
                    val text = shareText(title, currentState.sections, currentState.amountStyle, locale, resources)
                    context.shareText(text, resources)
                }

                else -> viewModel.onAction(action)
            }
        },
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

/**
 * Choosing the store the list is sorted by (the key's name is the grund lane's; the named lists
 * are chips on the list itself). [onChosen] is called with (null, null) once the choice is saved
 * on the device, so the shell goes back to the list, which follows the device's store.
 * [onDismiss] closes without a choice.
 */
@Composable
fun ShoppingListChoiceRoute(onChosen: (listId: Long?, supermarketId: Long?) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: StoreChoiceViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val currentOnChosen by rememberUpdatedState(onChosen)
    LaunchedEffect(viewModel, snackbarHostState, resources) {
        viewModel.effects.collect { effect ->
            when (effect) {
                StoreChoiceEffect.Chosen -> currentOnChosen(null, null)
                is StoreChoiceEffect.Failed -> snackbarHostState.showSnackbar(resources.errorText(effect.error))
            }
        }
    }
    StoreChoiceScreen(
        state = state,
        onChoose = viewModel::choose,
        onBack = onDismiss,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

/** The local history of bought items. */
@Composable
fun ShoppingHistoryRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: ShoppingHistoryViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbars = remember(snackbarHostState, scope) { ShoppingSnackbars(snackbarHostState, scope) }
    LaunchedEffect(viewModel, snackbars, resources) {
        viewModel.effects.collect { effect -> snackbars.show(effect, resources) {} }
    }
    ShoppingHistoryScreen(
        state = state,
        onAddAgain = viewModel::addAgain,
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

/** "Dela som text" (#13): the aisles still to buy as plain text, with plain fractions any app can show. */
internal fun shareText(title: String, sections: List<ShoppingSection>, style: AmountStyle, locale: Locale, resources: Resources): String =
    ShoppingFormat(locale, style, plainFractions = true).shareText(title, sections, resources.getString(R.string.shopping_other_category))

/** Opens Android's share sheet with [text]. */
private fun Context.shareText(text: String, resources: Resources) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    try {
        startActivity(Intent.createChooser(send, resources.getString(R.string.shopping_share_chooser)))
    } catch (ignored: ActivityNotFoundException) {
        // No app can take text (practically impossible on a phone): nothing to share with.
    }
}

internal fun Resources.messageText(message: ShoppingMessage): String = when (message) {
    is ShoppingMessage.Checked -> if (message.count == 1) {
        getString(R.string.shopping_msg_checked, message.name)
    } else {
        getQuantityString(R.plurals.shopping_msg_checked_many, message.count, message.count)
    }

    is ShoppingMessage.Unchecked -> getString(R.string.shopping_msg_unchecked, message.name)

    is ShoppingMessage.Delayed -> getString(R.string.shopping_msg_delayed, message.name)

    is ShoppingMessage.Undelayed -> getString(R.string.shopping_msg_undelayed, message.name)

    is ShoppingMessage.Added -> if (message.count == 1) {
        getString(R.string.shopping_msg_added, message.name)
    } else {
        getQuantityString(R.plurals.shopping_msg_added_many, message.count, message.count)
    }

    is ShoppingMessage.Removed -> getString(R.string.shopping_msg_removed, message.name)

    is ShoppingMessage.ClearedChecked -> getQuantityString(R.plurals.shopping_msg_cleared, message.count, message.count)

    ShoppingMessage.Saved -> getString(R.string.shopping_msg_saved)

    is ShoppingMessage.RecipeRemoved -> getString(R.string.shopping_msg_recipe_removed, message.name)
}

internal fun Resources.changeText(change: ShoppingChange): String = when (change) {
    is ShoppingChange.Added -> change.by?.let { getString(R.string.shopping_change_added_by, it, change.foodName) }
        ?: getString(R.string.shopping_change_added, change.foodName)

    is ShoppingChange.Checked -> getString(R.string.shopping_change_checked, change.foodName)

    is ShoppingChange.Removed -> getString(R.string.shopping_change_removed, change.foodName)
}

internal fun Resources.errorText(error: TandemError): String = when {
    error.dataProblem() == DataProblem.READ_ONLY -> getString(R.string.shopping_error_read_only)
    error.isConnectivityProblem -> getString(R.string.shopping_error_refresh)
    else -> getString(R.string.shopping_error_save)
}
