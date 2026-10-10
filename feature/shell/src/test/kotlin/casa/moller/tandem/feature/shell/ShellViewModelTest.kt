package casa.moller.tandem.feature.shell

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cash.turbine.test
import casa.moller.tandem.core.common.AppBuildInfo
import casa.moller.tandem.core.data.server.ServerCompatibility
import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.model.account.TokenOrigin
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.settings.RecoveryReason
import casa.moller.tandem.core.model.settings.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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

    private fun newViewModel() = ShellViewModel(sessions, appearance, messages, buildInfo, applicationScope) { nowMillis }

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
    fun themeChoiceIsStored() {
        val vm = newViewModel()
        vm.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, appearance.mode.value)
        assertEquals(ThemeMode.DARK, vm.uiState.value.themeMode)
    }

    @Test
    fun themeThatCannotBeSavedIsReported() = runTest {
        appearance.result = Outcome.Failure(TandemError.Unknown("tandem.data.settings-not-saved"))
        val vm = newViewModel()
        messages.messages.test {
            vm.setThemeMode(ThemeMode.LIGHT)
            assertEquals(ShellMessage.SETTINGS_NOT_SAVED, awaitItem())
        }
        assertEquals(ThemeMode.SYSTEM, vm.uiState.value.themeMode)
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
}
