package casa.moller.tandem.feature.shell.push

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.shell.CONNECTED_STATE
import casa.moller.tandem.feature.shell.PHONE_LANDSCAPE
import casa.moller.tandem.feature.shell.PlaceholderShellFeatures
import casa.moller.tandem.feature.shell.RecordingActions
import casa.moller.tandem.feature.shell.ShellScaffold
import casa.moller.tandem.feature.shell.TABLET
import casa.moller.tandem.feature.shell.accounts.TABLET_PORTRAIT
import casa.moller.tandem.feature.shell.navigation.FastPushSettingsKey
import casa.moller.tandem.feature.shell.navigation.ShellTab
import casa.moller.tandem.feature.shell.navigation.SyncSettingsKey
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
 * Fas 4 (push lane): Mer → Synk och lagring → Snabb push. Every state on a tall phone (light,
 * dark, Swedish, font scale 2), the phone in landscape, and the tablet's list-detail layout in
 * both orientations.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_TALL)
class FastPushScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun off() = page("phone_off_light", FAST_PUSH_OFF)

    @Test
    fun noDistributor() = page("phone_no_distributor_light", FAST_PUSH_NO_DISTRIBUTOR)

    @Test
    fun invalidUrl() = page("phone_invalid_url_light", FAST_PUSH_INVALID_URL)

    @Test
    fun registering() = page("phone_registering_light", FAST_PUSH_REGISTERING)

    @Test
    fun active() = page("phone_active_light", FAST_PUSH_ACTIVE)

    @Test
    fun activeDark() = page("phone_active_dark", FAST_PUSH_ACTIVE, darkTheme = true)

    @Test
    fun activeNotificationsBlocked() = page("phone_active_blocked_light", FAST_PUSH_ACTIVE_BLOCKED)

    @Test
    fun failedDark() = page("phone_failed_dark", FAST_PUSH_FAILED, darkTheme = true)

    @Test
    fun demo() = page("phone_demo_light", FAST_PUSH_DEMO)

    @Test
    fun guest() = page("phone_guest_light", FAST_PUSH_GUEST)

    @Test
    fun activeFontScale2() = page("phone_active_font_scale_2_light", FAST_PUSH_ACTIVE, fontScale = 2f)

    @Test
    fun noDistributorFontScale2() = page("phone_no_distributor_font_scale_2_light", FAST_PUSH_NO_DISTRIBUTOR, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun offSwedish() = page("phone_off_light_sv", FAST_PUSH_OFF_FILLED)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun activeSwedish() = page("phone_active_light_sv", FAST_PUSH_ACTIVE)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun failedSwedish() = page("phone_failed_light_sv", FAST_PUSH_FAILED)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun activeLandscape() = page("phone_landscape_active_light", FAST_PUSH_ACTIVE)

    @Test
    @Config(qualifiers = TABLET)
    fun tablet() = shell("tablet_active_light", FAST_PUSH_ACTIVE)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun tabletDarkSwedish() = shell("tablet_off_dark_sv", FAST_PUSH_OFF_FILLED, darkTheme = true)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletPortrait() = shell("tablet_portrait_active_light", FAST_PUSH_ACTIVE)

    private fun page(name: String, state: FastPushUiState, darkTheme: Boolean = false, fontScale: Float? = null) {
        content(name, darkTheme, fontScale) { FixedFastPushPage(state).FastPush({}, {}) }
    }

    private fun content(name: String, darkTheme: Boolean, fontScale: Float?, content: @Composable () -> Unit) {
        composeRule.setContent {
            TandemTheme(darkTheme = darkTheme) {
                val density = LocalDensity.current
                val scaled = if (fontScale == null) density else Density(density.density, fontScale)
                CompositionLocalProvider(LocalDensity provides scaled, content = content)
            }
        }
        composeRule.onRoot().captureRoboImage(path(name))
    }

    /** The whole shell: More → Synk och lagring → Snabb push (list-detail on the tablet). */
    private fun shell(name: String, state: FastPushUiState, darkTheme: Boolean = false) {
        val navigation = navigationAt(ShellTab.MORE, SyncSettingsKey, FastPushSettingsKey)
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
                    fastPushPage = FixedFastPushPage(state),
                )
            }
        }
        composeRule.onRoot().captureRoboImage(path(name))
    }

    private fun path(name: String) = "$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/shell/fast_push_$name.png"
}
