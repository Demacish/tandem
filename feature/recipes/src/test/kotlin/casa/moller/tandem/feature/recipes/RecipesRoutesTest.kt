package casa.moller.tandem.feature.recipes

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.R as DesignR
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.AppStatus
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.core.ui.navigation.LocalAppChrome
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The placeholder routes keep the navigation contract the shell relies on. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h915dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecipesRoutesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun string(id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

    @Test
    fun detailBackArrowCallsOnBack() {
        var backs = 0
        composeRule.setContent {
            TandemTheme {
                RecipeDetailRoute(recipeId = 101, onBack = { backs++ }, onOpenRecipe = {}, onOpenOnWeb = {})
            }
        }
        composeRule.onNodeWithText(string(R.string.recipe_detail_placeholder_title, 101L)).assertExists()
        composeRule.onNodeWithContentDescription(string(DesignR.string.tandem_action_back)).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun listShowsTheDemoChrome() {
        composeRule.setContent {
            TandemTheme {
                CompositionLocalProvider(LocalAppChrome provides AppChrome(status = AppStatus.Demo, showDemoBanner = true)) {
                    RecipesRoute(onOpenRecipe = {})
                }
            }
        }
        composeRule.onNodeWithText(string(R.string.recipes_placeholder_title)).assertExists()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/recipes/phone_recipes_placeholder_demo_light.png")
    }
}
