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
import casa.moller.tandem.feature.shopping.recipe.RecipeShoppingButton
import casa.moller.tandem.feature.shopping.recipe.RecipeShoppingContent
import casa.moller.tandem.feature.shopping.recipe.RecipeShoppingRow
import casa.moller.tandem.feature.shopping.recipe.RecipeShoppingUiState
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

    // fix-ui: the app's own pause after many refused requests (the circuit breaker) has its own text.
    LIST_BREAKER({ ListShot(ShoppingSamples.state(pendingCount = 2, syncNotice = ShoppingSyncNotice.BREAKER)) }),
    LIST_GUEST({ ListShot(ShoppingSamples.state(readOnly = true)) }),
    LIST_NO_HOUSEHOLD({ ListShot(ShoppingSamples.state(householdMissing = true)) }),
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

    // Phase 2 (koppling lane): "Lägg på inköpslistan" (#12) and removing a recipe from the list.
    RECIPE_SHEET({ Sheet { RecipeShoppingContent(state = RecipeShoppingSamples.sheet, onAction = {}) } }),
    RECIPE_SHEET_LISTS({ Sheet { RecipeShoppingContent(state = RecipeShoppingSamples.withLists, onAction = {}) } }),
    RECIPE_BUTTON({ Sheet { RecipeShoppingButton(state = RecipeShoppingSamples.onList, onAction = {}, servings = 4.0) } }),
    EDIT_FROM_RECIPE({ Sheet { EditEntryPanel(state = editState.copy(recipeRows = 4), onAction = {}) } }),
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
    // fix-ui: the named lists the entry can move between.
    lists = ShoppingSamples.lists,
    listIds = setOf(ShoppingSamples.lists.first().id),
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

/** Phase 2 (koppling lane): "Lägg på inköpslistan" for Krämig svamppasta (Stitch l_gg_p_listan_ark). */
internal object RecipeShoppingSamples {
    val sheet = RecipeShoppingUiState(
        loading = false,
        available = true,
        open = true,
        recipeName = "Krämig svamppasta",
        servings = 4.0,
        servingsText = "portioner",
        toBuy = listOf(
            RecipeShoppingRow(10111, "champinjoner", "400 g"),
            RecipeShoppingRow(10113, "vitlöksklyftor", "2 st", note = "pressade"),
            RecipeShoppingRow(10121, "tagliatelle", "300 g"),
            RecipeShoppingRow(10131, "vispgrädde", "2,5 dl"),
            RecipeShoppingRow(10132, "parmesan", "0,75 dl", note = "riven", chosen = false),
        ),
        atHome = listOf(
            RecipeShoppingRow(10112, "smör", "1 ½ msk", onHand = true),
            RecipeShoppingRow(10122, "salt", "", onHand = true),
        ),
    )

    val withLists = sheet.copy(lists = ShoppingSamples.lists, listId = ShoppingSamples.lists.first().id, servings = 6.0)

    /** The button under the ingredients with four of the recipe's rows on the list already. */
    val onList = sheet.copy(open = false, onList = 4, onListRecipeIds = listOf(12))
}
