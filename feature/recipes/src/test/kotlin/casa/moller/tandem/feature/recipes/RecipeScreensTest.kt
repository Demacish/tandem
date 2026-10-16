package casa.moller.tandem.feature.recipes

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.R as DesignR
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.ui.R as UiR
import casa.moller.tandem.feature.recipes.list.RecipeListAction
import casa.moller.tandem.feature.recipes.list.RecipeListScreen
import casa.moller.tandem.feature.recipes.list.RecipeListUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Interaction tests of the recipe screens (stateless screens and the real detail ViewModel over fakes). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecipeScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun string(id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

    @Test
    fun tappingARecipeOpensIt() {
        val opened = mutableListOf<Long>()
        composeRule.setContent {
            TestFrame { RecipeListScreen(state = listState(), onAction = {}, onOpenRecipe = { opened += it }) }
        }
        composeRule.onNodeWithText(SampleRecipes.MushroomPasta.name).performClick()
        assertEquals(listOf(SampleRecipes.MushroomPasta.id), opened)
    }

    @Test
    fun filterChipsAndLayoutToggleSendActions() {
        val actions = mutableListOf<RecipeListAction>()
        composeRule.setContent {
            TestFrame { RecipeListScreen(state = listState(), onAction = { actions += it }, onOpenRecipe = {}) }
        }
        composeRule.onNodeWithText(string(R.string.recipes_filter_quick, 30)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.recipes_show_as_list)).performClick()
        assertEquals(listOf(RecipeListAction.ToggleQuick, RecipeListAction.ToggleLayout), actions)
    }

    @Test
    fun theKeywordPickerTogglesKeywords() {
        val actions = mutableListOf<RecipeListAction>()
        composeRule.setContent {
            TestFrame { RecipeListScreen(state = listState(), onAction = { actions += it }, onOpenRecipe = {}) }
        }
        composeRule.onNodeWithText(string(R.string.recipes_filter_keywords)).performClick()
        composeRule.onNodeWithText(SampleRecipes.Fika.name).performClick()
        assertEquals(listOf(RecipeListAction.ToggleKeyword(SampleRecipes.Fika.id)), actions)
    }

    @Test
    fun theFoodPickerTogglesFoodsAndClears() {
        val actions = mutableListOf<RecipeListAction>()
        val butter = SampleRecipes.food("smör")
        composeRule.setContent {
            TestFrame {
                RecipeListScreen(
                    state = listState(RecipeQuery(foodIds = setOf(butter.id))),
                    onAction = { actions += it },
                    onOpenRecipe = {},
                )
            }
        }
        // One food picked: the chip shows its name.
        composeRule.onNodeWithText(butter.name).performClick()
        composeRule.onNodeWithText(string(R.string.recipes_foods_title)).assertIsDisplayed()
        composeRule.onNodeWithText(SampleRecipes.food("kardemumma").name).performClick()
        composeRule.onNodeWithText(string(R.string.recipes_foods_clear)).performClick()
        assertEquals(listOf(RecipeListAction.ToggleFood(SampleRecipes.food("kardemumma").id), RecipeListAction.ClearFoods), actions)
    }

    @Test
    fun detailBackArrowCallsOnBack() {
        var backs = 0
        composeRule.setContent { TestFrame { DetailWithFakes(SampleRecipes.MushroomPasta.id, onBack = { backs++ }) } }
        composeRule.onNodeWithContentDescription(string(DesignR.string.tandem_action_back)).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun startCookingOpensCookMode() {
        var starts = 0
        composeRule.setContent { TestFrame { DetailWithFakes(SampleRecipes.MushroomPasta.id, onStartCooking = { starts++ }) } }
        composeRule.onNodeWithText(string(R.string.recipe_detail_start_cooking)).performScrollTo().performClick()
        assertEquals(1, starts)
    }

    @Test
    fun tappingAnIngredientStrikesItThrough() {
        composeRule.setContent { TestFrame { DetailWithFakes(SampleRecipes.MushroomPasta.id) } }
        // An ingredient row is one TalkBack item labelled "<amount> <food>, <note>" (plan 6.6).
        val mushrooms = composeRule.onAllNodes(hasContentDescription("champinjoner", substring = true)).onFirst()
        mushrooms.performScrollTo().assertIsOff()
        mushrooms.performClick()
        composeRule.onAllNodes(hasContentDescription("champinjoner", substring = true)).onFirst().assertIsOn()
    }

    @Test
    fun moreServingsScaleTheAmounts() {
        composeRule.setContent { TestFrame { DetailWithFakes(SampleRecipes.MushroomPasta.id) } }
        composeRule.onNodeWithContentDescription(string(UiR.string.ui_recipe_more_servings)).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasContentDescription("500 g ", substring = true)).onFirst().assertExists()
    }

    @Test
    fun anEmptyListAfterAFailedSyncSaysWhyAndCanRetry() {
        val actions = mutableListOf<RecipeListAction>()
        composeRule.setContent {
            TestFrame {
                RecipeListScreen(
                    state = RecipeListUiState(loaded = true, syncError = TandemError.Blocked),
                    onAction = { actions += it },
                    onOpenRecipe = {},
                )
            }
        }
        composeRule.onNodeWithText(string(UiR.string.ui_error_blocked_title)).assertIsDisplayed()
        composeRule.onAllNodes(hasText(string(R.string.recipes_empty_title))).assertCountEquals(0)
        composeRule.onNode(hasText(string(DesignR.string.tandem_action_retry)) and hasClickAction()).performScrollTo().performClick()
        assertEquals(listOf(RecipeListAction.Refresh), actions)
    }
}
