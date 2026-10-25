package casa.moller.tandem.feature.shopping

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.feature.shopping.Devices.PHONE
import casa.moller.tandem.feature.shopping.Devices.TABLET
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** en-XA: every string longer and accented, plan 6.7. */
private const val LONG_TEXT = "en-rXA"

/** ar-XB: every string mirrored, right to left (plan 6.7). */
private const val RIGHT_TO_LEFT = "ar-rXB-ldrtl"

/**
 * The Handla tab and store mode in Android's pseudo-locales (plan 5.6/6.7): longer text on a phone
 * and right to left on a phone and a tablet (`*_en_xa.png`, `*_ar_xb.png`).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class ShoppingPseudoLocaleScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "$LONG_TEXT-$PHONE")
    fun phoneLongText() = capture("phone_shopping_light_en_xa", ShoppingShot.LIST)

    @Test
    @Config(qualifiers = "$RIGHT_TO_LEFT-$PHONE")
    fun phoneRightToLeft() = capture("phone_shopping_light_ar_xb", ShoppingShot.LIST)

    @Test
    @Config(qualifiers = "$RIGHT_TO_LEFT-$TABLET")
    fun tabletRightToLeftDark() = capture("tablet_shopping_dark_ar_xb", ShoppingShot.LIST, darkTheme = true)

    @Test
    @Config(qualifiers = "$LONG_TEXT-$PHONE")
    fun storeModeLongText() = capture("phone_store_mode_light_en_xa", ShoppingShot.STORE_MODE)

    /** Guards the screenshots above: without generated pseudo-locales they would silently show English. */
    @Test
    @Config(qualifiers = "$LONG_TEXT-$PHONE")
    fun theLongTextLocaleIsGenerated() {
        val title = ApplicationProvider.getApplicationContext<Context>().getString(R.string.shopping_title)
        assertTrue("en-XA must be pseudo-localized, got '$title'", title != "Shopping" && title.startsWith("["))
    }

    private fun capture(name: String, shot: ShoppingShot, darkTheme: Boolean = false) {
        composeRule.setContent {
            ShoppingTestTheme(darkTheme = darkTheme) { shot.content() }
        }
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/shopping/$name.png")
    }
}
