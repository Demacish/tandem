package casa.moller.tandem.feature.shell.assistant

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
import casa.moller.tandem.feature.shell.navigation.AssistantSettingsKey
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
 * Fas 5 (funktioner lane, plan §8.7): Mer → Assistenten on a tall phone (off, on, allowed while
 * locked, Android 15 and older, a guest, demo mode; light, dark, Swedish, font scale 2), the phone
 * in landscape, Mer with the row, and the tablet's list-detail layout in both orientations. ATF on
 * each shot.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_TALL)
class AssistantScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun off() = page("phone_off_light", ASSISTANT_OFF)

    @Test
    fun on() = page("phone_on_light", ASSISTANT_ON_LOCKED_ALLOWED)

    @Test
    fun onDark() = page("phone_on_dark", ASSISTANT_ON, darkTheme = true)

    @Test
    fun unsupported() = page("phone_unsupported_light", ASSISTANT_UNSUPPORTED)

    @Test
    fun guest() = page("phone_guest_light", ASSISTANT_GUEST)

    @Test
    fun demoDark() = page("phone_demo_dark", ASSISTANT_DEMO, darkTheme = true)

    @Test
    @Config(qualifiers = "w412dp-h2600dp-port-xhdpi")
    fun onFontScale2() = page("phone_on_font_scale_2_light", ASSISTANT_GUEST, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun onSwedish() = page("phone_on_light_sv", ASSISTANT_ON)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun unsupportedSwedish() = page("phone_unsupported_light_sv", ASSISTANT_UNSUPPORTED)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun onLandscape() = page("phone_landscape_on_light", ASSISTANT_ON)

    @Test
    fun moreWithTheRow() = shell("phone_more_row_light", ASSISTANT_ON, onPage = false)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun moreWithTheRowSwedish() = shell("phone_more_row_light_sv", ASSISTANT_OFF, onPage = false)

    @Test
    @Config(qualifiers = TABLET)
    fun tablet() = shell("tablet_on_light", ASSISTANT_ON)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun tabletDarkSwedish() = shell("tablet_off_dark_sv", ASSISTANT_OFF, darkTheme = true)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletPortrait() = shell("tablet_portrait_on_light", ASSISTANT_ON)

    private fun page(name: String, state: AssistantSettingsUiState, darkTheme: Boolean = false, fontScale: Float? = null) {
        content(name, darkTheme, fontScale) { AssistantSettingsScreen(state = state, onAction = {}, onNavigateBack = {}) }
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

    /** The whole shell: Mer with the row, and Mer → Assistenten (list-detail on the tablet). */
    private fun shell(name: String, state: AssistantSettingsUiState, darkTheme: Boolean = false, onPage: Boolean = true) {
        val navigation = if (onPage) navigationAt(ShellTab.MORE, AssistantSettingsKey) else navigationAt(ShellTab.MORE)
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
                    assistantPage = FixedAssistantPage(state),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
        composeRule.onRoot().captureRoboImage(path(name))
    }

    private fun path(name: String) = "$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/shell/assistant_$name.png"
}
