package casa.moller.tandem.feature.shell.lock

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
import casa.moller.tandem.feature.shell.PHONE_LANDSCAPE
import casa.moller.tandem.feature.shell.PlaceholderShellFeatures
import casa.moller.tandem.feature.shell.RecordingActions
import casa.moller.tandem.feature.shell.ShellScaffold
import casa.moller.tandem.feature.shell.TABLET
import casa.moller.tandem.feature.shell.accounts.TABLET_PORTRAIT
import casa.moller.tandem.feature.shell.navigation.AppLockKey
import casa.moller.tandem.feature.shell.navigation.ShellTab
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
 * Fas 5 (las lane, plan §5.5): Mer → Lås appen on a tall phone (off, on, "Direkt", no screen lock,
 * the screen lock gone while on; light, dark, Swedish, font scale 2), the phone in landscape, Mer
 * with the section "Säkerhet", and the tablet's list-detail layout in both orientations. ATF on
 * each shot.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_TALL)
class AppLockScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun off() = page("phone_off_light", LOCK_OFF)

    @Test
    fun on() = page("phone_on_light", LOCK_ON)

    @Test
    fun onDark() = page("phone_on_dark", LOCK_ON, darkTheme = true)

    @Test
    fun immediately() = page("phone_immediately_light", LOCK_ON_IMMEDIATELY)

    @Test
    fun noScreenLock() = page("phone_no_screen_lock_light", LOCK_NO_SCREEN_LOCK)

    @Test
    fun noScreenLockDark() = page("phone_no_screen_lock_dark", LOCK_NO_SCREEN_LOCK, darkTheme = true)

    @Test
    fun screenLockGone() = page("phone_screen_lock_gone_light", LOCK_ON_SCREEN_LOCK_GONE)

    @Test
    @Config(qualifiers = "w412dp-h2600dp-port-xhdpi")
    fun onFontScale2() = page("phone_on_font_scale_2_light", LOCK_ON, fontScale = 2f)

    @Test
    @Config(qualifiers = "w412dp-h2600dp-port-xhdpi")
    fun noScreenLockFontScale2() = page("phone_no_screen_lock_font_scale_2_light", LOCK_NO_SCREEN_LOCK, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun onSwedish() = page("phone_on_light_sv", LOCK_ON)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun noScreenLockSwedish() = page("phone_no_screen_lock_light_sv", LOCK_NO_SCREEN_LOCK)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun onLandscape() = page("phone_landscape_on_light", LOCK_ON)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun moreWithTheSection() = shell("phone_more_section_light", LOCK_ON, onPage = false)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun moreWithTheSectionSwedish() = shell("phone_more_section_light_sv", LOCK_OFF, onPage = false)

    @Test
    @Config(qualifiers = TABLET)
    fun tablet() = shell("tablet_on_light", LOCK_ON)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun tabletDarkSwedish() = shell("tablet_off_dark_sv", LOCK_OFF, darkTheme = true)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletPortrait() = shell("tablet_portrait_on_light", LOCK_ON)

    private fun page(name: String, state: AppLockSettingsUiState, darkTheme: Boolean = false, fontScale: Float? = null) {
        content(name, darkTheme, fontScale) { AppLockSettingsScreen(state = state, onAction = {}, onNavigateBack = {}) }
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

    /** The whole shell: Mer with "Säkerhet", and Mer → Lås appen (list-detail on the tablet). */
    private fun shell(name: String, state: AppLockSettingsUiState, darkTheme: Boolean = false, onPage: Boolean = true) {
        val navigation = if (onPage) navigationAt(ShellTab.MORE, AppLockKey) else navigationAt(ShellTab.MORE)
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
                    appLockPage = FixedAppLockPage(state),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
        composeRule.onRoot().captureRoboImage(path(name))
    }

    private fun path(name: String) = "$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/shell/lock_$name.png"
}
