package casa.moller.tandem.feature.shell.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.timer.TimerCapabilities
import casa.moller.tandem.core.model.timer.TimerMode
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.shell.CONNECTED_STATE
import casa.moller.tandem.feature.shell.PHONE
import casa.moller.tandem.feature.shell.PHONE_SV
import casa.moller.tandem.feature.shell.PlaceholderShellFeatures
import casa.moller.tandem.feature.shell.RecordingActions
import casa.moller.tandem.feature.shell.ShellScaffold
import casa.moller.tandem.feature.shell.ShellUiState
import casa.moller.tandem.feature.shell.TABLET
import casa.moller.tandem.feature.shell.TABLET_SV
import casa.moller.tandem.feature.shell.navigation.AppearanceSettingsKey
import casa.moller.tandem.feature.shell.navigation.CookingSettingsKey
import casa.moller.tandem.feature.shell.navigation.DiagnosticsKey
import casa.moller.tandem.feature.shell.navigation.PermissionsKey
import casa.moller.tandem.feature.shell.navigation.ShellTab
import casa.moller.tandem.feature.shell.navigation.SyncSettingsKey
import casa.moller.tandem.feature.shell.navigationAt
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A tall phone, so a whole settings page fits in one screenshot. */
internal const val PHONE_TALL = "w412dp-h1800dp-port-xhdpi"

/**
 * Screenshots of the settings sub-screens under "More" (Phase 1b, plan 6.2 "Inställningar"): the
 * stateless screens with fixed states on a phone and a tall phone (light, dark, Swedish, font
 * scale 2), and on a tablet in the shell's list-detail layout (design
 * mer_inst_llningar_surfplatta). Only recorded; CI uploads build/outputs/roborazzi.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class SettingsScreenshotTest(private val device: String) {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun appearance() = page("appearance_light") { FixedSettingsPages().Appearance {} }

    @Test
    fun appearanceDark() = page("appearance_dark", darkTheme = true) {
        FixedSettingsPages(appearance = SAMPLE_APPEARANCE.copy(dynamicColor = true, cookModeAlwaysDark = true)).Appearance {}
    }

    @Test
    fun cooking() = page("cooking_light") { FixedSettingsPages().Cooking({}, {}, {}) }

    /** Play without exact alarms and with the alarm sound off: the warning with the way to the permissions. */
    @Test
    fun cookingWarning() = page("cooking_warning_light") {
        FixedSettingsPages(cooking = CookingUiState(capabilities = CAPABILITIES_LIMITED)).Cooking({}, {}, {})
    }

    @Test
    fun cookingClockAppDark() = page("cooking_clock_app_dark", darkTheme = true) {
        FixedSettingsPages(cooking = CookingUiState(timerMode = TimerMode.CLOCK_APP)).Cooking({}, {}, {})
    }

    @Test
    fun cookingNoClockApp() = page("cooking_no_clock_app_light") {
        val capabilities = TimerCapabilities.AllGranted.copy(clockAppAvailable = false)
        FixedSettingsPages(cooking = CookingUiState(capabilities = capabilities)).Cooking({}, {}, {})
    }

    @Test
    fun sync() = page("sync_light") { FixedSettingsPages().Sync {} }

    @Test
    fun syncDark() = page("sync_dark", darkTheme = true) { FixedSettingsPages().Sync {} }

    @Test
    fun syncDownloading() = page("sync_downloading_light") { FixedSettingsPages(sync = SAMPLE_SYNC_DOWNLOADING).Sync {} }

    @Test
    fun syncPausedAllDownloaded() = page("sync_paused_all_downloaded_light") { FixedSettingsPages(sync = SAMPLE_SYNC_PAUSED).Sync {} }

    @Test
    fun syncDemo() = page("sync_demo_light") {
        val demo = SyncSettingsUiState(isDemo = true, storage = SAMPLE_STORAGE.copy(recipeCount = 14, downloadedCount = 14))
        FixedSettingsPages(sync = demo).Sync {}
    }

    @Test
    fun permissions() = page("permissions_light") { FixedSettingsPages().Permissions {} }

    @Test
    fun permissionsDark() = page("permissions_dark", darkTheme = true) { FixedSettingsPages().Permissions {} }

    /** Play: exact alarms can be allowed in Android's settings; no full-screen row, no battery row. */
    @Test
    fun permissionsPlayLimited() = page("permissions_play_limited_light") {
        FixedSettingsPages(permissions = PermissionsUiState(capabilities = CAPABILITIES_LIMITED)).Permissions {}
    }

    @Test
    fun diagnostics() = page("diagnostics_light") { FixedSettingsPages().Diagnostics {} }

    @Test
    fun diagnosticsDark() = page("diagnostics_dark", darkTheme = true) { FixedSettingsPages().Diagnostics {} }

    /** Phase 2 (koppling lane): "blockerad"-diagnostik with the circuit breaker open and changes per kind. */
    @Test
    fun diagnosticsBlocked() = page("diagnostics_blocked_light") {
        FixedSettingsPages(diagnostics = SAMPLE_DIAGNOSTICS_BLOCKED).Diagnostics {}
    }

    @Test
    fun diagnosticsBlockedDark() = page("diagnostics_blocked_dark", darkTheme = true) {
        FixedSettingsPages(diagnostics = SAMPLE_DIAGNOSTICS_BLOCKED).Diagnostics {}
    }

    @Test
    fun diagnosticsNotMeasured() = page("diagnostics_not_measured_light") {
        FixedSettingsPages(diagnostics = SAMPLE_DIAGNOSTICS_EMPTY.copy(exporting = true)).Diagnostics {}
    }

    protected fun page(name: String, darkTheme: Boolean = false, fontScale: Float? = null, content: @Composable () -> Unit) {
        composeRule.setContent {
            TandemTheme(darkTheme = darkTheme) {
                val density = LocalDensity.current
                val scaled = if (fontScale == null) density else Density(density.density, fontScale)
                CompositionLocalProvider(LocalDensity provides scaled, content = content)
            }
        }
        composeRule.onRoot().captureRoboImage(path(name))
    }

    /** The whole shell with [pages] open on the More tab (list-detail on wide windows). */
    protected fun shell(name: String, vararg pages: NavKey, state: ShellUiState = CONNECTED_STATE, darkTheme: Boolean = false) {
        val navigation = navigationAt(ShellTab.MORE, *pages)
        composeRule.setContent {
            TandemTheme(darkTheme = darkTheme) {
                ShellScaffold(
                    state = state,
                    navigation = navigation,
                    actions = RecordingActions(),
                    language = AppLanguage.SYSTEM,
                    onLanguageSelected = {},
                    onConnectTandoor = {},
                    features = PlaceholderShellFeatures(state),
                    settingsPages = FixedSettingsPages(),
                )
            }
        }
        composeRule.onRoot().captureRoboImage(path(name))
    }

    private fun path(name: String) = "$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/shell/settings_${device}_$name.png"
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
class SettingsPhoneScreenshotTest : SettingsScreenshotTest("phone") {
    @Test
    @Config(qualifiers = PHONE_SV)
    fun appearanceSwedish() = page("appearance_light_sv") { FixedSettingsPages().Appearance {} }

