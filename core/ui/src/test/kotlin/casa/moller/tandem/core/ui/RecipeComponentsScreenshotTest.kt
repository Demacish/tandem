package casa.moller.tandem.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import casa.moller.tandem.core.ui.sample.IngredientsSample
import casa.moller.tandem.core.ui.sample.RecipeCardsSample
import casa.moller.tandem.core.ui.sample.RecipeListItemsSample
import casa.moller.tandem.core.ui.sample.RecipeMetaSample
import casa.moller.tandem.core.ui.sample.StepCardSample
import casa.moller.tandem.core.ui.sample.UI_SAMPLE_ROOT_TAG
import casa.moller.tandem.core.ui.sample.UiSampleSurface
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The recipe components (core:ui recipe/) in light and dark at phone width. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1600dp-xhdpi")
class RecipeComponentsScreenshotTest(private val theme: String) {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun recipeCards() = capture("recipe_cards") { RecipeCardsSample() }

    @Test
    fun recipeListItems() = capture("recipe_list_items") { RecipeListItemsSample() }

    @Test
    fun recipeMeta() = capture("recipe_meta") { RecipeMetaSample() }

    @Test
    fun ingredients() = capture("recipe_ingredients") { IngredientsSample() }

    @Test
    fun stepCard() = capture("recipe_step_card") { StepCardSample() }

    private fun capture(name: String, sample: @Composable () -> Unit) {
        composeRule.setContent {
            UiSampleSurface(darkTheme = theme == DARK) { sample() }
        }
        composeRule.onNodeWithTag(UI_SAMPLE_ROOT_TAG)
            .captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/ui/${name}_$theme.png")
    }

    companion object {
        private const val LIGHT = "light"
        private const val DARK = "dark"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf(LIGHT), arrayOf(DARK))
    }
}
