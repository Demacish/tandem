package casa.moller.tandem.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.ui.sample.SafeModeInfoSample
import casa.moller.tandem.core.ui.sample.ServerTooOldSample
import casa.moller.tandem.core.ui.sample.StatusBannersSample
import casa.moller.tandem.core.ui.sample.UI_SAMPLE_ROOT_TAG
import casa.moller.tandem.core.ui.sample.UiSampleSurface
import casa.moller.tandem.core.ui.status.SafeModeBanner
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The server status components (feature #2): safe mode banners, the too-old notice and the safe
 * mode explanation, light and dark, English and Swedish. Recorded in CI (screenshots artifact).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-port-xhdpi")
class ServerStatusScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun banners() = capture("status_banners_light") { StatusBannersSample() }

    @Test
    fun bannersDark() = capture("status_banners_dark", darkTheme = true) { StatusBannersSample() }

    @Test
    @Config(qualifiers = "sv-w412dp-h915dp-port-xhdpi")
    fun bannersSwedish() {
        capture("status_banners_light_sv") { StatusBannersSample() }
        composeRule.onNodeWithText("Tandoor 2.7.3 är inte testad ännu").assertIsDisplayed()
        composeRule.onNodeWithText("Offline: visar det som sparats i enheten").assertIsDisplayed()
    }

    @Test
    fun tooOld() = capture("server_too_old_light") { ServerTooOldSample() }

    @Test
    fun tooOldDark() = capture("server_too_old_dark", darkTheme = true) { ServerTooOldSample() }

    @Test
    fun safeModeInfo() = capture("safe_mode_info_light", fillScreen = true) { SafeModeInfoSample() }

    @Test
    fun safeModeInfoDark() = capture("safe_mode_info_dark", darkTheme = true, fillScreen = true) { SafeModeInfoSample() }

    @Test
    @Config(qualifiers = "sv-w412dp-h915dp-port-xhdpi")
    fun safeModeInfoSwedish() = capture("safe_mode_info_light_sv", fillScreen = true) { SafeModeInfoSample() }

    @Test
    fun bannerIsOneButton() {
        var clicks = 0
        composeRule.setContent {
            UiSampleSurface(darkTheme = false) {
                SafeModeBanner(version = "2.7.3", compatibility = ServerCompatibility.UNTESTED_NEWER, onClick = { clicks++ })
            }
        }
        composeRule.onNodeWithText("Tandoor 2.7.3 is not tested yet").performClick()
        assertEquals(1, clicks)
    }

    private fun capture(name: String, darkTheme: Boolean = false, fillScreen: Boolean = false, sample: @Composable () -> Unit) {
        composeRule.setContent {
            UiSampleSurface(darkTheme = darkTheme, fillScreen = fillScreen) { sample() }
        }
        composeRule.onNodeWithTag(UI_SAMPLE_ROOT_TAG).captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/ui/$name.png")
    }
}
