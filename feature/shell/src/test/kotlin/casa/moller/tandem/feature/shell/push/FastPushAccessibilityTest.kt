package casa.moller.tandem.feature.shell.push

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.shell.CONNECTED_STATE
import casa.moller.tandem.feature.shell.PlaceholderShellFeatures
import casa.moller.tandem.feature.shell.RecordingActions
import casa.moller.tandem.feature.shell.ShellScaffold
import casa.moller.tandem.feature.shell.TABLET
import casa.moller.tandem.feature.shell.navigation.FastPushSettingsKey
import casa.moller.tandem.feature.shell.navigation.ShellTab
import casa.moller.tandem.feature.shell.navigation.SyncSettingsKey
import casa.moller.tandem.feature.shell.navigationAt
import casa.moller.tandem.feature.shell.settings.FixedSettingsPages
import casa.moller.tandem.feature.shell.settings.PHONE_TALL
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** ATF checks (core:testing `assertAccessible`) of Snabb push in every state (Fas 4, push lane). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_TALL)
class FastPushAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun off() = check(FAST_PUSH_OFF)

    @Test
    fun noDistributor() = check(FAST_PUSH_NO_DISTRIBUTOR)

    @Test
    fun invalidUrl() = check(FAST_PUSH_INVALID_URL)

    @Test
    fun registering() = check(FAST_PUSH_REGISTERING)

    @Test
    fun active() = check(FAST_PUSH_ACTIVE)

    @Test
    fun activeDark() = check(FAST_PUSH_ACTIVE, darkTheme = true)

    @Test
    fun activeBlockedDark() = check(FAST_PUSH_ACTIVE_BLOCKED, darkTheme = true)

    @Test
    fun failed() = check(FAST_PUSH_FAILED)

    @Test
    fun failedDark() = check(FAST_PUSH_FAILED, darkTheme = true)

    @Test
    fun demo() = check(FAST_PUSH_DEMO)

    @Test
    fun guestDark() = check(FAST_PUSH_GUEST, darkTheme = true)

    @Test
    fun activeFontScale2() = check(FAST_PUSH_ACTIVE, fontScale = 2f)

    /**
     * Taller than [PHONE_TALL]: at font scale 2 the page's last line is cut by the window's edge,
     * and ATF measures the contrast of a half-drawn line (a blend with the background) otherwise.
     */
    @Test
    @Config(qualifiers = "w412dp-h2600dp-port-xhdpi")
    fun noDistributorFontScale2() = check(FAST_PUSH_NO_DISTRIBUTOR, fontScale = 2f)

    @Test
    @Config(qualifiers = TABLET)
    fun tabletBesideTheList() {
        val navigation = navigationAt(ShellTab.MORE, SyncSettingsKey, FastPushSettingsKey)
        composeRule.setContent {
            TandemTheme {
                ShellScaffold(
                    state = CONNECTED_STATE,
                    navigation = navigation,
                    actions = RecordingActions(),
                    language = AppLanguage.SYSTEM,
                    onLanguageSelected = {},
                    onConnectTandoor = {},
                    features = PlaceholderShellFeatures(CONNECTED_STATE),
                    settingsPages = FixedSettingsPages(),
                    fastPushPage = FixedFastPushPage(FAST_PUSH_ACTIVE),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }

    private fun check(state: FastPushUiState, darkTheme: Boolean = false, fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale * density.fontScale)) {
                TandemTheme(darkTheme = darkTheme) { FixedFastPushPage(state).FastPush({}, {}) }
            }
        }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }
}
