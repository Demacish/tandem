package casa.moller.tandem.feature.mealplan

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.feature.mealplan.Devices.PHONE
import casa.moller.tandem.feature.mealplan.Devices.PHONE_LANDSCAPE
import casa.moller.tandem.feature.mealplan.ui.WEEK_SUGGESTIONS_ACCEPT_TAG
import casa.moller.tandem.feature.mealplan.ui.WeekScreen
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
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
    // (WeekSuggestionsSheetLayout) on a phone in both orientations and at font scale 2. On a tablet
    // the week stays visible beside the sheet under the scrim, and ATF's checks of that dimmed week
    // fail as a window; the tablet sheet is covered by MealPlanScreenshotTest/MealPlanAccessibilityTest.
    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun suggestionsSheetWindow() = captureSuggestions("phone_window_week_suggestions_light_sv", accessibility = false)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun suggestionsSheetWindowLandscapeDark() = captureSuggestions("phone_landscape_window_week_suggestions_dark", darkTheme = true)

    /**
     * The scale comes from the configuration: the sheet is a window of its own and goes back to the
     * view's density, so MealPlanTestTheme's LocalDensity never reached it (the image used to be the
     * same as at scale 1). The theme stays at 1: it multiplies, and both together would make 4.
     */
    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun suggestionsSheetWindowFontScale2() {
        RuntimeEnvironment.setFontScale(2f)
        try {
            captureSuggestions("phone_window_week_suggestions_font2_light_sv", accessibility = false)
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    /**
     * [accessibility] false on a portrait phone: the sheet then fills the window, and ATF's only
     * finding is the scrim, whose clickable strip above the sheet is 1 dp high. Robolectric has no
     * status bar; on a phone the strip is at least the status bar's height (docs/BYGGE.md "Smarta
     * förslag"). The landscape window keeps ATF, and every state of the panel itself is checked in
     * MealPlanAccessibilityTest (phone, dark at font scale 2, tablet in both orientations).
     */
    private fun captureSuggestions(name: String, darkTheme: Boolean = false, fontScale: Float = 1f, accessibility: Boolean = true) {
        val state = MealPlanSamples.state().copy(suggestions = MealPlanSamples.suggestionsKeywordState)
        captureWindow(name, darkTheme, fontScale, state, accessibility = accessibility)
        composeRule.onNodeWithTag(WEEK_SUGGESTIONS_ACCEPT_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureWindow(
        name: String,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        state: WeekUiState = MealPlanSamples.state(move = MealPlanSamples.moveState),
        accessibility: Boolean = true,
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
        if (accessibility) composeRule.assertAccessible()
    }
}
