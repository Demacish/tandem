package casa.moller.tandem.feature.recipes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.SampleRecipes
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The sample pasta in a 24 cm tin (Fas 5, plan §8.1 "Pannstorlek"), baked in [chosen] cm. */
private fun tinFakes(chosen: Int? = 20): DetailFakes {
    val tin: Recipe = SampleRecipes.MushroomPasta.copy(diameter = 24, diameterText = "cm")
    val fakes = DetailFakes(repository = FakeRecipeRepository(details = listOf(tin)))
    if (chosen != null) runBlocking { fakes.pans.setPanSize(tin.id, chosen) }
    return fakes
}

/**
 * Fas 5 (kok lane, plan §8.1 "Pannstorlek"): the tin size in the recipe view, scaled for a smaller
 * tin, on a tall phone (light, dark Swedish, font scale 2) and a tablet (the ingredient pane);
 * ATF on the same, and the stepper in Compose.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_TALL)
class RecipeTinScreenshotTest : RecipesScreenshotTest("phone_tall") {

    @Composable
    private fun Tin(chosen: Int? = 20) {
        DetailWithFakes(SampleRecipes.MushroomPasta.id, fakes = remember { tinFakes(chosen) })
    }

    @Test
    fun tin() = capture("detail_tin_light") { Tin() }

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun tinDarkSwedish() = capture("detail_tin_dark_sv", darkTheme = true) { Tin() }

    @Test
    fun tinFontScale2() = capture("detail_tin_font2_light", fontScale = 2f) { Tin() }

    @Test
    @Config(qualifiers = TABLET)
    fun tinTablet() = capture("detail_tin_tablet_light") { Tin() }

    @Test
    fun tinIsAccessible() {
        composeRule.setContent { TestFrame { Tin(chosen = null) } }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }

    @Test
    fun scaledTinIsAccessibleDark() {
        composeRule.setContent { TestFrame(darkTheme = true) { Tin() } }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }

    @Test
    fun theStepperScalesTheAmounts() {
        composeRule.setContent { TestFrame { Tin(chosen = null) } }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("24 cm").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Smaller tin").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Scaled for a 23 cm tin (× 0.92)").assertIsDisplayed()
        composeRule.onNodeWithText("As in the recipe (24 cm)").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Scaled for", substring = true).assertDoesNotExist()
    }
}
