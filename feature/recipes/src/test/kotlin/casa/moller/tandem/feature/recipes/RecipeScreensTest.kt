package casa.moller.tandem.feature.recipes

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.ui.R as UiR
import casa.moller.tandem.feature.recipes.list.RecipeListAction
import casa.moller.tandem.feature.recipes.list.RecipeListScreen
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
    fun detailBackArrowCallsOnBack() {
        var backs = 0
        composeRule.setContent { TestFrame { DetailWithFakes(SampleRecipes.MushroomPasta.id, onBack = { backs++ }) } }
        composeRule.onNodeWithContentDescription(string(DesignR.string.tandem_action_back)).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun tappingAnIngredientStrikesItThrough() {
        composeRule.setContent { TestFrame { DetailWithFakes(SampleRecipes.MushroomPasta.id) } }
        val mushrooms = composeRule.onAllNodes(hasText("champinjoner")).onFirst()
        mushrooms.performScrollTo().assertIsOff()
        mushrooms.performClick()
        composeRule.onAllNodes(hasText("champinjoner")).onFirst().assertIsOn()
    }

    @Test
    fun moreServingsScaleTheAmounts() {
        composeRule.setContent { TestFrame { DetailWithFakes(SampleRecipes.MushroomPasta.id) } }
        composeRule.onNodeWithContentDescription(string(UiR.string.ui_recipe_more_servings)).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasText("500 g")).onFirst().assertExists()
    }
}
