package casa.moller.tandem.feature.shopping

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.feature.shopping.ShoppingSamples.cream
import casa.moller.tandem.feature.shopping.ShoppingSamples.creamGrams
import casa.moller.tandem.feature.shopping.ShoppingSamples.eggs
import casa.moller.tandem.feature.shopping.ShoppingSamples.milk
import casa.moller.tandem.feature.shopping.ui.EditEntryPanel
import casa.moller.tandem.feature.shopping.ui.SHOPPING_LIST_TAG
import casa.moller.tandem.feature.shopping.ui.ShoppingScreen
import casa.moller.tandem.feature.shopping.ui.shoppingRowTag
import casa.moller.tandem.feature.shopping.ui.shoppingTileTag
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Fas 5, lane handla (plan §8.2, §6.6): merged rows and the grid on screen. A merged row and a
 * tile are each ONE TalkBack node with amount, food, aisle, recipes, how many rows it merges, the
 * state and the actions; tap checks, long press opens the edit sheet; the menu switches the view.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = Devices.PHONE_TALL)
class MergedRowsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val actions = mutableListOf<ShoppingAction>()

    private fun string(id: Int, vararg args: Any): String = ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

    private fun plural(id: Int, count: Int): String =
        ApplicationProvider.getApplicationContext<Context>().resources.getQuantityString(id, count, count)

    private fun merged(gridView: Boolean = false, readOnly: Boolean = false, checkedExpanded: Boolean = false) = ShoppingSamples.state(
        entries = ShoppingSamples.mergedEntries,
        conversions = ShoppingSamples.conversions,
        gridView = gridView,
        readOnly = readOnly,
        checkedExpanded = checkedExpanded,
    )

    private fun show(state: ShoppingUiState) {
        composeRule.setContent {
            ShoppingTestTheme { ShoppingScreen(state = state, onAction = { actions += it }) }
        }
    }

    private val creamDescription: String
        get() = "5 dl + 1 st grädde, Mejeri, Potatisgratäng, Kladdkaka, " + plural(R.plurals.shopping_row_merged, 3)

    @Test
    fun aMergedRowIsOneNodeAndActsForAllItsEntries() {
        show(merged())
        // One row for the three grädde entries, keyed by the first; none for the others.
        composeRule.onAllNodesWithTag(shoppingRowTag(creamGrams.id)).assertCountEquals(0)
        val node = composeRule.onNodeWithTag(shoppingRowTag(cream.id))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(creamDescription)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.shopping_row_unchecked)))
        node.performClick()
        node.performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(listOf(ShoppingAction.ToggleChecked(cream.id), ShoppingAction.OpenEdit(cream.id)), actions)
    }

    @Test
    fun aTileIsOneNodeWithNameAmountAisleStateAndActions() {
        show(merged(gridView = true))
        composeRule.onAllNodesWithTag(shoppingRowTag(cream.id)).assertCountEquals(0)
        val tile = composeRule.onNodeWithTag(shoppingTileTag(cream.id)).fetchSemanticsNode()
        assertEquals(listOf(creamDescription), tile.config[SemanticsProperties.ContentDescription])
        assertEquals(string(R.string.shopping_row_unchecked), tile.config[SemanticsProperties.StateDescription])
        assertEquals(Role.Checkbox, tile.config[SemanticsProperties.Role])
        assertEquals(ToggleableState.Off, tile.config[SemanticsProperties.ToggleableState])
        val custom = tile.config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
        assertEquals(listOf(string(R.string.shopping_action_later), string(R.string.shopping_action_edit)), custom)
        // ONE node: the texts inside are not separate TalkBack stops.
        assertEquals(0, tile.children.size)

        composeRule.onNodeWithTag(shoppingTileTag(milk.id)).performClick()
        composeRule.onNodeWithTag(shoppingTileTag(cream.id)).performSemanticsAction(SemanticsActions.OnLongClick)
        tile.config[SemanticsActions.CustomActions].first { it.label == string(R.string.shopping_action_later) }.action()
        assertEquals(
            listOf(ShoppingAction.ToggleChecked(milk.id), ShoppingAction.OpenEdit(cream.id), ShoppingAction.ToggleLater(cream.id)),
            actions,
        )
    }

    @Test
    fun checkedTilesSayCheckedAndOfferUncheck() {
        show(merged(gridView = true, checkedExpanded = true))
        composeRule.onNodeWithTag(SHOPPING_LIST_TAG).performScrollToNode(hasTestTag(shoppingTileTag(eggs.id)))
        val tile = composeRule.onNodeWithTag(shoppingTileTag(eggs.id)).fetchSemanticsNode()
        assertEquals(string(R.string.shopping_row_checked), tile.config[SemanticsProperties.StateDescription])
        assertEquals(ToggleableState.On, tile.config[SemanticsProperties.ToggleableState])
        // A checked tile has no "köp senare".
        assertEquals(listOf(string(R.string.shopping_action_edit)), tile.config[SemanticsActions.CustomActions].map { it.label })
    }

    @Test
    fun aGuestsTilesDoNothing() {
        show(merged(gridView = true, readOnly = true))
        val tile = composeRule.onNodeWithTag(shoppingTileTag(cream.id)).fetchSemanticsNode()
        assertEquals(null, tile.config.getOrNull(SemanticsActions.OnClick))
        assertEquals(null, tile.config.getOrNull(SemanticsActions.CustomActions))
        assertEquals(listOf(creamDescription), tile.config[SemanticsProperties.ContentDescription])
    }

    @Test
    fun theMenuSwitchesBetweenRowsAndTheGrid() {
        show(merged())
        composeRule.onNodeWithContentDescription(string(R.string.shopping_more)).performClick()
        composeRule.onNodeWithText(string(R.string.shopping_view_grid)).performClick()
        assertEquals(listOf<ShoppingAction>(ShoppingAction.ToggleGrid), actions)
    }

    @Test
    fun theMenuOffersTheListBackInTheGrid() {
        show(merged(gridView = true))
        composeRule.onNodeWithContentDescription(string(R.string.shopping_more)).performClick()
        composeRule.onNodeWithText(string(R.string.shopping_view_list)).performClick()
        assertEquals(listOf<ShoppingAction>(ShoppingAction.ToggleGrid), actions)
    }

    @Test
    fun theEditSheetOfAMergedRowSwitchesBetweenItsEntries() {
        val state = EditEntryState(
            entry = cream,
            food = "grädde",
            amount = "3",
            unit = "dl",
            categoryId = ShoppingSamples.dairy.id,
            parts = listOf(cream, creamGrams, ShoppingSamples.creamCarton),
        )
        composeRule.setContent {
            ShoppingTestTheme {
                EditEntryPanel(state = state, format = ShoppingFormat(Locale.ENGLISH), onAction = { actions += it })
            }
        }
        composeRule.onNodeWithText(string(R.string.shopping_edit_parts)).assertExists()
        composeRule.onNodeWithText(string(R.string.shopping_edit_part, "200 g", "Kladdkaka")).performClick()
        assertEquals(listOf<ShoppingAction>(ShoppingAction.EditPart(creamGrams.id)), actions)
    }
}
