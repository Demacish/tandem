package casa.moller.tandem.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.R
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.settings.RecoveryReason
import casa.moller.tandem.recovery.PendingExportState
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
private const val MEDIUM = "w800dp-h1280dp-port-mdpi"

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

    private var exports = 0
    private val pending = PendingExportState(available = true, pendingChanges = 3)

    private fun show(darkTheme: Boolean = false, export: PendingExportState = PendingExportState(), fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale * density.fontScale)) {
                TandemTheme(darkTheme = darkTheme) {
                    RecoveryScreen(
                        reasons = reasons,
                        onLogInAgain = { logInAgain++ },
                        onClearAppData = {},
                        export = export,
                        onExportPendingChanges = { exports++ },
                    )
                }
            }
        }
    }

    private fun capture(
        name: String,
        darkTheme: Boolean = false,
        export: PendingExportState = PendingExportState(),
        fontScale: Float = 1f,
    ) {
        show(darkTheme, export, fontScale)
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
    fun phonePendingChanges() = capture("phone_recovery_pending_light", export = pending)

    @Test
    fun phonePendingChangesDark() = capture("phone_recovery_pending_dark", darkTheme = true, export = pending)

    @Test
    @Config(application = Application::class, qualifiers = "sv-$PHONE")
    fun phonePendingChangesSwedish() = capture("phone_recovery_pending_light_sv", export = pending)

    @Test
    fun phoneFontScale2() = capture("phone_recovery_font2_light", export = pending, fontScale = 2f)

    @Test
    @Config(application = Application::class, qualifiers = TABLET)
    fun tabletDark() = capture("tablet_recovery_dark", darkTheme = true, export = pending)

    @Test
    @Config(application = Application::class, qualifiers = MEDIUM)
    fun mediumPortrait() = capture("medium_recovery_light", export = pending)

    @Test
    fun exportSharesWhenChangesWait() {
        show(export = pending)
        val text = ApplicationProvider.getApplicationContext<Application>().getString(R.string.app_recovery_export_button)
        composeRule.onNodeWithText(text).performScrollTo().performClick()
        assertEquals(1, exports)
    }

    @Test
    fun exportIsDisabledWithoutAnExporter() {
        show()
        val text = ApplicationProvider.getApplicationContext<Application>().getString(R.string.app_recovery_export_button)
        composeRule.onNodeWithText(text).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun logInAgainIsOffered() {
        show()
        val text = ApplicationProvider.getApplicationContext<Application>().getString(R.string.app_recovery_log_in_again)
        // The card's heading says the same thing; the button is the clickable node.
        composeRule.onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()
        assertEquals(1, logInAgain)
    }
}
