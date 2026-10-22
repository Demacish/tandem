package casa.moller.tandem.feature.shell.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.timer.TimerCapabilities
import casa.moller.tandem.core.model.timer.TimerMode
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.shell.CONNECTED_STATE
import casa.moller.tandem.feature.shell.PlaceholderShellFeatures
import casa.moller.tandem.feature.shell.RecordingActions
import casa.moller.tandem.feature.shell.ShellScaffold
import casa.moller.tandem.feature.shell.TABLET
import casa.moller.tandem.feature.shell.navigation.AppearanceSettingsKey
import casa.moller.tandem.feature.shell.navigation.CookingSettingsKey
import casa.moller.tandem.feature.shell.navigation.DiagnosticsKey
import casa.moller.tandem.feature.shell.navigation.PermissionsKey
import casa.moller.tandem.feature.shell.navigation.ShellTab
import casa.moller.tandem.feature.shell.navigation.SyncSettingsKey
import casa.moller.tandem.feature.shell.navigationAt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ATF accessibility checks (core:testing `assertAccessible`, plan 5.6) of the settings
 * sub-screens (Phase 1b): every screen and its states on a tall phone, dark, font scale 2, and the
 * tablet's list-detail layout with the selected row.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_TALL)
class SettingsAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun appearance() = check { FixedSettingsPages().Appearance {} }

    @Test
    fun appearanceDarkAtTheLimits() = check(darkTheme = true) {
        val state = SAMPLE_APPEARANCE.copy(dynamicColor = true, cookTextScale = 2f, cookModeAlwaysDark = true)
        FixedSettingsPages(appearance = state).Appearance {}
    }

    @Test
    fun appearanceFontScale2() = check(fontScale = 2f) {
        FixedSettingsPages(appearance = SAMPLE_APPEARANCE.copy(cookTextScale = 0.85f)).Appearance {}
    }

    @Test
    fun cooking() = check { FixedSettingsPages().Cooking({}, {}, {}) }

    @Test
    fun cookingWarning() = check { FixedSettingsPages(cooking = CookingUiState(capabilities = CAPABILITIES_LIMITED)).Cooking({}, {}, {}) }

    @Test
    fun cookingWarningDark() = check(darkTheme = true) {
        FixedSettingsPages(cooking = CookingUiState(capabilities = CAPABILITIES_LIMITED)).Cooking({}, {}, {})
    }

    @Test
    fun cookingNoClockAppFontScale2() = check(fontScale = 2f) {
        val capabilities = TimerCapabilities.AllGranted.copy(clockAppAvailable = false)
        FixedSettingsPages(cooking = CookingUiState(timerMode = TimerMode.IN_APP, capabilities = capabilities)).Cooking({}, {}, {})
    }

    @Test
    fun sync() = check { FixedSettingsPages().Sync {} }

    @Test
    fun syncDark() = check(darkTheme = true) { FixedSettingsPages().Sync {} }

    @Test
    fun syncDownloadingFontScale2() = check(fontScale = 2f) { FixedSettingsPages(sync = SAMPLE_SYNC_DOWNLOADING).Sync {} }

    @Test
    fun syncPaused() = check { FixedSettingsPages(sync = SAMPLE_SYNC_PAUSED).Sync {} }

    @Test
    fun syncDemo() = check { FixedSettingsPages(sync = SyncSettingsUiState(isDemo = true)).Sync {} }

    @Test
    fun permissions() = check { FixedSettingsPages().Permissions {} }

    @Test
    fun permissionsDark() = check(darkTheme = true) { FixedSettingsPages().Permissions {} }

    @Test
    fun permissionsPlayLimitedFontScale2() = check(fontScale = 2f) {
        FixedSettingsPages(permissions = PermissionsUiState(capabilities = CAPABILITIES_LIMITED, showBattery = true)).Permissions {}
    }

    @Test
    fun diagnostics() = check { FixedSettingsPages().Diagnostics {} }

    @Test
    fun diagnosticsDark() = check(darkTheme = true) { FixedSettingsPages().Diagnostics {} }

    @Test
    fun diagnosticsNotMeasuredFontScale2() = check(fontScale = 2f) {
        FixedSettingsPages(diagnostics = SAMPLE_DIAGNOSTICS_EMPTY).Diagnostics {}
    }

    @Test
    fun diagnosticsExporting() = check { FixedSettingsPages(diagnostics = SAMPLE_DIAGNOSTICS.copy(exporting = true)).Diagnostics {} }

    @Test
    @Config(qualifiers = TABLET)
    fun tabletMoreWithoutAPage() = checkShell()

    @Test
    @Config(qualifiers = TABLET)
    fun tabletSync() = checkShell(SyncSettingsKey)

    @Test
    @Config(qualifiers = TABLET)
    fun tabletAppearanceDark() = checkShell(AppearanceSettingsKey, darkTheme = true)

    @Test
    @Config(qualifiers = TABLET)
    fun tabletCooking() = checkShell(CookingSettingsKey)

    @Test
    @Config(qualifiers = TABLET)
    fun tabletPermissionsDark() = checkShell(PermissionsKey, darkTheme = true)

    @Test
    @Config(qualifiers = TABLET)
    fun tabletDiagnostics() = checkShell(DiagnosticsKey)

    private fun check(darkTheme: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale * density.fontScale)) {
                TandemTheme(darkTheme = darkTheme, content = content)
            }
        }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }

    private fun checkShell(vararg pages: NavKey, darkTheme: Boolean = false) {
        val navigation = navigationAt(ShellTab.MORE, *pages)
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
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }
}
