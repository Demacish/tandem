package casa.moller.tandem.ui

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.R
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.settings.RecoveryReason
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val PHONE = "w412dp-h915dp-port-xhdpi"
private const val TABLET = "w1280dp-h800dp-land-hdpi"

/**
 * The recovery screen (plan 4.2 step 7) on phone and tablet. A plain Application keeps Hilt out
 * of these tests; the screen itself is stateless.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE)
class RecoveryScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val reasons = setOf(RecoveryReason.KEY_UNUSABLE, RecoveryReason.SETTINGS_UNREADABLE)
    private var logInAgain = 0

    private fun show(darkTheme: Boolean = false) {
        composeRule.setContent {
            TandemTheme(darkTheme = darkTheme) {
                RecoveryScreen(reasons = reasons, onLogInAgain = { logInAgain++ }, onClearAppData = {})
            }
        }
    }

    private fun capture(name: String, darkTheme: Boolean = false) {
        show(darkTheme)
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/app/$name.png")
    }

    @Test
    fun phone() = capture("phone_recovery_light")

    @Test
    fun phoneDark() = capture("phone_recovery_dark", darkTheme = true)

    @Test
    @Config(application = Application::class, qualifiers = "sv-$PHONE")
    fun phoneSwedish() = capture("phone_recovery_light_sv")

    @Test
    @Config(application = Application::class, qualifiers = TABLET)
    fun tablet() = capture("tablet_recovery_light")

    @Test
    fun logInAgainIsOffered() {
        show()
        val text = ApplicationProvider.getApplicationContext<Application>().getString(R.string.app_recovery_log_in_again)
        composeRule.onNodeWithText(text).performScrollTo().performClick()
        assertEquals(1, logInAgain)
    }
}
