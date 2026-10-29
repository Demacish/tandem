package casa.moller.tandem.feature.scan.fridge

import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.feature.scan.Devices.PHONE
import casa.moller.tandem.feature.scan.Devices.PHONE_LANDSCAPE
import casa.moller.tandem.feature.scan.Devices.PHONE_TALL
import casa.moller.tandem.feature.scan.Devices.TABLET
import casa.moller.tandem.feature.scan.Devices.TABLET_PORTRAIT
import casa.moller.tandem.feature.scan.ScanTestTheme
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The fridge photo and Settings → AI (ai-foto lane, plan §8.8, §6.5, B22): phone portrait and
 * landscape, tablet in both orientations, light and dark, English and Swedish, font scale 2; the
 * experimental warning and the "remove the key" question as dialogs. The camera preview is a
 * placeholder. Recorded in CI (Screenshots workflow).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class FridgeScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun ready() = capture("phone_fridge_ready_light", FridgeShot.FRIDGE_READY)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun readySwedish() = capture("phone_fridge_ready_light_sv", FridgeShot.FRIDGE_READY)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun warning() = capture("phone_fridge_warning_light_sv", FridgeShot.FRIDGE_WARNING)

    @Test
    fun warningDark() = capture("phone_fridge_warning_dark", FridgeShot.FRIDGE_WARNING, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun asking() = capture("phone_fridge_asking_dark_sv", FridgeShot.FRIDGE_ASKING, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun refusal() = capture("phone_fridge_refusal_light_sv", FridgeShot.FRIDGE_REFUSAL)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun noKey() = capture("phone_fridge_no_key_light_sv", FridgeShot.FRIDGE_NO_KEY)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun demo() = capture("phone_fridge_demo_light_sv", FridgeShot.FRIDGE_DEMO)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun guest() = capture("phone_fridge_guest_light_sv", FridgeShot.FRIDGE_GUEST)

    @Test
    fun cameraDenied() = capture("phone_fridge_camera_denied_dark", FridgeShot.FRIDGE_CAMERA_DENIED, darkTheme = true)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun readyFontScale2() = capture("phone_fridge_ready_font2_light", FridgeShot.FRIDGE_RATE_LIMITED, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE_LANDSCAPE")
    fun landscape() = capture("phone_landscape_fridge_ready_light_sv", FridgeShot.FRIDGE_READY)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun tablet() = capture("tablet_fridge_ready_light_sv", FridgeShot.FRIDGE_ASKING)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletPortrait() = capture("tablet_portrait_fridge_ready_dark", FridgeShot.FRIDGE_READY, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun reviewTruncated() = capture("phone_fridge_review_truncated_light_sv", FridgeShot.REVIEW_FRIDGE_TRUNCATED)

    @Test
    fun aiOff() = capture("phone_ai_settings_off_light", FridgeShot.AI_OFF)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun aiOffSwedish() = capture("phone_ai_settings_off_light_sv", FridgeShot.AI_OFF)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun aiNewKey() = capture("phone_ai_settings_new_key_light_sv", FridgeShot.AI_NEW_KEY)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun aiKeyWorks() = capture("phone_ai_settings_works_dark_sv", FridgeShot.AI_KEY_WORKS, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun aiErrors() = capture("phone_ai_settings_errors_light_sv", FridgeShot.AI_ERRORS)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun aiFontScale2() = capture("phone_ai_settings_font2_light", FridgeShot.AI_KEY_WORKS, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun aiDemo() = capture("phone_ai_settings_demo_light_sv", FridgeShot.AI_DEMO)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun aiRemoveDialog() = capture("phone_ai_settings_remove_light_sv", FridgeShot.AI_REMOVE_DIALOG)

    @Test
    @Config(qualifiers = "sv-$PHONE_LANDSCAPE")
    fun aiLandscape() = capture("phone_landscape_ai_settings_light_sv", FridgeShot.AI_CHECKING)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun aiTablet() = capture("tablet_ai_settings_light_sv", FridgeShot.AI_KEY_WORKS)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun aiTabletPortrait() = capture("tablet_portrait_ai_settings_dark", FridgeShot.AI_ERRORS, darkTheme = true)

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String, shot: FridgeShot, darkTheme: Boolean = false, fontScale: Float = 1f) {
        composeRule.setContent { ScanTestTheme(darkTheme = darkTheme, fontScale = fontScale) { shot.content() } }
        val path = "$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/scan/$name.png"
        if (shot.dialog) {
            composeRule.onNode(isDialog()).assertExists()
            captureScreenRoboImage(path)
        } else {
            composeRule.onRoot().captureRoboImage(path)
        }
    }
}
