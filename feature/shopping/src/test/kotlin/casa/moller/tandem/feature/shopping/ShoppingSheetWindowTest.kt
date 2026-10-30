package casa.moller.tandem.feature.shopping

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.feature.shopping.Devices.PHONE
import casa.moller.tandem.feature.shopping.Devices.PHONE_LANDSCAPE
import casa.moller.tandem.feature.shopping.recipe.RecipeShoppingSheet
import casa.moller.tandem.feature.shopping.ui.ShoppingScreen
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fas S (rester lane, Phase 2's open issue): the bottom sheets as WINDOWS, not only their content.
 * A `ModalBottomSheet` is a window of its own (a dialog over the screen), so these tests open the
 * real sheet, capture the whole screen with every window (Roborazzi's `captureScreenRoboImage`:
 * the scrim, the sheet's position, drag handle and insets) and run the ATF checks on each window
 * (`assertAccessible` checks every root). The content alone stays covered by
 * [ShoppingScreenshotTest] and [ShoppingAccessibilityTest].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class ShoppingSheetWindowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun recipeSheetWindow() = captureWindow("phone_window_recipe_shopping_light") { RecipeSheetOverPage() }

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun recipeSheetWindowLandscape() =
        captureWindow("phone_landscape_window_recipe_shopping_dark", darkTheme = true) { RecipeSheetOverPage() }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun addSheetWindow() = captureWindow("phone_window_shopping_add_light_sv") {
        ShoppingScreen(state = ShoppingSamples.state().copy(addSheetOpen = true), onAction = {})
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureWindow(name: String, darkTheme: Boolean = false, content: @Composable () -> Unit) {
        composeRule.setContent { ShoppingTestTheme(darkTheme = darkTheme) { content() } }
        composeRule.waitForIdle()
        val windows = composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().size
        assertTrue("The sheet is a window of its own ($windows roots)", windows >= 2)
        captureScreenRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/shopping/$name.png")
        composeRule.assertAccessible()
    }
}

/** The recipe's sheet ("Lägg på inköpslistan") open over a plain page. */
@Composable
private fun RecipeSheetOverPage() {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        RecipeShoppingSheet(state = RecipeShoppingSamples.sheet, onAction = {})
    }
}
