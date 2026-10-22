package casa.moller.tandem.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.core.ui.navigation.LocalAppChrome
import casa.moller.tandem.feature.home.ui.TodayScreen
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val PHONE = "w412dp-h915dp-port-xhdpi"
private const val TABLET = "w1280dp-h800dp-land-mdpi"

/** en-XA: every string longer and accented, plan 6.7. */
private const val LONG_TEXT = "en-rXA"

/** ar-XB: every string mirrored, right to left (plan 6.7). */
private const val RIGHT_TO_LEFT = "ar-rXB-ldrtl"

/**
 * The Today tab in Android's pseudo-locales (plan 5.6/6.7): longer text (en-XA) on a phone and
 * right to left (ar-XB) on a phone and an expanded tablet. Recorded in CI like the other
 * screenshots (build/outputs/roborazzi/home/*_en_xa.png, *_ar_xb.png).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class TodayPseudoLocaleScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "$LONG_TEXT-$PHONE")
    fun phoneLongText() = capture("phone_today_light_en_xa")

    @Test
    @Config(qualifiers = "$RIGHT_TO_LEFT-$PHONE")
    fun phoneRightToLeft() = capture("phone_today_light_ar_xb")

    @Test
    @Config(qualifiers = "$RIGHT_TO_LEFT-$TABLET")
    fun tabletRightToLeftDark() = capture("tablet_today_dark_ar_xb", darkTheme = true)

    /** Guards the screenshots above: without generated pseudo-locales they would silently show English. */
    @Test
    @Config(qualifiers = "$LONG_TEXT-$PHONE")
    fun theLongTextLocaleIsGenerated() {
        val title = ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.home_title)
        assertTrue("en-XA must be pseudo-localized, got '$title'", title != "Today" && title.startsWith("["))
    }

    private fun capture(name: String, darkTheme: Boolean = false) {
        composeRule.setContent {
            TandemTheme(darkTheme = darkTheme) {
                CompositionLocalProvider(LocalAppChrome provides AppChrome()) {
                    TodayScreen(state = sampleHomeState(), onAction = {})
                }
            }
        }
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/home/$name.png")
    }
}
