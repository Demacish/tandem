package casa.moller.tandem.feature.recipes.books

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.feature.recipes.PHONE
import casa.moller.tandem.feature.recipes.R
import casa.moller.tandem.feature.recipes.TestFrame
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * fix-ui (plan 6.2 "Receptdetalj: Åtgärder … Planera", the 6.3 wireframe's "[Planera]"): the
 * recipe detail's "Planera" beside the book buttons plans the recipe; a guest (#42) never gets it.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
class RecipePlanButtonTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val plan: String = ApplicationProvider.getApplicationContext<Context>().getString(R.string.books_action_plan)

    @Test
    fun planeraPlansTheRecipe() {
        var planned = 0
        composeRule.setContent { TestFrame { RecipeBookButtons(state = BookSamples.buttons, onAction = {}, onPlanMeal = { planned++ }) } }
        composeRule.onNodeWithTag(RECIPE_PLAN_MEAL_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(plan).performClick()
        assertEquals(1, planned)
    }

    @Test
    fun aGuestGetsNoPlaneraAndWithoutTheCallbackThereIsNone() {
        composeRule.setContent {
            TestFrame {
                RecipeBookButtons(state = BookSamples.buttons.copy(readOnly = true), onAction = {}, onPlanMeal = {})
                RecipeBookButtons(state = BookSamples.buttons, onAction = {})
            }
        }
        composeRule.onAllNodesWithTag(RECIPE_PLAN_MEAL_TAG).assertCountEquals(0)
    }
}
