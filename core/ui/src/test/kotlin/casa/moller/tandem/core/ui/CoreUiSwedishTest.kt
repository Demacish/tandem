package casa.moller.tandem.core.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.ui.sample.DemoSample
import casa.moller.tandem.core.ui.sample.ErrorStateSample
import casa.moller.tandem.core.ui.sample.UI_SAMPLE_ROOT_TAG
import casa.moller.tandem.core.ui.sample.UiSampleSurface
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Swedish texts (plan 6.7: sv must be complete) and a Swedish screenshot of the demo banner. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w411dp-h1600dp-xhdpi")
class CoreUiSwedishTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun demoBannerAndChipsAreSwedish() {
        composeRule.setContent { UiSampleSurface(darkTheme = false) { DemoSample() } }
        composeRule.onNodeWithText("Demoläge").assertIsDisplayed()
        composeRule.onNodeWithText("Anslut din Tandoor").assertIsDisplayed()
        composeRule.onNodeWithText("Har du ingen Tandoor?").assertIsDisplayed()
        // Status chips expose a longer description to TalkBack.
        composeRule.onNodeWithContentDescription("Säkert läge: ändringar väntar").assertIsDisplayed()
        composeRule.onNodeWithTag(UI_SAMPLE_ROOT_TAG)
            .captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/ui/demo_banner_status_chips_light_sv.png")
    }

    @Test
    fun crashReportCardIsSwedishAndCallsBack() {
        val taps = mutableListOf<String>()
        composeRule.setContent {
            UiSampleSurface(darkTheme = false) {
                CrashReportCard(
                    onShow = { taps += "show" },
                    onCopy = { taps += "copy" },
                    onShare = { taps += "share" },
                    onDelete = { taps += "delete" },
                )
            }
        }
        composeRule.onNodeWithText("Tandem kraschade förra gången").assertIsDisplayed()
        for (label in listOf("Visa", "Kopiera", "Dela", "Ta bort")) composeRule.onNodeWithText(label).performClick()
        assertEquals(listOf("show", "copy", "share", "delete"), taps)
        composeRule.onNodeWithTag(UI_SAMPLE_ROOT_TAG)
            .captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/ui/crash_report_card_light_sv.png")
    }

    @Test
    fun errorStateUsesSharedSwedishActions() {
        composeRule.setContent { UiSampleSurface(darkTheme = false) { ErrorStateSample() } }
        composeRule.onNodeWithText("Försök igen").assertIsDisplayed()
        composeRule.onNodeWithText("Visa detaljer").performClick()
        composeRule.onNodeWithText("Dölj detaljer").assertIsDisplayed()
        composeRule.onNodeWithText("Tekniska detaljer").assertIsDisplayed()
    }
}
