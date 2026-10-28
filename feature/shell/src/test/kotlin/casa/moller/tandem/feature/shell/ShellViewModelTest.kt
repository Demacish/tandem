package casa.moller.tandem.feature.shell

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cash.turbine.test
import casa.moller.tandem.core.common.AppBuildInfo
import casa.moller.tandem.core.data.demo.DemoDataRepository
import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.data.sync.SyncRepository
import casa.moller.tandem.core.model.account.AccountSummary
import casa.moller.tandem.core.model.account.Household
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.account.TokenOrigin
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.server.Capabilities
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.server.ServerFeature
import casa.moller.tandem.core.model.server.ServerStatus
import casa.moller.tandem.core.model.server.ServerVersionChange
import casa.moller.tandem.core.model.settings.RecoveryReason
import casa.moller.tandem.core.model.settings.SyncInterval
import casa.moller.tandem.core.model.settings.ThemeMode
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.SyncProgress
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.model.sync.SyncStatus
import casa.moller.tandem.core.model.timer.TimerCapabilities
import casa.moller.tandem.core.model.timer.TimerMode
import casa.moller.tandem.core.model.timer.TimerSource
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.testing.data.FakeAccountDirectory
import casa.moller.tandem.core.testing.data.FakeServerStatusRepository
import casa.moller.tandem.core.testing.data.FakeSettingsRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.FakeTimerController
import casa.moller.tandem.core.ui.AppStatus
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShellViewModelTest {

    private val sessions = FakeSessionRepository()
    private val appearance = FakeAppearanceRepository()
    private val messages = ShellMessageQueue()
    private val buildInfo = AppBuildInfo(
        versionName = "0.0.1-dev",
        versionCode = 1,
        distribution = "sideload",
        isDebug = true,
        sdkInt = 36,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Stands in for the process-wide scope; runs eagerly like the test Main dispatcher. */
    private val applicationScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    private var nowMillis = 1_000_000L

    /** The sync's status; only its pause matters to the shell (the Offline chip). */
    private val syncStatus = MutableStateFlow(SyncStatus.Idle)
    private val syncRequests = mutableListOf<SyncReason>()
    private val sync = object : SyncRepository {
        override val status: StateFlow<SyncStatus> = syncStatus

        override fun requestSync(reason: SyncReason) {
            syncRequests += reason
        }

        override fun firstSync(): Flow<SyncProgress> = emptyFlow()
    }

    /** "Reset demo data": counts the calls and answers with [demoResetResult]. */
    private var demoResets = 0
    private var demoResetResult: Outcome<Unit> = Outcome.Success(Unit)
    private val demoData = object : DemoDataRepository {
        override suspend fun resetDemoData(): Outcome<Unit> {
            demoResets++
            return demoResetResult
        }
    }

    /** The server status; its version notice stands for the persisted store (it outlives ViewModels). */
    private val serverStatus = FakeServerStatusRepository()

    // Phase 1b (inställningar lane): the settings rows' summaries and the timer bar.
    private val settings = FakeSettingsRepository()
    private val timers = FakeTimerController()

    // Phase 2 (konto lane): household, role and the stored accounts on the account card.
    private val accountDirectory = FakeAccountDirectory()
    private val permissions = FakeSpacePermissionsRepository()

    private fun newViewModel() = ShellViewModel(
        sessionRepository = sessions,
        appearanceRepository = appearance,
        messages = messages,
        buildInfo = buildInfo,
        applicationScope = applicationScope,
        syncRepository = sync,
        demoData = demoData,
        serverStatus = serverStatus,
        settingsRepository = settings,
        timerController = timers,
        accounts = accountDirectory,
        permissions = permissions,
    ) { nowMillis }

    // --- Phase 2 (konto lane) ---

    @Test
    fun theAccountCardShowsHouseholdRoleAndStoredAccounts() {
        accountDirectory.household.value = Household(3, "Familjen Exempel")
        accountDirectory.accounts.value = listOf(
            AccountSummary(ACCOUNT, isActive = true),
            AccountSummary(ACCOUNT.copy(id = "account-2", username = "bo"), isActive = false),
        )
        permissions.setGuest(true)

        val state = newViewModel().uiState.value

        assertEquals("Familjen Exempel", state.household)
        assertEquals(SpaceRole.GUEST, state.role)
        assertEquals(2, state.storedAccounts)
    }

    /** 0.3.0 review (fix-data, plan 8.6): an account without a household is warned; demo mode never. */
    @Test
    fun theAccountCardWarnsWithoutAHousehold() {
        permissions.state.value = permissions.state.value.copy(householdMissing = true)
        assertTrue(newViewModel().uiState.value.householdMissing)

        sessions.state.value = Session.Demo
        assertFalse(newViewModel().uiState.value.householdMissing)
    }

    @Test
    fun demoModeShowsNoHouseholdOrRole() {
        sessions.state.value = Session.Demo
        accountDirectory.household.value = Household(3, "Familjen Exempel")

        val state = newViewModel().uiState.value

        assertEquals(null, state.household)
        assertEquals(SpaceRole.UNKNOWN, state.role)
    }

    @Test
    fun aSwitchedAccountGetsItsOwnTimerSource() {
        val vm = newViewModel()
        assertEquals(ACCOUNT.id, vm.uiState.value.timerSource)

        // A timer of account-1 opens the timer list while account-2 is shown, never cook mode here.
        sessions.state.value = LOGGED_IN.copy(account = ACCOUNT.copy(id = "account-2"))

        assertEquals("account-2", vm.uiState.value.timerSource)
    }

    @Test
    fun exportingUnsentChangesBeforeALogoutSharesTheActiveAccountsChanges() = runTest {
        accountDirectory.accounts.value = listOf(AccountSummary(ACCOUNT, isActive = true, pendingChanges = 2))
        val vm = newViewModel()

        vm.pendingExports.test {
            vm.exportPendingChanges()
            assertTrue(awaitItem().contains("\"count\": 2"))
        }
        assertEquals(listOf(ACCOUNT.id), accountDirectory.exports)
    }

    // --- komplettering-3: the in-app notice for a server update (plan B18 fallback) ---

    private val update = ServerVersionChange("2.6.13", "2.6.15", ServerCompatibility.SUPPORTED)

    @Test
    fun aServerUpdateWithoutANotificationIsShownOnceAlsoAfterProcessDeath() {
        sessions.refreshResult = Outcome.Failure(TandemError.Timeout)
        serverStatus.versionNotice.value = update
        val vm = newViewModel()

        assertEquals("2.6.15", vm.uiState.value.serverUpdatedTo)
        // Marked as shown as soon as it appears, so a new process does not show it again.
        assertEquals(listOf(update), serverStatus.shownVersionNotices)
        assertNull(serverStatus.versionNotice.value)

        vm.dismissServerUpdate()
        assertNull(vm.uiState.value.serverUpdatedTo)

        // Process death: a new ViewModel on the same store has nothing to show.
        val restarted = newViewModel()
        assertNull(restarted.uiState.value.serverUpdatedTo)
        assertEquals(1, serverStatus.shownVersionNotices.size)
    }

    @Test
    fun noBannerWithoutAStoredNoticeOnTheFirstRunOrAfterANotification() = runTest {
        sessions.refreshResult = Outcome.Failure(TandemError.Timeout)
        val vm = newViewModel()
        // The first login and a change the system notification told about store no notice;
        // the change itself (observeVersionChanges) is not shown by the shell.
        serverStatus.emitVersionChange(update)

        assertNull(vm.uiState.value.serverUpdatedTo)
        assertTrue(serverStatus.shownVersionNotices.isEmpty())
    }

    @Test
    fun noBannerInDemoMode() {
        sessions.state.value = Session.Demo
        serverStatus.versionNotice.value = update
        val vm = newViewModel()

        assertNull(vm.uiState.value.serverUpdatedTo)
        assertTrue("not used up in demo mode", serverStatus.shownVersionNotices.isEmpty())
    }

    @Test
    fun noBannerNextToSafeModeAndNoneAfterLeavingTheAccount() {
        sessions.refreshResult = Outcome.Failure(TandemError.Timeout)
        sessions.state.value = LOGGED_IN.copy(serverVersion = "2.7.0", compatibility = ServerCompatibility.UNTESTED_NEWER)
        serverStatus.versionNotice.value = ServerVersionChange("2.6.13", "2.7.0", ServerCompatibility.UNTESTED_NEWER)
        val vm = newViewModel()
        assertNull("the safe mode banner tells about an untested version", vm.uiState.value.serverUpdatedTo)

        // Shown for a tested version, then gone as soon as the shell no longer shows the account.
        sessions.state.value = LOGGED_IN
        serverStatus.versionNotice.value = update
        assertEquals("2.6.15", vm.uiState.value.serverUpdatedTo)
        sessions.state.value = Session.Demo
        assertNull(vm.uiState.value.serverUpdatedTo)
    }

    @Test
    fun theSyncsUnauthorizedPauseAsksToLogInAgain() {
        sessions.refreshResult = Outcome.Failure(TandemError.Timeout)
        val vm = newViewModel()
        assertFalse(vm.uiState.value.loginExpired)

        syncStatus.value = SyncStatus(pause = SyncPause.UNAUTHORIZED)
        assertTrue(vm.uiState.value.loginExpired)
        assertFalse("unauthorized is not offline", vm.uiState.value.syncOffline)

        // Never for demo mode (the pause belongs to an account).
        sessions.state.value = Session.Demo
        assertFalse(vm.uiState.value.loginExpired)
    }

    @Test
    fun resetDemoDataReportsTheResult() = runTest {
        sessions.state.value = Session.Demo
        val vm = newViewModel()
        messages.messages.test {
            vm.resetDemoData()
            assertEquals(ShellMessage.DEMO_DATA_RESET, awaitItem())

            demoResetResult = Outcome.Failure(TandemError.Unknown())
            vm.resetDemoData()
            assertEquals(ShellMessage.DEMO_DATA_RESET_FAILED, awaitItem())
        }
        assertEquals(2, demoResets)
        assertFalse(vm.uiState.value.resettingDemo)
    }

    @Test
    fun resetDemoDataIsIgnoredForARealAccount() {
        sessions.refreshResult = Outcome.Failure(TandemError.Timeout)
        newViewModel().resetDemoData()
        assertEquals(0, demoResets)
    }

    @Test
    fun theSyncsNetworkPauseShowsTheOfflineChip() {
        sessions.refreshResult = Outcome.Failure(TandemError.Timeout) // not a connectivity problem
        val vm = newViewModel()
        assertFalse(vm.uiState.value.syncOffline)

        syncStatus.value = SyncStatus(pause = SyncPause.OFFLINE)
        assertTrue(vm.uiState.value.syncOffline)
        assertEquals(AppStatus.Offline, vm.uiState.value.appStatus)

        syncStatus.value = SyncStatus.Idle
        assertFalse(vm.uiState.value.syncOffline)
    }

    @Test
    fun loggedInSessionBecomesTheAccountCard() {
        sessions.refreshResult = Outcome.Failure(TandemError.Timeout) // keep the stored version
        val state = newViewModel().uiState.value

        val account = (state.mode as ShellMode.Connected).account
        assertEquals("Anna Andersson", account.displayName)
        assertEquals("anna", account.username)
        assertEquals("https://tandoor.example.com", account.serverUrl)
        assertEquals("tandoor.example.com", account.serverAddress)
        assertEquals("2.6.13", account.serverVersion)
        assertEquals(ServerCompatibility.SUPPORTED, account.compatibility)
        assertEquals("Hemma", account.spaceName)
        assertFalse(state.isDemo)
        assertFalse(state.isSafeMode)
        assertEquals(AppVersion("0.0.1-dev", 1, "sideload"), state.appVersion)
    }

    @Test
    fun displayNameFallsBackToTheUsername() {
        sessions.state.value = LOGGED_IN.copy(account = ACCOUNT.copy(displayName = null))
        val account = (newViewModel().uiState.value.mode as ShellMode.Connected).account
        assertEquals("anna", account.displayName)
    }

    @Test
    fun otherSessionsMapToDemoOrUnavailable() {
        assertEquals(ShellMode.Demo, Session.Demo.toShellMode())
        assertEquals(ShellMode.Unavailable, Session.NoAccount.toShellMode())
        assertEquals(ShellMode.Unavailable, Session.RecoveryNeeded(setOf(RecoveryReason.KEY_UNUSABLE)).toShellMode())
        assertEquals(ShellMode.Unavailable, (null as Session?).toShellMode())

        sessions.state.value = Session.Demo
        val state = newViewModel().uiState.value
        assertTrue(state.isDemo)
        assertEquals(0, sessions.refreshes)
    }

    @Test
    fun untestedServerVersionMeansSafeMode() {
        sessions.state.value = LOGGED_IN.copy(serverVersion = "2.7.0", compatibility = ServerCompatibility.UNTESTED_NEWER)
        sessions.refreshResult = Outcome.Failure(TandemError.Timeout)
        assertTrue(newViewModel().uiState.value.isSafeMode)
    }

    @Test
    fun serverVersionIsCheckedOnceWhenTheShellOpens() {
        val vm = newViewModel()

        assertEquals(1, sessions.refreshes)
        val account = (vm.uiState.value.mode as ShellMode.Connected).account
        assertEquals("the refreshed version is shown", "2.6.14", account.serverVersion)

        // The session flow emits again (new version) but it is the same account: no second check.
        assertEquals(1, sessions.refreshes)
    }

    @Test
    fun pullToRefreshChecksAgainAndShowsProgress() {
        val vm = newViewModel()
        val gate = CompletableDeferred<Unit>()
        sessions.refreshGate = gate

        vm.refreshServerInfo()
        assertTrue(vm.uiState.value.refreshing)
        vm.refreshServerInfo()
        assertEquals("no second check while one runs", 2, sessions.refreshes)

        gate.complete(Unit)
        assertFalse(vm.uiState.value.refreshing)
    }

    @Test
    fun failedCheckIsShownUntilTheNextSuccess() {
        sessions.refreshResult = Outcome.Failure(TandemError.Timeout)
        val vm = newViewModel()
        assertEquals(TandemError.Timeout, vm.uiState.value.refreshError)

        sessions.refreshResult = Outcome.Success(
            ServerInfo("https://tandoor.example.com/", "2.6.13", ServerCompatibility.SUPPORTED),
        )
        vm.refreshServerInfo()
        assertNull(vm.uiState.value.refreshError)
    }

    @Test
    fun noServerCheckInDemoMode() {
        sessions.state.value = Session.Demo
        val vm = newViewModel()
        vm.refreshServerInfo()
        assertEquals(0, sessions.refreshes)
    }

    @Test
    fun logoutRevokesTheDeviceTokenAndReportsIt() = runTest {
        val vm = newViewModel()
        messages.messages.test {
            vm.logout(revokeToken = true)

            assertEquals(listOf(true), sessions.logouts)
            assertEquals(ShellMessage.LOGGED_OUT, awaitItem())
        }
        assertEquals(ShellMode.Unavailable, vm.uiState.value.mode)
        assertFalse(vm.uiState.value.loggingOut)
    }

    @Test
    fun logoutWithoutRevokingKeepsTheTokenOnTheServer() = runTest {
        sessions.logoutResult = Outcome.Success(TokenRevocation.SKIPPED)
        val vm = newViewModel()
        messages.messages.test {
            vm.logout(revokeToken = false)
            assertEquals(listOf(false), sessions.logouts)
            assertEquals(ShellMessage.LOGGED_OUT, awaitItem())
        }
    }

    @Test
    fun logoutResultsBecomeMessages() {
        assertEquals(ShellMessage.LOGGED_OUT, ShellViewModel.messageFor(Outcome.Success(TokenRevocation.REVOKED)))
        assertEquals(ShellMessage.LOGGED_OUT, ShellViewModel.messageFor(Outcome.Success(TokenRevocation.ALREADY_INVALID)))
        assertEquals(ShellMessage.LOGGED_OUT, ShellViewModel.messageFor(Outcome.Success(TokenRevocation.SKIPPED)))
        assertEquals(
            ShellMessage.LOGGED_OUT_TOKEN_NOT_REVOKED,
            ShellViewModel.messageFor(Outcome.Success(TokenRevocation.FAILED)),
        )
        assertEquals(
            ShellMessage.LOGGED_OUT_PASTED_TOKEN,
            ShellViewModel.messageFor(Outcome.Success(TokenRevocation.UNKNOWN_TOKEN_ID)),
        )
        assertEquals(
            ShellMessage.LOGGED_OUT_PASTED_TOKEN,
            ShellViewModel.messageFor(Outcome.Success(TokenRevocation.NOT_OWNED)),
        )
        assertEquals(ShellMessage.LOGOUT_FAILED, ShellViewModel.messageFor(Outcome.Failure(TandemError.Unknown("x"))))
    }

    @Test
    fun failedLogoutKeepsTheAccount() = runTest {
        sessions.logoutResult = Outcome.Failure(TandemError.Unknown("tandem.data.secure-storage"))
        val vm = newViewModel()
        messages.messages.test {
            vm.logout(revokeToken = true)
            assertEquals(ShellMessage.LOGOUT_FAILED, awaitItem())
        }
        assertTrue(vm.uiState.value.mode is ShellMode.Connected)
        assertFalse(vm.uiState.value.loggingOut)
    }

    @Test
    fun theThemeFollowsTheStore() {
        val vm = newViewModel()
        // Changed under Settings → Appearance (SettingsViewModel); More shows it in the row's summary.
        appearance.mode.value = ThemeMode.DARK
        assertEquals(ThemeMode.DARK, vm.uiState.value.themeMode)
    }

    // --- Phase 1b (inställningar lane): settings summaries, the held-back sync, the timer bar ---

    @Test
    fun theSettingsRowsSummariseTheStoredSettings() {
        val vm = newViewModel()
        assertEquals(SettingsSummary(), vm.uiState.value.settings)

        settings.state.value = settings.state.value.copy(
            dynamicColor = true,
            timerMode = TimerMode.CLOCK_APP,
            syncInterval = SyncInterval.SIX_HOURS,
            downloadAllRecipes = true,
        )
        timers.setCapabilities(TimerCapabilities.AllGranted.copy(exactAlarmsAllowed = false, exactAlarmsRequestable = true))

        assertEquals(
            SettingsSummary(
                dynamicColor = true,
                timerMode = TimerMode.CLOCK_APP,
                syncInterval = SyncInterval.SIX_HOURS,
                downloadAllRecipes = true,
                timersNeedAttention = true,
            ),
            vm.uiState.value.settings,
        )
    }

    @Test
    fun aBlockedOrBreakerPausedSyncIsShownWithTryAgain() {
        val vm = newViewModel()
        syncStatus.value = SyncStatus.Idle.copy(pause = SyncPause.BLOCKED)
        assertEquals(SyncPause.BLOCKED, vm.uiState.value.syncHeldBack)
        syncStatus.value = SyncStatus.Idle.copy(pause = SyncPause.CIRCUIT_BREAKER)
        assertEquals(SyncPause.CIRCUIT_BREAKER, vm.uiState.value.syncHeldBack)

        vm.retrySync()

        assertEquals(listOf(SyncReason.USER_REFRESH), syncRequests)
    }

    @Test
    fun otherPausesAndDemoModeHaveNoHeldBackBanner() {
        val vm = newViewModel()
        listOf(SyncPause.OFFLINE, SyncPause.UNAUTHORIZED, SyncPause.SAFE_MODE, SyncPause.IMPORT_RUNNING).forEach { pause ->
            syncStatus.value = SyncStatus.Idle.copy(pause = pause)
            assertNull(pause.name, vm.uiState.value.syncHeldBack)
        }
        syncStatus.value = SyncStatus.Idle.copy(pause = SyncPause.CIRCUIT_BREAKER)
        sessions.state.value = Session.Demo
        assertNull(vm.uiState.value.syncHeldBack)
        vm.retrySync()
        assertTrue("no sync in demo mode", syncRequests.isEmpty())
    }

    @Test
    fun theTimerBarFollowsTheTimersAndStopsARingingOne() = runTest {
        val vm = newViewModel()
        timers.start("Pasta", 10.minutes, recipeId = 7, stepIndex = 2)
        assertEquals(listOf("Pasta"), vm.uiState.value.timers.map { it.label })

        timers.advanceTimeBy(10.minutes)
        assertEquals(TimerState.RINGING, vm.uiState.value.timers.single().state)

        vm.stopTimer(vm.uiState.value.timers.single().id)

        assertEquals(TimerState.FINISHED, vm.uiState.value.timers.single().state)
    }

    // --- Version check on resume (plan 3.6 point 3) ---

    @Test
    fun resumeChecksAgainOnlyAfterFifteenMinutes() {
        val vm = newViewModel()
        assertEquals("checked when the shell opens", 1, sessions.refreshes)

        vm.onAppResumed() // right after opening (the shell's first resume)
        nowMillis += 5 * MINUTE
        vm.onAppResumed()
        assertEquals(1, sessions.refreshes)

        nowMillis += 11 * MINUTE // 16 minutes after the first check
        vm.onAppResumed()
        assertEquals(2, sessions.refreshes)

        nowMillis += 1 * MINUTE
        vm.onAppResumed()
        assertEquals("counted from the last check", 2, sessions.refreshes)
    }

    @Test
    fun aFailedCheckAlsoStartsTheWait() {
        sessions.refreshResult = Outcome.Failure(TandemError.Timeout)
        val vm = newViewModel()
        nowMillis += 10 * MINUTE
        vm.onAppResumed()
        assertEquals("a broken server is not probed on every resume", 1, sessions.refreshes)
    }

    @Test
    fun pullToRefreshIsNotThrottled() {
        val vm = newViewModel()
        vm.refreshServerInfo()
        vm.refreshServerInfo()
        assertEquals(3, sessions.refreshes)
    }

    @Test
    fun noCheckOnResumeInDemoMode() {
        sessions.state.value = Session.Demo
        val vm = newViewModel()
        nowMillis += 60 * MINUTE
        vm.onAppResumed()
        assertEquals(0, sessions.refreshes)
    }

    // --- Logout of an account with a pasted token ---

    @Test
    fun accountCardKnowsWhereTheTokenCameFrom() {
        sessions.state.value = LOGGED_IN.copy(account = ACCOUNT.copy(tokenOrigin = TokenOrigin.PASTED))
        val account = (newViewModel().uiState.value.mode as ShellMode.Connected).account
        assertEquals(TokenOrigin.PASTED, account.tokenOrigin)
    }

    @Test
    fun logoutResultArrivesAfterTheShellIsGone() = runTest {
        // The account is removed first, so the shell's ViewModel is cleared while the token is
        // still being revoked; the message must still be posted.
        val gate = CompletableDeferred<Unit>()
        sessions.logoutGate = gate
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, viewModelFactory { initializer { newViewModel() } })[ShellViewModel::class.java]
        messages.messages.test {
            vm.logout(revokeToken = true)
            store.clear() // the shell leaves the screen: viewModelScope is cancelled
            gate.complete(Unit)
            assertEquals(ShellMessage.LOGGED_OUT, awaitItem())
        }
    }

    private companion object {
        const val MINUTE = 60_000L
    }

    // --- fix-ui (0.2.0 review) ---

    @Test
    fun unsentChangesOfTheShownAccountReachTheState() {
        val vm = newViewModel()
        syncStatus.value = SyncStatus.Idle.copy(pendingChanges = 3)
        assertEquals(3, vm.uiState.value.pendingChanges)

        syncStatus.value = SyncStatus.Idle.copy(pendingChanges = 0)
        assertEquals(0, vm.uiState.value.pendingChanges)

        // Demo mode has no outbox to lose.
        syncStatus.value = SyncStatus.Idle.copy(pendingChanges = 2)
        sessions.state.value = Session.Demo
        assertEquals(0, vm.uiState.value.pendingChanges)
    }

    @Test
    fun theTimerSourceIsTheAccountOrDemoMode() {
        val vm = newViewModel()
        assertEquals(ACCOUNT.id, vm.uiState.value.timerSource)

        sessions.state.value = Session.Demo
        assertEquals(TimerSource.DEMO, vm.uiState.value.timerSource)

        sessions.state.value = Session.NoAccount
        assertNull(vm.uiState.value.timerSource)
    }

    @Test
    fun theServersFeaturesAreShownForTheAccountOnly() {
        val vm = newViewModel()
        serverStatus.status.value = ServerStatus.Unknown.copy(
            capabilities = Capabilities("2.6.13", setOf(ServerFeature.COOK_LOG, ServerFeature.HOUSEHOLDS)),
        )
        assertEquals(setOf(ServerFeature.COOK_LOG, ServerFeature.HOUSEHOLDS), vm.uiState.value.serverFeatures)

        serverStatus.status.value = ServerStatus.Demo
        assertEquals(emptySet<ServerFeature>(), vm.uiState.value.serverFeatures)
    }
}
