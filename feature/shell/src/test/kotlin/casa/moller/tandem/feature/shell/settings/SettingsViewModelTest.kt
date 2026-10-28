package casa.moller.tandem.feature.shell.settings

import app.cash.turbine.test
import casa.moller.tandem.core.common.AppBuildInfo
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.model.diagnostics.LogExport
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.settings.SyncInterval
import casa.moller.tandem.core.model.settings.ThemeMode
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.model.timer.TimerCapabilities
import casa.moller.tandem.core.model.timer.TimerMode
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeDiagnosticsRepository
import casa.moller.tandem.core.testing.data.FakePreferencesRepository
import casa.moller.tandem.core.testing.data.FakeSettingsRepository
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.core.testing.data.FakeTimerController
import casa.moller.tandem.feature.shell.FakeAppearanceRepository
import casa.moller.tandem.feature.shell.FakeSessionRepository
import casa.moller.tandem.feature.shell.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The settings sub-screens' ViewModel (Phase 1b, plan 6.2 "Inställningar"): every change is
 * written at once, a failed write says so, "Download all recipes" starts a sync, the photo cache,
 * the log export and the permission refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settings = FakeSettingsRepository()
    private val appearance = FakeAppearanceRepository()
    private val timers = FakeTimerController()
    private val sync = FakeSyncRepository()
    private val storage = FakeOfflineStorageRepository()
    private val diagnostics = FakeDiagnosticsRepository()
    private val sessions = FakeSessionRepository()
    private val preferences = FakePreferencesRepository()

    private fun newViewModel(distribution: String = "sideload", manufacturer: String = "Google") = SettingsViewModel(
        settingsRepository = settings,
        appearanceRepository = appearance,
        timers = timers,
        syncRepository = sync,
        offlineStorage = storage,
        diagnostics = diagnostics,
        sessionRepository = sessions,
        buildInfo = AppBuildInfo(versionName = "0.2.0-dev", versionCode = 20, distribution = distribution, isDebug = true, sdkInt = 36),
        manufacturer = manufacturer,
        preferences = preferences,
    )

    /** The screens' states are built while collected; keeps one collector for the test. */
    private fun <T> TestScope.collecting(flow: StateFlow<T>): StateFlow<T> {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { } }
        return flow
    }

    @Test
    fun appearanceShowsTheThemeAndTheStoredSettings() = runTest {
        appearance.mode.value = ThemeMode.DARK
        settings.state.value = AppSettings.Default.copy(dynamicColor = true, cookTextScale = 1.5f, cookModeAlwaysDark = true)
        val state = collecting(newViewModel().appearance)

        assertEquals(AppearanceUiState(ThemeMode.DARK, dynamicColor = true, cookTextScale = 1.5f, cookModeAlwaysDark = true), state.value)
    }

    @Test
    fun everyChangeIsWrittenAtOnce() = runTest {
        val vm = newViewModel()
        val appearanceState = collecting(vm.appearance)

        vm.onAction(SettingsAction.SetThemeMode(ThemeMode.LIGHT))
        vm.onAction(SettingsAction.SetDynamicColor(true))
        vm.onAction(SettingsAction.SetCookTextScale(1.75f))
        vm.onAction(SettingsAction.SetCookModeAlwaysDark(true))
        vm.onAction(SettingsAction.SetTimerMode(TimerMode.CLOCK_APP))
        vm.onAction(SettingsAction.SetSyncInterval(SyncInterval.MANUAL))

        assertEquals(ThemeMode.LIGHT, appearance.mode.value)
        assertEquals(
            AppSettings.Default.copy(
                dynamicColor = true,
                cookTextScale = 1.75f,
                cookModeAlwaysDark = true,
                timerMode = TimerMode.CLOCK_APP,
                syncInterval = SyncInterval.MANUAL,
            ),
            settings.state.value,
        )
        assertEquals(ThemeMode.LIGHT, appearanceState.value.themeMode)
        assertEquals(1.75f, appearanceState.value.cookTextScale)
    }

    @Test
    fun aSettingThatCannotBeSavedSaysSo() = runTest {
        settings.failWrites = true
        val vm = newViewModel()
        vm.effect.test {
            vm.onAction(SettingsAction.SetSyncInterval(SyncInterval.ONE_HOUR))
            assertEquals(SettingsEffect.Message(R.string.shell_settings_not_saved), awaitItem())
        }
        assertEquals(SyncInterval.DEFAULT, settings.state.value.syncInterval)
    }

    @Test
    fun aThemeThatCannotBeSavedSaysSo() = runTest {
        appearance.result = Outcome.Failure(TandemError.Unknown("tandem.data.settings-not-saved"))
        val vm = newViewModel()
        vm.effect.test {
            vm.onAction(SettingsAction.SetThemeMode(ThemeMode.DARK))
            assertEquals(SettingsEffect.Message(R.string.shell_settings_not_saved), awaitItem())
        }
        assertEquals(ThemeMode.SYSTEM, appearance.mode.value)
    }

    @Test
    fun downloadingAllRecipesStartsASyncAtOnce() = runTest {
        val vm = newViewModel()
        val state = collecting(vm.sync)

        vm.onAction(SettingsAction.SetDownloadAll(true))

        assertTrue(settings.state.value.downloadAllRecipes)
        assertTrue(state.value.downloadAll)
        assertEquals(listOf(SyncReason.USER_REFRESH), sync.requests)

        vm.onAction(SettingsAction.SetDownloadAll(false))
        assertFalse(settings.state.value.downloadAllRecipes)
        assertEquals("switching it off needs no sync", 1, sync.requests.size)
    }

    @Test
    fun syncShowsStorageAndStatusAndSyncNowAsks() = runTest {
        val vm = newViewModel()
        val state = collecting(vm.sync)
        sync.setStatus(SAMPLE_SYNC_STATUS)

        assertEquals(SAMPLE_STORAGE, state.value.storage)
        assertEquals(SAMPLE_SYNC_STATUS, state.value.status)
        assertFalse(state.value.isDemo)

        vm.onAction(SettingsAction.SyncNow)
        assertEquals(listOf(SyncReason.USER_REFRESH), sync.requests)

        sessions.state.value = Session.Demo
        assertTrue(state.value.isDemo)
    }

    /** 0.3.0 review (fix-data, plan 6.2 "Har hemma-effekten"): the account's shopping preferences, read only. */
    @Test
    fun syncShowsTheShoppingPreferencesOfTheAccount() = runTest {
        val state = collecting(newViewModel().sync)
        assertEquals(
            ShoppingServerSettings(addOnHand = false, excludeOnHand = true, autoAdd = false, pollSeconds = 20),
            state.value.shopping,
        )

        preferences.preferences.value = UserPreferences.Default.copy(
            shoppingAddOnHand = true,
            mealPlanAutoAddShopping = true,
            shoppingAutoSyncSeconds = 45,
        )
        assertEquals(
            ShoppingServerSettings(addOnHand = true, excludeOnHand = true, autoAdd = true, pollSeconds = 45),
            state.value.shopping,
        )

        preferences.preferences.value = UserPreferences.Default.copy(shoppingAutoSyncSeconds = 0)
        assertEquals("automatic sync off: no polling", null, state.value.shopping?.pollSeconds)
        assertTrue(state.value.shopping != null)

        sessions.state.value = Session.Demo
        assertEquals("demo mode has no Tandoor account", null, state.value.shopping)
    }

    @Test
    fun clearingThePhotoCacheSaysHowItWent() = runTest {
        val vm = newViewModel()
        val state = collecting(vm.sync)
        vm.effect.test {
            vm.onAction(SettingsAction.ClearImageCache)
            assertEquals(SettingsEffect.Message(R.string.settings_image_cache_cleared), awaitItem())
            assertEquals(0L, state.value.storage.imageCacheBytes)

            storage.clearResult = FakeOfflineStorageRepository.FAILED
            vm.onAction(SettingsAction.ClearImageCache)
            assertEquals(SettingsEffect.Message(R.string.settings_image_cache_not_cleared), awaitItem())
        }
        assertEquals(2, storage.clears)
        assertFalse(state.value.clearingImageCache)
    }

    @Test
    fun exportingTheLogOpensTheShareSheet() = runTest {
        val export = LogExport(text = "Tandem diagnostics", fileName = "tandem-diagnostics-2000-01-15.txt")
        diagnostics.exportResult = Outcome.Success(export)
        val vm = newViewModel()
        vm.effect.test {
            vm.onAction(SettingsAction.ExportLog)
            assertEquals(SettingsEffect.ShareLog(export), awaitItem())

            diagnostics.exportResult = Outcome.Failure(TandemError.Unknown("tandem.diagnostics.export"))
            vm.onAction(SettingsAction.ExportLog)
            assertEquals(SettingsEffect.Message(R.string.settings_diagnostics_export_failed), awaitItem())
        }
        assertEquals(2, diagnostics.exportCount)
    }

    @Test
    fun diagnosticsShowTheSnapshot() = runTest {
        val vm = newViewModel()
        val state = collecting(vm.diagnosticsState)
        assertEquals(diagnostics.snapshot.value, state.value.snapshot)
    }

    @Test
    fun permissionsAreReadAgainWhenTheScreenComesBack() = runTest {
        val vm = newViewModel(manufacturer = "samsung")
        val state = collecting(vm.permissions)
        assertTrue(state.value.showBattery)
        assertTrue("sideload can ring on the lock screen", state.value.showFullScreen)

        timers.setCapabilities(CAPABILITIES_LIMITED)
        vm.onAction(SettingsAction.RefreshPermissions(batteryUnrestricted = true))

        assertEquals(1, timers.refreshCount)
        assertEquals(CAPABILITIES_LIMITED, state.value.capabilities)
        assertEquals(true, state.value.batteryUnrestricted)
        assertFalse(state.value.exactAlarmsFixed)
    }

    @Test
    fun playHasNoFullScreenRowAndOtherPhonesNoBatteryRow() = runTest {
        val state = collecting(newViewModel(distribution = "play", manufacturer = "Google").permissions)
        assertFalse(state.value.showFullScreen)
        assertFalse(state.value.showBattery)
    }

    @Test
    fun exactAlarmsThatComeWithTheBuildCannotBeChanged() {
        val sideload = PermissionsUiState(capabilities = TimerCapabilities.AllGranted.copy(exactAlarmsRequestable = false))
        assertTrue(sideload.exactAlarmsFixed)
        val play = PermissionsUiState(capabilities = TimerCapabilities.AllGranted.copy(exactAlarmsRequestable = true))
        assertFalse(play.exactAlarmsFixed)
    }

    @Test
    fun theClockAppCanOnlyBeChosenWhenThereIsOne() = runTest {
        val vm = newViewModel()
        val state = collecting(vm.cooking)
        assertTrue(state.value.clockAppSelectable)

        timers.setCapabilities(TimerCapabilities.AllGranted.copy(clockAppAvailable = false))
        assertFalse(state.value.clockAppSelectable)

        // Chosen before the clock app went away: it can still be changed back.
        settings.state.value = settings.state.value.copy(timerMode = TimerMode.CLOCK_APP)
        assertTrue(state.value.clockAppSelectable)
    }

    @Test
    fun theBatteryRowIsForPhonesThatStopApps() {
        listOf("samsung", "Samsung", "Xiaomi", "OnePlus", " HUAWEI ").forEach { assertTrue(it, BatteryAdvice.appliesTo(it)) }
        listOf("Google", "Fairphone", "").forEach { assertFalse(it, BatteryAdvice.appliesTo(it)) }
    }
}
