package casa.moller.tandem.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.model.error.InvalidUrlReason
import casa.moller.tandem.core.model.error.NotTandoorReason
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.TlsErrorKind
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.core.model.server.ServerCompatibility
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class OnboardingViewModelTest {

    private val accounts = FakeAccountRepository()
    private val session = FakeSessionRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(handle: SavedStateHandle = SavedStateHandle(), config: OnboardingConfig = OnboardingConfig()) =
        viewModel(accounts, session, handle, config)

    /** Welcome -> server step with [url] entered. */
    private fun OnboardingViewModel.atServerStep(url: String = "tandoor.example.com") = apply {
        onGetStarted()
        onServerUrlChange(url)
    }

    /** Through a successful probe to the login step. */
    private fun OnboardingViewModel.atLoginStep() = apply {
        atServerStep()
        onConnect()
        assertEquals(OnboardingStep.LOGIN, uiState.value.step)
    }

    @Test
    fun startsOnWelcomeWithAnEmptyAddress() {
        val state = newViewModel().uiState.value
        assertEquals(OnboardingStep.WELCOME, state.step)
        assertEquals("", state.serverUrl)
        assertFalse(state.canConnect)
    }

    @Test
    fun configuredDefaultUrlPrefillsTheField() {
        val vm = newViewModel(config = OnboardingConfig(defaultServerUrl = "https://tandoor.example.com"))
        assertEquals("https://tandoor.example.com", vm.uiState.value.serverUrl)
    }

    @Test
    fun getStartedAndBackMoveBetweenSteps() {
        val vm = newViewModel()
        vm.onGetStarted()
        assertEquals(OnboardingStep.SERVER, vm.uiState.value.step)
        vm.onBack()
        assertEquals(OnboardingStep.WELCOME, vm.uiState.value.step)
    }

    @Test
    fun startCanSkipToTheServerStepOnce() {
        val vm = newViewModel()
        vm.start(atServerStep = true)
        assertEquals(OnboardingStep.SERVER, vm.uiState.value.step)
        vm.onBack()
        vm.start(atServerStep = true)
        assertEquals("start is ignored the second time", OnboardingStep.WELCOME, vm.uiState.value.step)
    }

    @Test
    fun startFillsInTheServerAddressToLogInAgain() {
        val vm = newViewModel()
        vm.start(atServerStep = true, serverUrl = "https://tandoor.example.com/recipes")
        assertEquals(OnboardingStep.SERVER, vm.uiState.value.step)
        assertEquals("https://tandoor.example.com/recipes", vm.uiState.value.serverUrl)
    }

    @Test
    fun blankAddressFailsLocallyWithoutProbing() {
        val vm = newViewModel().atServerStep(url = "  ")
        vm.onConnect()
        assertEquals(
            ServerCheck.Failed(TandemError.InvalidUrl(InvalidUrlReason.EMPTY)),
            vm.uiState.value.serverCheck,
        )
        assertTrue(accounts.probes.isEmpty())
    }

    @Test
    fun supportedServerGoesToLogin() = runTest {
        val gate = CompletableDeferred<Unit>()
        accounts.gate = gate
        val vm = newViewModel().atServerStep()

        vm.onConnect()
        assertEquals(ServerCheck.Checking, vm.uiState.value.serverCheck)
        assertFalse("no second probe while checking", vm.uiState.value.canConnect)
        gate.complete(Unit)

        val state = vm.uiState.value
        assertEquals(OnboardingStep.LOGIN, state.step)
        assertEquals(SUPPORTED_SERVER, state.server)
        assertEquals(ServerCheck.Idle, state.serverCheck)
        assertEquals(listOf("tandoor.example.com"), accounts.probes)
    }

    @Test
    fun probeErrorsStayOnTheServerStep() {
        val error = TandemError.TlsError(TlsErrorKind.UNTRUSTED_CERTIFICATE)
        accounts.probeResult = Outcome.Failure(error)
        val vm = newViewModel().atServerStep()

        vm.onConnect()

        assertEquals(OnboardingStep.SERVER, vm.uiState.value.step)
        assertEquals(ServerCheck.Failed(error), vm.uiState.value.serverCheck)
        assertNull(vm.uiState.value.server)
    }

    @Test
    fun editingTheAddressClearsTheErrorAndCancelsAProbe() {
        accounts.gate = CompletableDeferred()
        val vm = newViewModel().atServerStep()
        vm.onConnect()

        vm.onServerUrlChange("other.example.com")

        assertEquals(ServerCheck.Idle, vm.uiState.value.serverCheck)
        accounts.gate?.complete(Unit)
        assertEquals("the cancelled probe must not move on", OnboardingStep.SERVER, vm.uiState.value.step)
    }

    @Test
    fun untestedVersionNeedsConfirmationAndContinuesInSafeMode() {
        accounts.probeResult = Outcome.Success(NEWER_SERVER)
        val vm = newViewModel().atServerStep()

        vm.onConnect()
        assertEquals(ServerCheck.NeedsConfirmation(NEWER_SERVER), vm.uiState.value.serverCheck)
        assertEquals(OnboardingStep.SERVER, vm.uiState.value.step)

        vm.onContinueInSafeMode()
        assertEquals(OnboardingStep.LOGIN, vm.uiState.value.step)
        assertEquals(NEWER_SERVER, vm.uiState.value.server)
    }

    @Test
    fun tooOldVersionIsBlocked() {
        accounts.probeResult = Outcome.Success(OLD_SERVER)
        val vm = newViewModel().atServerStep()

        vm.onConnect()
        vm.onContinueInSafeMode()

        assertEquals(ServerCheck.TooOld(OLD_SERVER), vm.uiState.value.serverCheck)
        assertEquals(OnboardingStep.SERVER, vm.uiState.value.step)
    }

    @Test
    fun loginNeedsUsernameAndPassword() {
        val vm = newViewModel().atLoginStep()
        vm.onUsernameChange("anna")
        assertFalse(vm.uiState.value.canLogIn)
        vm.onLogin()
        assertTrue(accounts.logins.isEmpty())
        vm.onPasswordChange("secret")
        assertTrue(vm.uiState.value.canLogIn)
    }

    @Test
    fun successfulLoginShowsTheConnectedSummaryAndForgetsThePassword() {
        val gate = CompletableDeferred<Unit>()
        val vm = newViewModel().atLoginStep()
        vm.onUsernameChange("anna")
        vm.onPasswordChange("secret")
        accounts.gate = gate

        vm.onLogin()
        assertTrue(vm.uiState.value.loginInProgress)
        assertFalse(vm.uiState.value.canLogIn)
        vm.onBack()
        assertEquals("back is ignored while logging in", OnboardingStep.LOGIN, vm.uiState.value.step)
        gate.complete(Unit)

        val state = vm.uiState.value
        assertEquals(OnboardingStep.CONNECTED, state.step)
        assertEquals("", state.password)
        assertFalse(state.loginInProgress)
        assertEquals(
            ConnectedSummary(
                serverAddress = "tandoor.example.com",
                serverVersion = "2.6.13",
                accountLabel = "Anna",
                safeMode = false,
            ),
            state.connected,
        )
        assertEquals(listOf(Triple(SUPPORTED_SERVER, "anna", "secret")), accounts.logins)
    }

    @Test
    fun wrongPasswordIsShownAndNotRetried() {
        accounts.loginResult = Outcome.Failure(
            TandemError.BadRequest(mapOf(TandemError.BadRequest.NON_FIELD_ERRORS to listOf("Unable to log in"))),
        )
        val vm = newViewModel().atLoginStep()
        vm.onUsernameChange("anna")
        vm.onPasswordChange("wrong")

        vm.onLogin()

        assertEquals(LoginError.WrongCredentials, vm.uiState.value.loginError)
        assertEquals(OnboardingStep.LOGIN, vm.uiState.value.step)
        assertEquals(1, accounts.logins.size)
        vm.onPasswordChange("right")
        assertNull("typing clears the error", vm.uiState.value.loginError)
    }

    @Test
    fun throttledLoginShowsRetryAfterAndIsNotRetried() {
        accounts.loginResult = Outcome.Failure(TandemError.Throttled(retryAfter = 7.hours))
        val vm = newViewModel().atLoginStep()
        vm.onUsernameChange("anna")
        vm.onPasswordChange("secret")

        vm.onLogin()

        assertEquals(LoginError.Throttled(7.hours), vm.uiState.value.loginError)
        assertEquals(1, accounts.logins.size)
    }

    @Test
    fun pastedTokenLogsIn() {
        val vm = newViewModel().atLoginStep()
        vm.onLoginMethodChange(LoginMethod.TOKEN)
        assertFalse(vm.uiState.value.canLogIn)
        vm.onTokenChange("tda_pasted")

        vm.onLogin()

        assertEquals(listOf(SUPPORTED_SERVER to "tda_pasted"), accounts.tokenLogins)
        assertTrue(accounts.logins.isEmpty())
        assertEquals(OnboardingStep.CONNECTED, vm.uiState.value.step)
        assertEquals("", vm.uiState.value.token)
    }

    @Test
    fun rejectedTokenIsAnInvalidToken() {
        accounts.tokenLoginResult = Outcome.Failure(TandemError.Unauthorized(UnauthorizedSource.TANDOOR))
        val vm = newViewModel().atLoginStep()
        vm.onLoginMethodChange(LoginMethod.TOKEN)
        vm.onTokenChange("tda_bad")

        vm.onLogin()

        assertEquals(LoginError.InvalidToken, vm.uiState.value.loginError)
    }

    @Test
    fun accountWithoutASpaceShowsTheNoSpaceError() {
        // The data layer reports NO_SPACE when Tandoor refuses the space read with 403 + JSON.
        accounts.loginResult = Outcome.Failure(DataProblem.NO_SPACE.toError())
        accounts.tokenLoginResult = Outcome.Failure(DataProblem.NO_SPACE.toError())
        val vm = newViewModel().atLoginStep()
        vm.onUsernameChange("anna")
        vm.onPasswordChange("secret")

        vm.onLogin()

        assertEquals(LoginError.NoSpace, vm.uiState.value.loginError)
        assertEquals(OnboardingStep.LOGIN, vm.uiState.value.step)

        vm.onLoginMethodChange(LoginMethod.TOKEN)
        vm.onTokenChange("tda_pasted")
        vm.onLogin()
        assertEquals(LoginError.NoSpace, vm.uiState.value.loginError)
    }

    @Test
    fun olderUntestedVersionNeedsConfirmationLikeANewerOne() {
        accounts.probeResult = Outcome.Success(
            SUPPORTED_SERVER.copy(version = "2.5.3", compatibility = ServerCompatibility.UNTESTED_OLDER),
        )
        val vm = newViewModel().atServerStep()

        vm.onConnect()
        assertTrue(vm.uiState.value.serverCheck is ServerCheck.NeedsConfirmation)
        vm.onContinueInSafeMode()

        assertEquals(OnboardingStep.LOGIN, vm.uiState.value.step)
    }

    @Test
    fun untestedServerLoginIsMarkedAsSafeMode() {
        accounts.probeResult = Outcome.Success(NEWER_SERVER)
        accounts.loginResult = Outcome.Success(ACCOUNT.copy(serverVersion = "2.7.1"))
        val vm = newViewModel().atServerStep()
        vm.onConnect()
        vm.onContinueInSafeMode()
        vm.onUsernameChange("anna")
        vm.onPasswordChange("secret")

        vm.onLogin()

        assertEquals(true, vm.uiState.value.connected?.safeMode)
        assertEquals("2.7.1", vm.uiState.value.connected?.serverVersion)
    }

    @Test
    fun anUnexpectedThrowableDuringLoginShowsTheGenericErrorInsteadOfCrashing() {
        val vm = newViewModel().atLoginStep()
        vm.onUsernameChange("anna")
        vm.onPasswordChange("secret")
        // v0.0.1: R8 broke the Retrofit setup and login threw ClassCastException, which killed
        // the app from viewModelScope.
        accounts.unexpected = ClassCastException()

        vm.onLogin()

        val state = vm.uiState.value
        assertEquals(OnboardingStep.LOGIN, state.step)
        assertFalse(state.loginInProgress)
        assertEquals(LoginError.Other(TandemError.Unknown("java.lang.ClassCastException")), state.loginError)

        // Nothing is stuck: the user can try again.
        accounts.unexpected = null
        vm.onLogin()
        assertEquals(OnboardingStep.CONNECTED, vm.uiState.value.step)
    }

    @Test
    fun anUnexpectedThrowableDuringTheProbeShowsTheGenericError() {
        val vm = newViewModel().atServerStep()
        accounts.unexpected = NoClassDefFoundError("x")

        vm.onConnect()

        assertEquals(ServerCheck.Failed(TandemError.Unknown("java.lang.NoClassDefFoundError")), vm.uiState.value.serverCheck)
    }

    @Test
    fun loginErrorsAreMappedPerMethod() {
        val badRequest = TandemError.BadRequest()
        assertEquals(LoginError.WrongCredentials, OnboardingViewModel.loginErrorFor(badRequest, LoginMethod.PASSWORD))
        assertEquals(LoginError.InvalidToken, OnboardingViewModel.loginErrorFor(badRequest, LoginMethod.TOKEN))
        assertEquals(
            LoginError.NoSpace,
            OnboardingViewModel.loginErrorFor(DataProblem.NO_SPACE.toError(), LoginMethod.PASSWORD),
        )
        assertEquals(
            LoginError.StorageFailed,
            OnboardingViewModel.loginErrorFor(DataProblem.SECURE_STORAGE.toError(), LoginMethod.TOKEN),
        )
        val proxy = TandemError.Unauthorized(UnauthorizedSource.PROXY)
        assertEquals(LoginError.Other(proxy), OnboardingViewModel.loginErrorFor(proxy, LoginMethod.TOKEN))
        assertEquals(
            LoginError.Other(TandemError.Timeout),
            OnboardingViewModel.loginErrorFor(TandemError.Timeout, LoginMethod.PASSWORD),
        )
    }

    @Test
    fun aMissingPasswordLoginEndpointPointsToTheToken() {
        // Tandoor 2.7.1: POST /api-token-auth/ -> 302 to /account/login/?next=/api-token-auth/.
        val redirect = TandemError.NotTandoor(NotTandoorReason.REDIRECT, 302)
        assertEquals(LoginError.PasswordLoginUnavailable, OnboardingViewModel.loginErrorFor(redirect, LoginMethod.PASSWORD))
        val loginRedirect = TandemError.NotTandoor(NotTandoorReason.LOGIN_REDIRECT, 302)
        assertEquals(
            LoginError.PasswordLoginUnavailable,
            OnboardingViewModel.loginErrorFor(loginRedirect, LoginMethod.PASSWORD),
        )
        val htmlNotFound = TandemError.NotTandoor(NotTandoorReason.UNEXPECTED_STATUS, 404)
        assertEquals(
            LoginError.PasswordLoginUnavailable,
            OnboardingViewModel.loginErrorFor(htmlNotFound, LoginMethod.PASSWORD),
        )
        assertEquals(
            LoginError.PasswordLoginUnavailable,
            OnboardingViewModel.loginErrorFor(TandemError.NotFound, LoginMethod.PASSWORD),
        )
        // The token login never used api-token-auth, and an HTML page stays "not Tandoor".
        assertEquals(LoginError.Other(redirect), OnboardingViewModel.loginErrorFor(redirect, LoginMethod.TOKEN))
        val html = TandemError.NotTandoor(NotTandoorReason.HTML_RESPONSE, 200)
        assertEquals(LoginError.Other(html), OnboardingViewModel.loginErrorFor(html, LoginMethod.PASSWORD))
    }

    @Test
    fun backFromLoginForgetsPasswordAndToken() {
        val vm = newViewModel().atLoginStep()
        vm.onUsernameChange("anna")
        vm.onPasswordChange("secret")
        vm.onTokenChange("tda_x")

        vm.onBack()

        val state = vm.uiState.value
        assertEquals(OnboardingStep.SERVER, state.step)
        assertEquals("", state.password)
        assertEquals("", state.token)
        assertEquals("anna", state.username)
    }

    @Test
    fun demoModeStartsAndTellsTheScreen() = runTest {
        val vm = newViewModel()
        vm.events.test {
            vm.onStartDemo()
            assertEquals(OnboardingEvent.DemoStarted, awaitItem())
        }
        assertEquals(1, session.demoStarts)
    }

    @Test
    fun failedDemoStartStaysInTheIntro() = runTest {
        session.startDemoResult = Outcome.Failure(DataProblem.SECURE_STORAGE.toError())
        val vm = newViewModel()
        vm.events.test {
            vm.onStartDemo()
            expectNoEvents()
        }
        assertEquals(1, session.demoStarts)
    }

    @Test
    fun stateSurvivesProcessDeathWithoutThePassword() {
        val handle = SavedStateHandle()
        val vm = newViewModel(handle).atLoginStep()
        vm.onUsernameChange("anna")
        vm.onPasswordChange("secret")

        assertFalse(
            "the password must never reach the saved state",
            handle.keys().any { handle.get<Any?>(it) == "secret" },
        )

        val restored = newViewModel(handle).uiState.value
        assertEquals(OnboardingStep.LOGIN, restored.step)
        assertEquals(SUPPORTED_SERVER, restored.server)
        assertEquals("tandoor.example.com", restored.serverUrl)
        assertEquals("anna", restored.username)
        assertEquals("", restored.password)
    }

    @Test
    fun connectedStepSurvivesProcessDeath() {
        val handle = SavedStateHandle()
        val vm = newViewModel(handle).atLoginStep()
        vm.onUsernameChange("anna")
        vm.onPasswordChange("secret")
        vm.onLogin()

        val restored = newViewModel(handle).uiState.value
        assertEquals(OnboardingStep.CONNECTED, restored.step)
        assertEquals(vm.uiState.value.connected, restored.connected)
    }

    @Test
    fun stateToStringHidesSecrets() {
        val text = OnboardingUiState(password = "secret", token = "tda_token").toString()
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("tda_token"))
    }
}
