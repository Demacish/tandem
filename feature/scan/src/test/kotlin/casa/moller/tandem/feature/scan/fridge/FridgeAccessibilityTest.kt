package casa.moller.tandem.feature.scan.fridge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.feature.scan.Devices.PHONE_LANDSCAPE
import casa.moller.tandem.feature.scan.Devices.PHONE_TALL
import casa.moller.tandem.feature.scan.Devices.TABLET
import casa.moller.tandem.feature.scan.ScanTestTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** ATF checks (plan 5.6/6.6) of every fridge-photo and Settings → AI state, dialogs included (every window is checked). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_TALL)
class FridgeAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun everyStateLight() = checkAll(FridgeShot.entries)

    @Test
    fun everyStateDark() = checkAll(FridgeShot.entries, darkTheme = true)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun landscape() = checkAll(listOf(FridgeShot.FRIDGE_READY, FridgeShot.AI_ERRORS))

    @Test
    @Config(qualifiers = TABLET)
    fun tablet() = checkAll(listOf(FridgeShot.FRIDGE_ASKING, FridgeShot.AI_KEY_WORKS))

    @Test
    fun fontScale2() = checkAll(listOf(FridgeShot.FRIDGE_REFUSAL, FridgeShot.AI_ERRORS, FridgeShot.FRIDGE_WARNING), fontScale = 2f)

    /** One composition (a compose rule takes one setContent) that shows each shot in turn. */
    private fun checkAll(shots: List<FridgeShot>, darkTheme: Boolean = false, fontScale: Float = 1f) {
        var current by mutableStateOf(shots.first())
        composeRule.setContent { ScanTestTheme(darkTheme = darkTheme, fontScale = fontScale) { current.content() } }
        shots.forEach { shot ->
            current = shot
            composeRule.waitForIdle()
            try {
                composeRule.assertAccessible()
            } catch (e: AssertionError) {
                throw AssertionError("${shot.name}: ${e.message}", e)
            }
        }
    }
}
