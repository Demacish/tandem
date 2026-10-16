package casa.moller.tandem.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.settings.RecoveryReason
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.recovery.PendingExportState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ATF accessibility checks (core:testing `assertAccessible`, plan 5.6 and Fas 1 "Klart när") of the
 * recovery screen: with and without the export of pending changes (a disabled button explains
 * why), light and dark, font scale 2 and a tablet.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-port-xhdpi")
class RecoveryAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val pending = PendingExportState(available = true, pendingChanges = 3)

    @Test
    fun recovery() = check()

    @Test
    fun recoveryDark() = check(darkTheme = true)

    @Test
    fun pendingChanges() = check(export = pending)

    @Test
    fun pendingChangesDark() = check(darkTheme = true, export = pending)

    @Test
    fun fontScale2() = check(export = pending, fontScale = 2f)

    @Test
    @Config(application = Application::class, qualifiers = "w1280dp-h800dp-land-hdpi")
    fun tablet() = check(export = pending)

    private fun check(darkTheme: Boolean = false, export: PendingExportState = PendingExportState(), fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale * density.fontScale)) {
                TandemTheme(darkTheme = darkTheme) {
                    RecoveryScreen(
                        reasons = setOf(RecoveryReason.KEY_UNUSABLE, RecoveryReason.SETTINGS_UNREADABLE),
                        onLogInAgain = {},
                        onClearAppData = {},
                        export = export,
                        onExportPendingChanges = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }
}