    @Test
    @Config(qualifiers = PHONE_SV)
    fun cookingWarningSwedish() = page("cooking_warning_light_sv") {
        FixedSettingsPages(cooking = CookingUiState(capabilities = CAPABILITIES_LIMITED)).Cooking({}, {}, {})
    }

    @Test
    @Config(qualifiers = PHONE_SV)
    fun syncSwedish() = page("sync_light_sv") { FixedSettingsPages().Sync {} }

    @Test
    @Config(qualifiers = PHONE_SV)
    fun permissionsSwedish() = page("permissions_light_sv") { FixedSettingsPages().Permissions {} }

    @Test
    @Config(qualifiers = PHONE_SV)
    fun diagnosticsSwedish() = page("diagnostics_light_sv") { FixedSettingsPages().Diagnostics {} }

    @Test
    @Config(qualifiers = PHONE_SV)
    fun diagnosticsBlockedSwedish() = page("diagnostics_blocked_light_sv") {
        FixedSettingsPages(diagnostics = SAMPLE_DIAGNOSTICS_BLOCKED).Diagnostics {}
    }

    @Test
    fun diagnosticsBlockedFontScale2() = page("diagnostics_blocked_font_scale_2_light", fontScale = 2f) {
        FixedSettingsPages(diagnostics = SAMPLE_DIAGNOSTICS_BLOCKED).Diagnostics {}
    }

    @Test
    fun appearanceFontScale2() = page("appearance_font_scale_2_light", fontScale = 2f) { FixedSettingsPages().Appearance {} }

    @Test
    fun cookingFontScale2() = page("cooking_warning_font_scale_2_light", fontScale = 2f) {
        FixedSettingsPages(cooking = CookingUiState(capabilities = CAPABILITIES_LIMITED)).Cooking({}, {}, {})
    }

    @Test
    fun syncFontScale2() = page("sync_font_scale_2_light", fontScale = 2f) { FixedSettingsPages().Sync {} }

    @Test
    fun permissionsFontScale2() = page("permissions_font_scale_2_light", fontScale = 2f) { FixedSettingsPages().Permissions {} }

    @Test
    fun diagnosticsFontScale2() = page("diagnostics_font_scale_2_light", fontScale = 2f) { FixedSettingsPages().Diagnostics {} }

    /** The banner of a held-back sync above Today (1a open issue). */
    @Test
    fun syncBlockedBanner() = shell("shell_more_sync_blocked_light", state = CONNECTED_STATE.copy(syncHeldBack = SyncPause.BLOCKED))

    @Test
    @Config(qualifiers = PHONE_SV)
    fun syncBreakerBannerSwedish() =
        shell("shell_more_sync_breaker_light_sv", state = CONNECTED_STATE.copy(syncHeldBack = SyncPause.CIRCUIT_BREAKER))
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_TALL)
class SettingsTallPhoneScreenshotTest : SettingsScreenshotTest("phone_tall")

/** The tablet's list-detail layout (design mer_inst_llningar_surfplatta). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = TABLET)
class SettingsTabletScreenshotTest : SettingsScreenshotTest("tablet") {
    @Test
    fun shellNoPage() = shell("shell_more_light")

    @Test
    fun shellSync() = shell("shell_sync_light", SyncSettingsKey)

    @Test
    fun shellAppearanceDark() = shell("shell_appearance_dark", AppearanceSettingsKey, darkTheme = true)

    @Test
    fun shellCooking() = shell("shell_cooking_light", CookingSettingsKey)

    @Test
    fun shellPermissions() = shell("shell_permissions_light", PermissionsKey)

    @Test
    fun shellDiagnostics() = shell("shell_diagnostics_light", DiagnosticsKey)

    @Test
    @Config(qualifiers = TABLET_SV)
    fun shellSyncSwedish() = shell("shell_sync_light_sv", SyncSettingsKey)

    @Test
    @Config(qualifiers = TABLET_SV)
    fun shellDiagnosticsDarkSwedish() = shell("shell_diagnostics_dark_sv", DiagnosticsKey, darkTheme = true)
}
