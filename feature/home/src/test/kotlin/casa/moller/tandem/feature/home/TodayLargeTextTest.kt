package casa.moller.tandem.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.testing.accessibility.assertNoWordBreaks
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.core.ui.navigation.LocalAppChrome
import casa.moller.tandem.feature.home.ui.TODAY_LIST_TAG
import casa.moller.tandem.feature.home.ui.TodayScreen
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The tester's phone at the largest text and display size (Galaxy S21 FE: 320 × 693 dp). */
private const val PHONE_LARGEST = "w320dp-h693dp-port-xhdpi"

/**
 * Today at font scale 2.0 on 320 dp (device test 2026-11-14, B22): the sync row "Synkar med
 * Tandoor…" with "Synka nu" under it instead of beside it, and the carousels' cards wide enough
 * for their names. No word breaks inside a word, the ATF checks pass, screenshots in the goldens.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_LARGEST)
class TodayLargeTextTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "sv-$PHONE_LARGEST")
    fun syncRowSwedish() {
        show(running = true)
        scrollTo("sync")
        capture("phone_largest_devicetest_today_sync_font2_light_sv")
        val text = string(R.string.home_sync_running)
        val button = string(R.string.home_sync_now)
        composeRule.assertNoWordBreaks(text, button)
        val textBounds = composeRule.onNodeWithText(text).getUnclippedBoundsInRoot()
        val buttonBounds = composeRule.onNodeWithText(button).getUnclippedBoundsInRoot()
        assertTrue("\"Synka nu\" under the text", buttonBounds.top >= textBounds.bottom)
        composeRule.assertAccessible()
    }

    @Test
    fun syncRowDark() {
        show(darkTheme = true)
        scrollTo("sync")
        capture("phone_largest_devicetest_today_sync_font2_dark")
        composeRule.assertNoWordBreaks(string(R.string.home_sync_now))
        composeRule.assertAccessible()
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_LARGEST")
    fun makeNowCarouselSwedish() {
        show()
        scrollTo("make-now")
        capture("phone_largest_devicetest_today_carousel_font2_light_sv")
        val pantry = samplePantryState()
        composeRule.assertNoWordBreaks(string(R.string.home_make_now_title), pantry.makeNow.first().name)
        composeRule.assertAccessible()
    }

    @Test
    fun recipeCarousel() {
        val state = sampleHomeState()
        show()
        scrollTo("section-${state.sections.first().type}")
        capture("phone_largest_devicetest_today_section_font2_light")
        composeRule.assertNoWordBreaks(state.sections.first().recipes.first().name)
        composeRule.assertAccessible()
    }

    private fun show(running: Boolean = false, darkTheme: Boolean = false) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TandemTheme(darkTheme = darkTheme) {
                    CompositionLocalProvider(LocalAppChrome provides AppChrome()) {
                        TodayScreen(
                            state = sampleHomeState().copy(syncRunning = running),
                            onAction = {},
                            pantry = samplePantryState(),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun scrollTo(key: String) {
        composeRule.onNodeWithTag(TODAY_LIST_TAG).performScrollToKey(key)
        composeRule.waitForIdle()
    }

    private fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/home/$name.png")
    }

    private fun string(id: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)
}
