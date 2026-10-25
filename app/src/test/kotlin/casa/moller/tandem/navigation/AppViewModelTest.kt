package casa.moller.tandem.navigation

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.data.settings.AppearanceRepository
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.settings.RecoveryReason
import casa.moller.tandem.core.model.settings.ThemeMode
import casa.moller.tandem.core.testing.data.FakeSettingsRepository
import casa.moller.tandem.feature.shell.ShellMessage
import casa.moller.tandem.feature.shell.ShellMessageQueue
import casa.moller.tandem.feature.shell.navigation.TimerOpenRequest
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    private val sessions = FakeSessionRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val appearance = FakeAppearanceRepository()
    private val shellMessages = ShellMessageQueue()

    private fun newViewModel(handle: SavedStateHandle = SavedStateHandle()) =
        AppViewModel(handle, sessions, appearance, shellMessages, FakeSettingsRepository())

    private fun AppViewModel.destination() = uiState.value.destination

    @Test
    fun destinationsFollowTheSession() {
        assertEquals(AppDestination.Loading, AppViewModel.resolveDestination(null, holdOnboarding = false))
        assertEquals(AppDestination.Onboarding, AppViewModel.resolveDestination(Session.NoAccount, false))
        assertEquals(AppDestination.Shell, AppViewModel.resolveDestination(LOGGED_IN, false))
        assertEquals(AppDestination.Shell, AppViewModel.resolveDestination(Session.Demo, false))
        assertEquals(AppDestination.Onboarding, AppViewModel.resolveDestination(LOGGED_IN, true))
        assertEquals(
            AppDestination.Recovery,
            AppViewModel.resolveDestination(Session.RecoveryNeeded(setOf(RecoveryReason.KEY_UNUSABLE)), true),
        )
    }

    @Test
    fun aTimerRequestIsKeptUntilTheShellOpenedIt() {
        sessions.state.value = LOGGED_IN
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        vm.onOpenTimer(TimerOpenRequest(recipeId = 6, stepIndex = 1))
        assertEquals(TimerOpenRequest(6, 1), vm.uiState.value.timerRequest)
        // Process death before the shell opened it: the request comes back once.
        assertEquals(TimerOpenRequest(6, 1), newViewModel(handle).uiState.value.timerRequest)
        vm.onTimerRequestHandled()
        assertEquals(null, vm.uiState.value.timerRequest)
        vm.onOpenTimer(TimerOpenRequest(recipeId = null, stepIndex = null))
        assertEquals(TimerOpenRequest(null, null), vm.uiState.value.timerRequest)
    }

    @Test
    fun aTimerRequestKeepsItsSourceAcrossProcessDeath() {
        sessions.state.value = LOGGED_IN
        val handle = SavedStateHandle()
        newViewModel(handle).onOpenTimer(TimerOpenRequest(recipeId = 6, stepIndex = 1, sourceKey = "demo"))
        assertEquals(TimerOpenRequest(6, 1, "demo"), newViewModel(handle).uiState.value.timerRequest)
    }

    @Test
    fun aTimerRequestDoesNotOutliveALogout() {
        sessions.state.value = LOGGED_IN
        val vm = newViewModel()
        vm.onOpenTimer(TimerOpenRequest(recipeId = 6, stepIndex = 0))
        sessions.state.value = Session.NoAccount
        assertEquals(null, vm.uiState.value.timerRequest)
    }

    @Test
    fun logInAgainOpensTheIntroAtTheServerStepForTheSameServer() {
        sessions.state.value = LOGGED_IN
        val vm = newViewModel()
        assertEquals(AppDestination.Shell, vm.destination())

        vm.logInAgain("https://tandoor.example.com")
        assertEquals("the stored account stays, the intro is shown", AppDestination.Onboarding, vm.destination())
        assertTrue(vm.uiState.value.startOnboardingAtServer)
        assertEquals("https://tandoor.example.com", vm.uiState.value.onboardingServerUrl)

        vm.onOnboardingFinished()
        assertEquals(AppDestination.Shell, vm.destination())
        assertEquals(null, vm.uiState.value.onboardingServerUrl)
    }

    @Test
    fun logInAgainCanBeCancelledBackToTheApp() {
        sessions.state.value = LOGGED_IN
        val vm = newViewModel()
        assertFalse(vm.uiState.value.canCancelOnboarding)

        vm.logInAgain("https://tandoor.example.com")
        assertTrue("the intro offers Cancel", vm.uiState.value.canCancelOnboarding)

        vm.cancelLogInAgain()
        assertEquals(AppDestination.Shell, vm.destination())
        assertEquals("the session is unchanged", LOGGED_IN, vm.uiState.value.session)
        assertFalse(vm.uiState.value.canCancelOnboarding)
        assertFalse(vm.uiState.value.startOnboardingAtServer)
        assertEquals(null, vm.uiState.value.onboardingServerUrl)
    }

    @Test
    fun theIntroCannotBeCancelledWithoutALoggedInAccount() {
        val vm = newViewModel()
        assertFalse(vm.uiState.value.canCancelOnboarding)

        vm.cancelLogInAgain()
        assertEquals(AppDestination.Onboarding, vm.destination())

        // A first login (no "Log in again") is held in the intro and cannot be cancelled either.
        sessions.state.value = LOGGED_IN
        vm.cancelLogInAgain()
        assertEquals(AppDestination.Onboarding, vm.destination())
        assertFalse(vm.uiState.value.canCancelOnboarding)
    }

    @Test
    fun loggedInUserGoesStraightHome() {
        sessions.state.value = LOGGED_IN
        assertEquals(AppDestination.Shell, newViewModel().destination())
    }

    @Test
    fun introStaysUntilItsConnectedStepIsDone() {
        val vm = newViewModel()
        assertEquals(AppDestination.Onboarding, vm.destination())

        sessions.state.value = LOGGED_IN
        assertEquals("login alone does not leave the intro", AppDestination.Onboarding, vm.destination())

        vm.onOnboardingFinished()
        assertEquals(AppDestination.Shell, vm.destination())
    }

    @Test
    fun demoLeavesTheIntroWithoutFlashingBack() {
        val vm = newViewModel()
        vm.onDemoStarted()
        assertEquals("still no demo session yet", AppDestination.Onboarding, vm.destination())
        sessions.state.value = Session.Demo
        assertEquals(AppDestination.Shell, vm.destination())
    }

    @Test
    fun connectFromDemoOpensTheServerStep() {
        sessions.state.value = Session.Demo
        val vm = newViewModel()

        vm.connectFromDemo()

        assertEquals(1, sessions.demoLeaves)
        assertEquals(AppDestination.Onboarding, vm.destination())
        assertTrue(vm.uiState.value.startOnboardingAtServer)
    }

    @Test
    fun holdSurvivesProcessDeath() {
        val handle = SavedStateHandle()
        newViewModel(handle)
        sessions.state.value = LOGGED_IN

        assertEquals(AppDestination.Onboarding, newViewModel(handle).destination())
    }

    @Test
    fun logoutFromTheShellReturnsToTheIntro() = runTest {
        sessions.state.value = LOGGED_IN
        val vm = newViewModel()
        assertEquals(AppDestination.Shell, vm.destination())

        sessions.logout(revokeToken = true)

        assertEquals(AppDestination.Onboarding, vm.destination())
        sessions.state.value = LOGGED_IN
        assertEquals("the next login is held in the intro again", AppDestination.Onboarding, vm.destination())
    }

    @Test
    fun recoveryDuringTheIntroDoesNotLeaveAStaleHold() {
        // Fresh install, intro open; the first Keystore use fails while a login is saved.
        val vm = newViewModel()
        assertEquals(AppDestination.Onboarding, vm.destination())

        sessions.state.value = Session.RecoveryNeeded(setOf(RecoveryReason.KEY_UNUSABLE))
        assertEquals(AppDestination.Recovery, vm.destination())

        // "Log in again": the account was stored after all, so the shell opens, not a fresh intro.
        vm.acknowledgeRecovery()
        sessions.state.value = LOGGED_IN
        assertEquals(AppDestination.Shell, vm.destination())
    }

    @Test
    fun recoveryWithoutAnAccountGoesBackToTheIntro() {
        sessions.state.value = LOGGED_IN
        val vm = newViewModel()
        sessions.state.value = Session.RecoveryNeeded(setOf(RecoveryReason.ACCOUNTS_UNREADABLE))
        assertEquals(AppDestination.Recovery, vm.destination())

        vm.acknowledgeRecovery()
        sessions.state.value = Session.NoAccount
        assertEquals(AppDestination.Onboarding, vm.destination())
        sessions.state.value = LOGGED_IN
        assertEquals("a new login is held in the intro again", AppDestination.Onboarding, vm.destination())
    }

    @Test
    fun themeComesFromTheSettings() {
        val vm = newViewModel()
        assertTrue(vm.uiState.value.themeModeLoaded)
        assertEquals(ThemeMode.SYSTEM, vm.uiState.value.themeMode)
        appearance.mode.value = ThemeMode.DARK
        assertEquals(ThemeMode.DARK, vm.uiState.value.themeMode)
    }

    @Test
    fun shellMessagesReachTheApp() = runTest {
        val vm = newViewModel()
        vm.messages.test {
            shellMessages.post(ShellMessage.LOGGED_OUT_TOKEN_NOT_REVOKED)
            assertEquals(AppMessage.FromShell(ShellMessage.LOGGED_OUT_TOKEN_NOT_REVOKED), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun failedClearDataIsReported() = runTest {
        sessions.clearResult = Outcome.Failure(TandemError.Unknown("test"))
        val vm = newViewModel()
        vm.messages.test {
            vm.clearAppData()
            assertEquals(AppMessage.ClearDataFailed, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private class FakeAppearanceRepository : AppearanceRepository {
        val mode = MutableStateFlow(ThemeMode.SYSTEM)
        override val themeMode: Flow<ThemeMode> = mode

        override suspend fun setThemeMode(mode: ThemeMode): Outcome<Unit> {
            this.mode.value = mode
            return Outcome.Success(Unit)
        }
    }

    private class FakeSessionRepository : SessionRepository {
        val state = MutableStateFlow<Session>(Session.NoAccount)
        val logouts = mutableListOf<Boolean>()
        var demoLeaves = 0
        var refreshes = 0

        override val session: Flow<Session> = state

        override suspend fun refreshServerInfo(): Outcome<ServerInfo> {
            refreshes++
            return Outcome.Success(ServerInfo("https://tandoor.example.com/", "2.6.13", ServerCompatibility.SUPPORTED))
        }

        override suspend fun logout(revokeToken: Boolean): Outcome<TokenRevocation> {
            logouts += revokeToken
            state.value = Session.NoAccount
            return Outcome.Success(TokenRevocation.REVOKED)
        }

        override suspend fun startDemo(): Outcome<Unit> {
            state.value = Session.Demo
            return Outcome.Success(Unit)
        }

        override suspend fun leaveDemo(): Outcome<Unit> {
            demoLeaves++
            state.value = Session.NoAccount
            return Outcome.Success(Unit)
        }

        override fun acknowledgeRecovery() = Unit

        var clearResult: Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun clearAppData(): Outcome<Unit> = clearResult
    }

    private companion object {
        val LOGGED_IN = Session.LoggedIn(
            account = ServerAccount(
                id = "a",
                baseUrl = "https://tandoor.example.com",
                username = "anna",
                userId = 7,
                spaceId = 1,
                serverVersion = "2.6.13",
                addedAt = Instant.parse("2026-10-09T12:00:00Z"),
            ),
            serverVersion = "2.6.13",
            compatibility = ServerCompatibility.SUPPORTED,
        )
    }
}
