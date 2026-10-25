package casa.moller.tandem.feature.shopping

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.sync.ForegroundTarget
import casa.moller.tandem.core.testing.data.FakeForegroundSync
import casa.moller.tandem.core.testing.data.FakeIngredientParser
import casa.moller.tandem.core.testing.data.FakePreferencesRepository
import casa.moller.tandem.core.testing.data.FakeShoppingRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.FakeSupermarketRepository
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.core.testing.time.TestClock
import casa.moller.tandem.feature.shopping.ShoppingSamples.milk
import casa.moller.tandem.feature.shopping.navigation.ShoppingListKey
import casa.moller.tandem.feature.shopping.navigation.StoreModeKey
import casa.moller.tandem.feature.shopping.ui.SHOPPING_SIDE_PANE_TAG
import casa.moller.tandem.feature.shopping.ui.ShoppingScreen
import casa.moller.tandem.feature.shopping.ui.StoreModeScreen
import casa.moller.tandem.feature.shopping.ui.shoppingRowTag
import casa.moller.tandem.feature.shopping.ui.shoppingSectionTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Handla screens' behaviour and semantics (plan 6.6): one node per row, actions, guest, store mode. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = Devices.PHONE_TALL)
class ShoppingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val actions = mutableListOf<ShoppingAction>()

    private fun string(id: Int, vararg args: Any): String = ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

    private fun show(state: ShoppingUiState) {
        composeRule.setContent {
            ShoppingTestTheme { ShoppingScreen(state = state, onAction = { actions += it }) }
        }
    }

    @Test
    fun aRowIsOneNodeWithAmountFoodAisleAndState() {
        show(ShoppingSamples.state())
        composeRule.onNodeWithTag(shoppingRowTag(milk.id))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("2 l mjölk, Mejeri")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.shopping_row_unchecked)))
        val cream = composeRule.onNodeWithTag(shoppingRowTag(ShoppingSamples.cream.id)).fetchSemanticsNode()
        assertEquals(listOf("3 dl grädde, Mejeri, Potatisgratäng"), cream.config[SemanticsProperties.ContentDescription])
        val customActions = cream.config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
        assertEquals(listOf(string(R.string.shopping_action_later), string(R.string.shopping_action_edit)), customActions)
    }

    @Test
    fun tappingARowChecksItAndTheTalkBackActionsWork() {
        show(ShoppingSamples.state())
        composeRule.onNodeWithTag(shoppingRowTag(milk.id)).performClick()
        val node = composeRule.onNodeWithTag(shoppingRowTag(milk.id)).fetchSemanticsNode()
        node.config[SemanticsActions.CustomActions].first { it.label == string(R.string.shopping_action_later) }.action()
        node.config[SemanticsActions.CustomActions].first { it.label == string(R.string.shopping_action_edit) }.action()
        composeRule.onNodeWithTag(shoppingRowTag(milk.id)).performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(
            listOf(
                ShoppingAction.ToggleChecked(milk.id),
                ShoppingAction.ToggleLater(milk.id),
                ShoppingAction.OpenEdit(milk.id),
                ShoppingAction.OpenEdit(milk.id),
            ),
            actions,
        )
    }

    @Test
    fun checkAllAndTheFoldedSections() {
        show(ShoppingSamples.state())
        composeRule.onNodeWithContentDescription(string(R.string.shopping_check_all_description, "Mejeri")).performClick()
        // "Avbockat" starts folded: the checked eggs are not shown until it is opened.
        composeRule.onAllNodesWithTag(shoppingRowTag(ShoppingSamples.eggs.id)).assertCountEquals(0)
        composeRule.onNodeWithText(string(R.string.shopping_section_title_count, string(R.string.shopping_checked_title), 1)).performClick()
        assertEquals(listOf(ShoppingAction.CheckSection("category-2"), ShoppingAction.ToggleCheckedExpanded), actions)
    }

    @Test
    fun theCheckedSectionShowsStruckRowsWhenOpen() {
        show(ShoppingSamples.state(checkedExpanded = true))
        composeRule.onNodeWithTag(shoppingRowTag(ShoppingSamples.eggs.id))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.shopping_row_checked)))
        composeRule.onNodeWithTag(shoppingSectionTag("later")).assertIsDisplayed()
    }

    @Test
    fun aGuestGetsNoEditControls() {
        show(ShoppingSamples.state(readOnly = true))
        composeRule.onNodeWithText(string(R.string.shopping_guest_title)).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.shopping_add_field_hint)).assertCountEquals(0)
        composeRule.onAllNodesWithText(string(R.string.shopping_add_fab)).assertCountEquals(0)
        composeRule.onAllNodesWithText(string(R.string.shopping_check_all)).assertCountEquals(0)
        val row = composeRule.onNodeWithTag(shoppingRowTag(milk.id)).fetchSemanticsNode()
        assertTrue(SemanticsActions.OnClick !in row.config)
        assertTrue(SemanticsActions.CustomActions !in row.config)
        assertTrue(SemanticsActions.OnLongClick !in row.config)
    }

    @Test
    fun theEmptyListOffersToAddAndRecentFoods() {
        show(ShoppingSamples.state(entries = emptyList()))
        composeRule.onNodeWithText(string(R.string.shopping_empty_title)).assertIsDisplayed()
        composeRule.onNode(hasContentDescription(string(R.string.shopping_add_food_description, "havregryn"))).performClick()
        composeRule.onNodeWithText(string(R.string.shopping_add_title)).performClick()
        assertEquals(listOf(ShoppingAction.AddFood("havregryn"), ShoppingAction.OpenAdd), actions)
    }

    @Test
    fun offlineShowsThatChangesWait() {
        show(ShoppingSamples.state(pendingCount = 3, syncNotice = ShoppingSyncNotice.OFFLINE))
        composeRule.onNodeWithText(string(R.string.shopping_offline_title)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            ApplicationProvider.getApplicationContext<Context>().resources.getQuantityString(R.plurals.shopping_pending_description, 3, 3),
        ).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = Devices.TABLET)
    fun tabletsShowTheAddPaneBesideTheList() {
        show(ShoppingSamples.state())
        composeRule.onNodeWithTag(SHOPPING_SIDE_PANE_TAG).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.shopping_add_fab)).assertCountEquals(0)
        composeRule.onNodeWithText("Hörnbutiken").performClick()
        assertEquals(listOf<ShoppingAction>(ShoppingAction.SelectStore(ShoppingSamples.otherStore.id)), actions)
    }

    @Test
    fun storeModeChecksWithATapAndShowsOnlyWhatIsLeft() {
        val storeActions = mutableListOf<StoreModeAction>()
        composeRule.setContent {
            ShoppingTestTheme { StoreModeScreen(state = ShoppingSamples.storeModeState(), onAction = { storeActions += it }) }
        }
        composeRule.onAllNodesWithTag(shoppingRowTag(ShoppingSamples.eggs.id)).assertCountEquals(0)
        composeRule.onNodeWithTag(shoppingRowTag(milk.id)).performClick()
        assertEquals(listOf<StoreModeAction>(StoreModeAction.Check(milk.id)), storeActions)
    }

    @Test
    fun theRouteChecksAndUndoesThroughTheSnackbar() {
        val shopping = FakeShoppingRepository(entries = ShoppingSamples.entries, lists = ShoppingSamples.lists)
        val supermarkets = FakeSupermarketRepository(ShoppingSamples.supermarkets, ShoppingSamples.categories)
        val clock = TestClock(ShoppingSamples.now)
        val foreground = FakeForegroundSync()
        val viewModel = ShoppingViewModel(
            ShoppingListKey(),
            SavedStateHandle(),
            ShoppingSource(
                shopping,
                supermarkets,
                FakeSpacePermissionsRepository(),
                foreground,
                FakeSyncRepository(),
                FakePreferencesRepository(),
                clock,
            ),
            shopping,
            supermarkets,
            FakeIngredientParser(),
            clock,
        )
        val storeModes = mutableListOf<StoreModeKey>()
        composeRule.setContent {
            ShoppingTestTheme {
                ShoppingRoute(
                    viewModel = viewModel,
                    onOpenStoreMode = { storeModes += it },
                    onOpenListChoice = {},
                    onOpenHistory = {},
                    onOpenRecipe = {},
                )
            }
        }
        composeRule.onNodeWithTag(shoppingRowTag(milk.id)).performClick()
        composeRule.waitForIdle()
        assertTrue(shopping.entries.value.first { it.id == milk.id }.checked)
        composeRule.onNodeWithText(string(R.string.shopping_msg_checked, "mjölk")).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.shopping_undo)).performClick()
        composeRule.waitForIdle()
        assertTrue(!shopping.entries.value.first { it.id == milk.id }.checked)
        assertEquals(listOf<ForegroundTarget>(ForegroundTarget.ShoppingList), foreground.started)
    }
}
