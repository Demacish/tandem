package casa.moller.tandem.feature.shopping

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.model.shopping.ParsedIngredient
import casa.moller.tandem.feature.shopping.ui.AddItemPanel
import casa.moller.tandem.feature.shopping.ui.EditEntryPanel
import casa.moller.tandem.feature.shopping.ui.ShoppingHistoryScreen
import casa.moller.tandem.feature.shopping.ui.ShoppingScreen
import casa.moller.tandem.feature.shopping.ui.StoreChoiceScreen
import casa.moller.tandem.feature.shopping.ui.StoreModeScreen

/**
 * Every screen and sheet of the Handla tab with sample data, for the screenshot and
 * accessibility tests (one list, so both cover the same states).
 */
internal enum class ShoppingShot(val content: @Composable () -> Unit) {
    LIST({ ListShot(ShoppingSamples.state()) }),
    LIST_CHECKED_OPEN({ ListShot(ShoppingSamples.state(checkedExpanded = true)) }),
    LIST_EMPTY({ ListShot(ShoppingSamples.state(entries = emptyList())) }),
    LIST_OFFLINE({ ListShot(ShoppingSamples.state(pendingCount = 3, syncNotice = ShoppingSyncNotice.OFFLINE)) }),
    LIST_PAUSED({ ListShot(ShoppingSamples.state(pendingCount = 1, syncNotice = ShoppingSyncNotice.PAUSED)) }),
    LIST_GUEST({ ListShot(ShoppingSamples.state(readOnly = true)) }),
    LIST_ONE_LIST({ ListShot(ShoppingSamples.state(entries = listOf(ShoppingSamples.milk), selectedListId = 1)) }),
    ADD({ Sheet { AddItemPanel(state = addState, format = format(), onAction = {}) } }),
    ADD_RECENT({ Sheet { AddItemPanel(state = recentState, format = format(), onAction = {}) } }),
    EDIT({ Sheet { EditEntryPanel(state = editState, onAction = {}) } }),
    EDIT_OFFLINE_FOOD({ Sheet { EditEntryPanel(state = editOfflineState, onAction = {}) } }),
    STORE_MODE({ StoreModeScreen(state = ShoppingSamples.storeModeState(), onAction = {}) }),
    STORE_MODE_DONE({ StoreModeScreen(state = ShoppingSamples.storeModeState(entries = listOf(ShoppingSamples.eggs)), onAction = {}) }),
    STORE_CHOICE({ ChoiceShot(StoreChoiceUiState(false, ShoppingSamples.supermarkets, selectedId = ShoppingSamples.store.id)) }),
    STORE_CHOICE_EMPTY({ ChoiceShot(StoreChoiceUiState(loading = false)) }),
    HISTORY({ HistoryShot(ShoppingHistoryUiState(loading = false, items = ShoppingSamples.history)) }),
    HISTORY_EMPTY({ HistoryShot(ShoppingHistoryUiState(loading = false)) }),
}

@Composable
private fun ListShot(state: ShoppingUiState) = ShoppingScreen(state = state, onAction = {})

@Composable
private fun ChoiceShot(state: StoreChoiceUiState) = StoreChoiceScreen(state = state, onChoose = {}, onBack = {})

@Composable
private fun HistoryShot(state: ShoppingHistoryUiState) = ShoppingHistoryScreen(state = state, onAddAgain = {}, onBack = {})

private val recentState = AddItemState(recent = ShoppingSamples.history.map { it.foodName })

private val addState = AddItemState(
    text = "2 dl grädde, mjölk, ka",
    preview = listOf(
        ParsedIngredient("2 dl grädde", 2.0, "dl", "grädde"),
        ParsedIngredient("mjölk", 0.0, null, "mjölk"),
        ParsedIngredient("ka", 0.0, null, "ka"),
    ),
    suggestions = listOf("kaffe", "kardemumma"),
)

private val editState = EditEntryState(
    entry = ShoppingSamples.cream,
    food = "grädde",
    amount = "3",
    unit = "dl",
    categoryId = ShoppingSamples.dairy.id,
    categories = ShoppingSamples.categories.sortedBy { it.name },
)

private val editOfflineState = EditEntryState(
    entry = ShoppingSamples.butter,
    food = "smör",
    amount = "five",
    unit = "g",
    categoryId = null,
    amountError = true,
)

@Composable
private fun format(): ShoppingFormat = ShoppingFormat(LocalConfiguration.current.locales[0])

/** A bottom sheet's content as it is laid out in the sheet (the sheet window itself is the platform's). */
@Composable
private fun Sheet(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Box(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
        ) { content() }
    }
}
