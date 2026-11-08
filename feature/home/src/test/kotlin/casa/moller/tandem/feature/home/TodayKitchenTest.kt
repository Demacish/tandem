package casa.moller.tandem.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.feature.home.ui.TodayScreen
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val KITCHEN_PHONE = "w412dp-h915dp-port-xhdpi"
private const val KITCHEN_MEDIUM = "w800dp-h1280dp-port-mdpi"
private const val KITCHEN_TABLET = "w1280dp-h800dp-land-mdpi"

/**
 * Fas 5 (kok lane, plan §8.7 "Köksskärmsläge", #41): "Köksskärm" in Idag's top bar, an icon on a
 * phone and a labelled button on a tablet (both orientations); screenshots (`home/…_today_kitchen…`),
 * ATF and the tap.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = KITCHEN_PHONE)
class TodayKitchenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var opened = 0

    private fun show(darkTheme: Boolean = false, fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale * density.fontScale)) {
                TandemTheme(darkTheme = darkTheme) {
                    TodayScreen(state = sampleHomeState(), onAction = {}, onOpenKitchen = { opened++ })
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun capture(name: String, darkTheme: Boolean = false, fontScale: Float = 1f) {
        show(darkTheme, fontScale)
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/home/$name.png")
        composeRule.assertAccessible()
    }

    @Test
    fun phone() = capture("phone_today_kitchen_light")

    @Test
    @Config(qualifiers = "sv-$KITCHEN_PHONE")
    fun phoneDarkSwedish() = capture("phone_today_kitchen_dark_sv", darkTheme = true)

    @Test
    fun phoneFontScale2() = capture("phone_today_kitchen_font2_light", fontScale = 2f)

    @Test
    @Config(qualifiers = KITCHEN_TABLET)
    fun tablet() = capture("tablet_today_kitchen_light")

    @Test
    @Config(qualifiers = "sv-$KITCHEN_MEDIUM")
    fun tabletPortraitDarkSwedish() = capture("medium_today_kitchen_dark_sv", darkTheme = true)

    @Test
    fun theIconOpensTheKitchenDisplay() {
        show()
        composeRule.onNodeWithContentDescription("Kitchen display").performClick()
        assertEquals(1, opened)
    }

    @Test
    @Config(qualifiers = KITCHEN_TABLET)
    fun theLabelledButtonOpensItOnATablet() {
        show()
        composeRule.onNodeWithText("Kitchen display").performClick()
        assertEquals(1, opened)
    }
}
