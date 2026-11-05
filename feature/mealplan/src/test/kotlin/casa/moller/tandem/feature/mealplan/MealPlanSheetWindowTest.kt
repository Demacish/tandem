package casa.moller.tandem.feature.mealplan

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.feature.mealplan.Devices.PHONE
import casa.moller.tandem.feature.mealplan.Devices.PHONE_LANDSCAPE
import casa.moller.tandem.feature.mealplan.Devices.TABLET
import casa.moller.tandem.feature.mealplan.Devices.TABLET_PORTRAIT
import casa.moller.tandem.feature.mealplan.ui.WEEK_SUGGESTIONS_ACCEPT_TAG
import casa.moller.tandem.feature.mealplan.ui.WeekScreen
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
 * Fas S (rester lane, Phase 2's open issue): Veckan's move sheet ("Flytta", the week's only
 * `ModalBottomSheet`) as a WINDOW over the week: the whole screen with every window
 * (`captureScreenRoboImage`) and the ATF checks of each window. "Planera måltid" is not a sheet
 * since 0.3.0 (fix-ui: `PlanMealKey` is a full page), so its page tests cover it as it is shown.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class MealPlanSheetWindowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun moveSheetWindow() = captureWindow("phone_window_week_move_light_sv")

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun moveSheetWindowLandscapeDark() = captureWindow("phone_landscape_window_week_move_dark", darkTheme = true)

    // fas4-kvar: the suggestion sheet keeps "Lägg n måltider i veckan" below its scrolling content
    // (WeekSuggestionsSheetLayout) on a phone in both orientations, a tablet and at font scale 2.
    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun suggestionsSheetWindow() = captureSuggestions("phone_window_week_suggestions_light_sv")

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun suggestionsSheetWindowLandscapeDark() = captureSuggestions("phone_landscape_window_week_suggestions_dark", darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun suggestionsSheetWindowFontScale2() = captureSuggestions("phone_window_week_suggestions_font2_light_sv", fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$TABLET_PORTRAIT")
    fun suggestionsSheetWindowTabletPortrait() = captureSuggestions("tablet_portrait_window_week_suggestions_light_sv")

    @Test
    @Config(qualifiers = TABLET)
    fun suggestionsSheetWindowTablet() = captureSuggestions("tablet_window_week_suggestions_light")

    private fun captureSuggestions(name: String, darkTheme: Boolean = false, fontScale: Float = 1f) {
        val state = MealPlanSamples.state().copy(suggestions = MealPlanSamples.suggestionsKeywordState)
        captureWindow(name, darkTheme, fontScale, state)
        composeRule.onNodeWithTag(WEEK_SUGGESTIONS_ACCEPT_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureWindow(
        name: String,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        state: WeekUiState = MealPlanSamples.state(move = MealPlanSamples.moveState),
    ) {
        composeRule.setContent {
            MealPlanTestTheme(darkTheme = darkTheme, fontScale = fontScale) {
                WeekScreen(state = state, onAction = {})
            }
        }
        composeRule.waitForIdle()
        val windows = composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().size
        assertTrue("The sheet is a window of its own ($windows roots)", windows >= 2)
        captureScreenRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/mealplan/$name.png")
        composeRule.assertAccessible()
    }
}
