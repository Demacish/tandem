package casa.moller.tandem.feature.recipes

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.feature.recipes.list.RECIPE_SEARCH_FIELD_TAG
import casa.moller.tandem.feature.recipes.list.RecipeListScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S3 (the owner's screenshot): with the search field focused and the keyboard up, scrolling the
 * results moved the field out of view while the keyboard stayed, so one typed blind. A drag of the
 * results now ends the typing (the field lets go of the focus and the keyboard goes).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h780dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecipeSearchKeyboardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val longList = List(16) { SampleRecipes.overviews.first().copy(id = 2_000L + it, name = "Recept $it") }

    @Test
    fun scrollingTheResultsEndsTheTyping() {
        composeRule.setContent {
            TestFrame { RecipeListScreen(state = listState { copy(recipes = longList) }, onAction = {}, onOpenRecipe = {}) }
        }
        val field = composeRule.onNodeWithTag(RECIPE_SEARCH_FIELD_TAG)
        field.performClick()
        field.assertIsFocused()

        val list = composeRule.onAllNodes(hasScrollToNodeAction()).onFirst()
        list.performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        list.performScrollToNode(hasTestTag(RECIPE_SEARCH_FIELD_TAG))

        composeRule.onNodeWithTag(RECIPE_SEARCH_FIELD_TAG).assertIsNotFocused()
    }

    @Test
    fun scrollingWithoutTypingChangesNothing() {
        composeRule.setContent {
            TestFrame { RecipeListScreen(state = listState { copy(recipes = longList) }, onAction = {}, onOpenRecipe = {}) }
        }
        val list = composeRule.onAllNodes(hasScrollToNodeAction()).onFirst()
        list.performTouchInput { swipeUp() }
        list.performScrollToNode(hasTestTag(RECIPE_SEARCH_FIELD_TAG))
        // The field can still be focused for a new search.
        composeRule.onNodeWithTag(RECIPE_SEARCH_FIELD_TAG).performClick().assertIsFocused()
    }
}
