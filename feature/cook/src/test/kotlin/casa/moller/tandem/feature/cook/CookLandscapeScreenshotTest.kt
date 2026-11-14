package casa.moller.tandem.feature.cook

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.cook.ui.COOK_MORE_ACTIONS_TAG
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Device test 2026-11-14, round 3 (B20, K1, K2, K3): pictures of the short wide layout on the
 * tested phone in landscape, prefix `liggande_`: 780 × 360 dp at font scale 1 and 693 × 320 dp
 * (largest display size) at font scale 2, each with handsfree on and with a ringing timer above
 * its folded warning (light, dark, Swedish), two recipes, a short split-screen tablet window, and
 * the header's ⋮ menu on the 360 dp phone at font scale 2. ATF for the same states.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_SMALL_LANDSCAPE)
class CookLandscapeScreenshotTest : CookScreenshotTest("small_landscape") {

    @Test
    fun handsfree() = capture("liggande_handsfree_light", fakes = CookFakes().withLongStep()) { DeviceTest.Step2(it) }

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL_LANDSCAPE")
    fun handsfreeSwedish() = capture("liggande_handsfree_light_sv", fakes = CookFakes().withLongStep()) { DeviceTest.Step2(it) }

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL_LANDSCAPE")
    fun ringingSwedish() = capture("liggande_ringing_warning_light_sv", fakes = DeviceTest.ringingWithWarning()) {
        CookWithFakes(fakes = it, handsfree = DeviceTest.keysOnly)
    }

    @Test
    fun ringingDark() = capture("liggande_ringing_warning_dark", darkTheme = true, fakes = DeviceTest.ringingWithWarning()) {
        CookWithFakes(fakes = it, handsfree = DeviceTest.keysOnly)
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL_LANDSCAPE")
    fun twoRecipesRinging() = capture("liggande_two_ringing_handsfree_light_sv", fakes = DeviceTest.twoRingingWithWarning()) {
        CookSessionWithFakes(fakes = it, sessionKey = TwoRecipes.key, handsfree = DeviceTest.keysOnly)
    }

    @Test
    @Config(qualifiers = PHONE_ZOOMED_LANDSCAPE)
    fun zoomedHandsfreeFontScale2() = capture("liggande_zoomed_handsfree_font2_light", fontScale = 2f, fakes = CookFakes().withLongStep()) {
        DeviceTest.Step2(it)
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_ZOOMED_LANDSCAPE")
    fun zoomedHandsfreeFontScale2Swedish() = capture(
        "liggande_zoomed_handsfree_font2_light_sv",
        fontScale = 2f,
        fakes = CookFakes().withLongStep(),
    ) { DeviceTest.Step2(it) }

    @Test
    @Config(qualifiers = "sv-$PHONE_ZOOMED_LANDSCAPE")
    fun zoomedRingingFontScale2Swedish() = capture(
        "liggande_zoomed_ringing_warning_font2_light_sv",
        fontScale = 2f,
        fakes = DeviceTest.ringingWithWarning(),
    ) { CookWithFakes(fakes = it, handsfree = DeviceTest.keysOnly) }

    @Test
    @Config(qualifiers = PHONE_ZOOMED_LANDSCAPE)
    fun zoomedRingingFontScale2Dark() = capture(
        "liggande_zoomed_ringing_warning_font2_dark",
        darkTheme = true,
        fontScale = 2f,
        fakes = DeviceTest.ringingWithWarning(),
    ) { CookWithFakes(fakes = it, handsfree = DeviceTest.keysOnly) }

    @Test
    @Config(qualifiers = SPLIT_TABLET)
    fun splitScreenTablet() = capture("liggande_split_tablet_ringing_light", fakes = DeviceTest.ringingWithWarning()) {
        CookWithFakes(fakes = it, handsfree = DeviceTest.keysOnly)
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL")
    fun headerMenuFontScale2() {
        val fakes = CookFakes()
        composeRule.setContent { TestFrame(fontScale = 2f, clock = fakes.clock) { CookWithFakes(key = CookKey(pasta.id), fakes = fakes) } }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/cook/small_liggande_header_font2_light_sv.png")
        composeRule.onNodeWithTag(COOK_MORE_ACTIONS_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/cook/small_liggande_header_menu_font2_light_sv.png")
    }

    // --- ATF ---

    @Test
    fun handsfreeAccessible() = check(fakes = CookFakes().withLongStep()) { DeviceTest.Step2(it) }

    @Test
    fun ringingAccessible() = check(fakes = DeviceTest.ringingWithWarning()) { CookWithFakes(fakes = it, handsfree = DeviceTest.keysOnly) }

    @Test
    @Config(qualifiers = "sv-$PHONE_ZOOMED_LANDSCAPE")
    fun zoomedRingingFontScale2Accessible() = check(fontScale = 2f, fakes = DeviceTest.ringingWithWarning()) {
        CookWithFakes(fakes = it, handsfree = DeviceTest.keysOnly)
    }

    @Test
    @Config(qualifiers = PHONE_SMALL)
    fun headerMenuFontScale2Accessible() = check(fontScale = 2f) { CookWithFakes(fakes = it) }

    private fun check(fontScale: Float? = null, fakes: CookFakes = CookFakes(), content: @Composable (CookFakes) -> Unit) {
        composeRule.setContent { TestFrame(fontScale = fontScale, clock = fakes.clock) { content(fakes) } }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }

    private companion object {
        /** A tablet in landscape with another app beside it under it: wide but short. */
        const val SPLIT_TABLET = "w1280dp-h400dp-land-hdpi"
    }
}
