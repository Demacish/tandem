package casa.moller.tandem.feature.shell.watch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.shell.CONNECTED_STATE
import casa.moller.tandem.feature.shell.PHONE
import casa.moller.tandem.feature.shell.PHONE_LANDSCAPE
import casa.moller.tandem.feature.shell.PlaceholderShellFeatures
import casa.moller.tandem.feature.shell.RecordingActions
import casa.moller.tandem.feature.shell.ShellScaffold
import casa.moller.tandem.feature.shell.TABLET
import casa.moller.tandem.feature.shell.accounts.TABLET_PORTRAIT
import casa.moller.tandem.feature.shell.navigation.ShellTab
import casa.moller.tandem.feature.shell.navigation.WatchKey
import casa.moller.tandem.feature.shell.navigationAt
import casa.moller.tandem.feature.shell.settings.FixedSettingsPages
import casa.moller.tandem.feature.shell.settings.PHONE_TALL
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fas 5 (wear lane): Mer → Klockan on a tall phone (every state, light, dark, Swedish, font scale
 * 2), the phone in landscape, Mer with the watch row, and the tablet's list-detail layout in both
 * orientations. ATF on each shot.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_TALL)
class WatchScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun ready() = page("phone_ready_light", WATCH_READY)

    @Test
    fun paired() = page("phone_paired_light", WATCH_PAIRED)

    @Test
    fun pairedDark() = page("phone_paired_dark", WATCH_PAIRED, darkTheme = true)

    @Test
    fun pairedAwayOtherAccount() = page("phone_paired_away_light", WATCH_PAIRED_AWAY)

    @Test
    fun noApp() = page("phone_no_app_light", WATCH_NO_APP)

    @Test
    fun noWatchDark() = page("phone_no_watch_dark", WATCH_NO_WATCH, darkTheme = true)

    @Test
    fun unavailable() = page("phone_unavailable_light", WATCH_UNAVAILABLE)

    @Test
    fun busy() = page("phone_busy_light", WATCH_BUSY)

    @Test
    fun serverError() = page("phone_server_error_light", WATCH_SERVER_ERROR)

    @Test
    fun demo() = page("phone_demo_light", WATCH_DEMO)

    @Test
    fun guestDark() = page("phone_guest_dark", WATCH_GUEST, darkTheme = true)

    @Test
    fun pairedFontScale2() = page("phone_paired_font_scale_2_light", WATCH_PAIRED, fontScale = 2f)

    @Test
    @Config(qualifiers = "w412dp-h2600dp-port-xhdpi")
    fun noAppFontScale2() = page("phone_no_app_font_scale_2_light", WATCH_NO_APP, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun pairedSwedish() = page("phone_paired_light_sv", WATCH_PAIRED)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun guestSwedish() = page("phone_guest_light_sv", WATCH_GUEST)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun readyLandscape() = page("phone_landscape_ready_light", WATCH_READY)

    @Test
    @Config(qualifiers = PHONE)
    fun moreWithTheWatchRow() = shell("phone_more_row_light", WATCH_PAIRED, onPage = false)

    @Test
    @Config(qualifiers = TABLET)
    fun tablet() = shell("tablet_paired_light", WATCH_PAIRED)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun tabletDarkSwedish() = shell("tablet_ready_dark_sv", WATCH_READY, darkTheme = true)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletPortrait() = shell("tablet_portrait_paired_light", WATCH_PAIRED)

    private fun page(name: String, state: WatchUiState, darkTheme: Boolean = false, fontScale: Float? = null) {
        content(name, darkTheme, fontScale) { FixedWatchPage(state).Watch {} }
    }

    private fun content(name: String, darkTheme: Boolean, fontScale: Float?, content: @Composable () -> Unit) {
        composeRule.setContent {
            TandemTheme(darkTheme = darkTheme) {
                val density = LocalDensity.current
                val scaled = if (fontScale == null) density else Density(density.density, fontScale)
                CompositionLocalProvider(LocalDensity provides scaled, content = content)
            }
        }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
        composeRule.onRoot().captureRoboImage(path(name))
    }

    /** The whole shell: Mer with the watch row, and Mer → Klockan (list-detail on the tablet). */
    private fun shell(name: String, state: WatchUiState, darkTheme: Boolean = false, onPage: Boolean = true) {
        val navigation = if (onPage) navigationAt(ShellTab.MORE, WatchKey) else navigationAt(ShellTab.MORE)
        composeRule.setContent {
            TandemTheme(darkTheme = darkTheme) {
                ShellScaffold(
                    state = CONNECTED_STATE,
                    navigation = navigation,
                    actions = RecordingActions(),
                    language = AppLanguage.SYSTEM,
                    onLanguageSelected = {},
                    onConnectTandoor = {},
                    features = PlaceholderShellFeatures(CONNECTED_STATE),
                    settingsPages = FixedSettingsPages(),
                    watchPage = FixedWatchPage(state),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
        composeRule.onRoot().captureRoboImage(path(name))
    }

    private fun path(name: String) = "$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/shell/watch_$name.png"
}
