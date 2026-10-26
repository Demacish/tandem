package casa.moller.tandem.feature.shopping.recipe

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.feature.shopping.R
import casa.moller.tandem.feature.shopping.RecipeShoppingSamples
import casa.moller.tandem.feature.shopping.ShoppingTestTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Lägg på inköpslistan" (feature #12, Phase 2 koppling lane): the button, the sheet's rows and its add button. */
@RunWith(AndroidJUnit4::class)
class RecipeShoppingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun eachIngredientIsOneCheckboxAndAtHomeIsUnticked() {
        val actions = mutableListOf<RecipeShoppingAction>()
        composeRule.setContent {
            ShoppingTestTheme { RecipeShoppingContent(state = RecipeShoppingSamples.sheet, onAction = { actions += it }) }
        }
        composeRule.onNodeWithTag(recipeShoppingRowTag(10111)).assertIsOn().performClick()
        assertEquals(RecipeShoppingAction.Toggle(10111), actions.last())
        composeRule.onNodeWithTag(recipeShoppingRowTag(10132)).assertIsOff()
        composeRule.onNodeWithTag(recipeShoppingRowTag(10112)).performScrollTo().assertIsOff()
        composeRule.onNodeWithText(context.getString(R.string.recipe_shopping_at_home)).performScrollTo()
        val add = context.resources.getQuantityString(R.plurals.recipe_shopping_add, 4, 4)
        composeRule.onNodeWithTag(RECIPE_SHOPPING_ADD_TAG).performScrollTo().assertIsEnabled().performClick()
        composeRule.onNodeWithText(add).performScrollTo()
        assertEquals(RecipeShoppingAction.Add, actions.last())
    }

    @Test
    fun nothingChosenCannotBeAdded() {
        val none = RecipeShoppingSamples.sheet.copy(toBuy = RecipeShoppingSamples.sheet.toBuy.map { it.copy(chosen = false) })
        composeRule.setContent { ShoppingTestTheme { RecipeShoppingContent(state = none, onAction = {}) } }
        composeRule.onNodeWithTag(RECIPE_SHOPPING_ADD_TAG).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.recipe_shopping_add_none)).performScrollTo()
    }

    @Test
    fun theButtonOpensTheSheetAndRemovesTheRecipeFromTheList() {
        val actions = mutableListOf<RecipeShoppingAction>()
        composeRule.setContent {
            ShoppingTestTheme { RecipeShoppingButton(state = RecipeShoppingSamples.onList, onAction = { actions += it }, servings = 6.0) }
        }
        composeRule.onNodeWithTag(RECIPE_SHOPPING_BUTTON_TAG).performClick()
        assertEquals(RecipeShoppingAction.Open(6.0), actions.last())
        composeRule.onNodeWithText(context.resources.getQuantityString(R.plurals.recipe_shopping_on_list, 4, 4)).performScrollTo()
        composeRule.onNodeWithText(context.getString(R.string.recipe_shopping_remove)).performClick()
        assertEquals(RecipeShoppingAction.RemoveFromList, actions.last())
    }

    @Test
    fun aGuestSeesNoButton() {
        val guest = RecipeShoppingSamples.onList.copy(readOnly = true)
        composeRule.setContent { ShoppingTestTheme { RecipeShoppingButton(state = guest, onAction = {}, servings = 4.0) } }
        composeRule.onRoot().assertExists()
        composeRule.onNodeWithTag(RECIPE_SHOPPING_BUTTON_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.recipe_shopping_remove)).assertDoesNotExist()
    }
}
