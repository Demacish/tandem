package casa.moller.tandem.feature.shell

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.server.ServerFeature
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.shell.more.LOGOUT_PENDING_TAG
import casa.moller.tandem.feature.shell.navigation.AboutKey
import casa.moller.tandem.feature.shell.navigation.BundledLicense
import casa.moller.tandem.feature.shell.navigation.LanguageKey
import casa.moller.tandem.feature.shell.navigation.LicenseTextKey
import casa.moller.tandem.feature.shell.navigation.LicensesKey
import casa.moller.tandem.feature.shell.navigation.SafeModeInfoKey
import casa.moller.tandem.feature.shell.navigation.ShellTab
import casa.moller.tandem.feature.shell.settings.FixedSettingsPages
import casa.moller.tandem.feature.shell.settings.PHONE_TALL
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ATF accessibility checks (core:testing `assertAccessible`, plan 5.6) of the shell's own screens,
 * also the Phase 0 ones that were never checked: More (connected, demo, safe mode with a failed
 * check, font scale 2, tablet), Language, About, Licenses and a licence text, the logout dialog,
 * the safe mode explanation, and the banners above the tabs (log in again, held-back sync,
 * Tandoor updated, safe mode).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_TALL)
class ShellAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val safeMode = CONNECTED_STATE.copy(
        mode = LOGGED_IN.copy(serverVersion = "2.7.1", compatibility = ServerCompatibility.UNTESTED_NEWER).toShellMode(),
        refreshError = TandemError.Timeout,
    )

    @Test
    fun more() = check(CONNECTED_STATE, ShellTab.MORE)

    @Test
    fun moreDark() = check(CONNECTED_STATE, ShellTab.MORE, darkTheme = true)

    @Test
    fun moreFontScale2() = check(CONNECTED_STATE, ShellTab.MORE, fontScale = 2f)

    @Test
    fun moreDemo() = check(DEMO_STATE, ShellTab.MORE)

    @Test
    fun moreSafeModeWithAFailedCheck() = check(safeMode, ShellTab.MORE)

    @Test
    fun moreAllSummariesDark() = check(
        CONNECTED_STATE.copy(settings = SettingsSummary(dynamicColor = true, downloadAllRecipes = true, timersNeedAttention = true)),
        ShellTab.MORE,
        darkTheme = true,
    )

    @Test
    @Config(qualifiers = TABLET)
    fun moreTabletDark() = check(CONNECTED_STATE, ShellTab.MORE, AboutKey, darkTheme = true)

    @Test
    fun language() = check(CONNECTED_STATE, ShellTab.MORE, LanguageKey)

    @Test
    fun languageDark() = check(CONNECTED_STATE, ShellTab.MORE, LanguageKey, darkTheme = true)

    @Test
    fun about() = check(CONNECTED_STATE, ShellTab.MORE, AboutKey)

    @Test
    fun aboutFontScale2() = check(CONNECTED_STATE, ShellTab.MORE, AboutKey, fontScale = 2f)

    @Test
    fun licenses() = check(CONNECTED_STATE, ShellTab.MORE, LicensesKey)

    @Test
    fun licenseTextDark() = check(CONNECTED_STATE, ShellTab.MORE, LicensesKey, LicenseTextKey(BundledLicense.DM_SANS), darkTheme = true)

    @Test
    fun safeModeInfo() = check(safeMode, ShellTab.TODAY, SafeModeInfoKey)

    @Test
    fun logoutDialog() {
        set(CONNECTED_STATE, ShellTab.MORE)
        val logOut = ApplicationProvider.getApplicationContext<Context>().getString(R.string.shell_log_out_of, "tandoor.example.com")
        composeRule.onNodeWithText(logOut).performScrollTo().performClick()
        composeRule.onNode(isDialog()).assertExists()
        composeRule.assertAccessible()
    }

    @Test
    fun logoutDialogPastedTokenDark() {
        set(PASTED_STATE, ShellTab.MORE, darkTheme = true)
        val logOut = ApplicationProvider.getApplicationContext<Context>().getString(R.string.shell_log_out_of, "tandoor.example.com")
        composeRule.onNodeWithText(logOut).performScrollTo().performClick()
        composeRule.onNode(isDialog()).assertExists()
        composeRule.assertAccessible()
    }

    @Test
    fun loginExpiredBanner() = check(CONNECTED_STATE.copy(loginExpired = true), ShellTab.TODAY)

    @Test
    fun syncBlockedBanner() = check(CONNECTED_STATE.copy(syncHeldBack = SyncPause.BLOCKED), ShellTab.TODAY)

    @Test
    fun syncBreakerBannerDark() = check(CONNECTED_STATE.copy(syncHeldBack = SyncPause.CIRCUIT_BREAKER), ShellTab.RECIPES, darkTheme = true)

    @Test
    fun syncBreakerBannerFontScale2() =
        check(CONNECTED_STATE.copy(syncHeldBack = SyncPause.CIRCUIT_BREAKER), ShellTab.TODAY, fontScale = 2f)

    @Test
    fun serverUpdatedBanner() = check(CONNECTED_STATE.copy(serverUpdatedTo = "2.6.15"), ShellTab.TODAY)

    @Test
    fun safeModeBannerDark() = check(safeMode, ShellTab.TODAY, darkTheme = true)

    @Test
    fun demoPlaceholderTabs() = check(DEMO_STATE, ShellTab.WEEK)

    // --- Phase 1b: the timer bar on every tab ---

    @Test
    fun timerBarRunningAndPaused() = check(CONNECTED_STATE.copy(timers = listOf(PASTA_TIMER, SAUCE_TIMER)), ShellTab.TODAY)

    @Test
    fun timerBarRingingDark() = check(CONNECTED_STATE.copy(timers = listOf(PASTA_TIMER, OVEN_TIMER)), ShellTab.MORE, darkTheme = true)

    @Test
    fun timerBarPausedFontScale2() = check(CONNECTED_STATE.copy(timers = listOf(SAUCE_TIMER)), ShellTab.RECIPES, fontScale = 2f)

    // --- fix-ui (0.2.0 review) ---

    @Test
    fun timerBarLongLabelFontScale2() = check(
        CONNECTED_STATE.copy(timers = listOf(LONG_RINGING_TIMER, LONG_TIMER)),
        ShellTab.TODAY,
        fontScale = 2f,
    )

    @Test
    fun logoutDialogWithUnsentChanges() {
        set(CONNECTED_STATE.copy(pendingChanges = 2), ShellTab.MORE)
        val logOut = ApplicationProvider.getApplicationContext<Context>().getString(R.string.shell_log_out_of, "tandoor.example.com")
        composeRule.onNodeWithText(logOut).performScrollTo().performClick()
        composeRule.onNodeWithTag(LOGOUT_PENDING_TAG).assertExists()
        composeRule.assertAccessible()
    }

    @Test
    fun moreWithServerFeaturesDark() = check(
        CONNECTED_STATE.copy(serverFeatures = setOf(ServerFeature.COOK_LOG, ServerFeature.AI_IMPORT)),
        ShellTab.MORE,
        darkTheme = true,
    )

    @Test
    fun phoneSettingsRowsAreNotAnnouncedAsNotSelected() {
        set(CONNECTED_STATE, ShellTab.MORE)
        // Navigation bar items are tabs (they may say "selected"); a settings row is a button.
        val rowWithSelectedState = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        composeRule.onAllNodes(rowWithSelectedState).assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = TABLET)
    fun timerBarTabletRinging() = check(CONNECTED_STATE.copy(timers = listOf(OVEN_TIMER, PASTA_TIMER, SAUCE_TIMER)), ShellTab.TODAY)

    private fun check(state: ShellUiState, tab: ShellTab, vararg pages: NavKey, darkTheme: Boolean = false, fontScale: Float = 1f) {
        set(state, tab, *pages, darkTheme = darkTheme, fontScale = fontScale)
        composeRule.assertAccessible()
    }

    private fun set(state: ShellUiState, tab: ShellTab, vararg pages: NavKey, darkTheme: Boolean = false, fontScale: Float = 1f) {
        val navigation = navigationAt(tab, *pages)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale * density.fontScale)) {
                TandemTheme(darkTheme = darkTheme) {
                    FrozenTimers {
                        ShellScaffold(
                            state = state,
                            navigation = navigation,
                            actions = RecordingActions(),
                            language = AppLanguage.SWEDISH,
                            onLanguageSelected = {},
                            onConnectTandoor = {},
                            features = PlaceholderShellFeatures(state),
                            settingsPages = FixedSettingsPages(),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }
}
