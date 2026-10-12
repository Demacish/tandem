package casa.moller.tandem.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.account.AccountRepository
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.sync.SyncRepository
import casa.moller.tandem.core.model.error.InvalidUrlReason
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.sync.SyncPhase
import casa.moller.tandem.core.model.sync.SyncProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State holder of the intro (welcome -> server -> login -> connected -> first sync -> battery tip,
 * plan 6.2).
 *
 * Survives rotation (ViewModel) and process death ([SavedStateHandle]: step, address, probed
 * server, login method, username, connected summary). The password and a pasted token are kept
 * in memory only and are never saved.
 *
 * Nothing is retried automatically: every probe, login and first sync retry is started by the
 * user. Leaving the first sync early never cancels it: [SyncRepository.firstSync] goes on in the
 * background.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val accountRepository: AccountRepository,
    private val sessionRepository: SessionRepository,
    private val config: OnboardingConfig,
    private val syncRepository: SyncRepository,
) : ViewModel(),
    OnboardingActions {

    private val state = MutableStateFlow(restore(savedStateHandle, config))
    val uiState: StateFlow<OnboardingUiState> = state.asStateFlow()

    private val eventChannel = Channel<OnboardingEvent>(Channel.BUFFERED)
    val events: Flow<OnboardingEvent> = eventChannel.receiveAsFlow()

    private var probeJob: Job? = null
    private var firstSyncJob: Job? = null
    private var demoStarting = false

    init {
        // After process death on the first sync step: follow (or restart) the sync again.
        if (state.value.step == OnboardingStep.FIRST_SYNC) startFirstSync()
    }

    /**
     * Called once when the intro is shown. [atServerStep] skips the welcome step, e.g. for
     * "Connect your Tandoor" from demo mode; [serverUrl] fills in the address ("Log in again"
     * after Tandoor refused the stored token). Ignored after process death or rotation.
     */
    fun start(atServerStep: Boolean, serverUrl: String? = null) {
        if (savedStateHandle.get<Boolean>(KEY_STARTED) == true) return
        savedStateHandle[KEY_STARTED] = true
        if (!serverUrl.isNullOrBlank()) {
            setState { it.copy(serverUrl = serverUrl) }
        }
        if (atServerStep && state.value.step == OnboardingStep.WELCOME) {
            setState { it.copy(step = OnboardingStep.SERVER) }
        }
    }

    override fun onGetStarted() = setState { it.copy(step = OnboardingStep.SERVER) }

    override fun onBack() {
        when (state.value.step) {
            OnboardingStep.SERVER -> {
                probeJob?.cancel()
                setState { it.copy(step = OnboardingStep.WELCOME, serverCheck = ServerCheck.Idle) }
            }

            OnboardingStep.LOGIN -> {
                if (state.value.loginInProgress) return
                setState {
                    it.copy(
                        step = OnboardingStep.SERVER,
                        serverCheck = ServerCheck.Idle,
                        password = "",
                        token = "",
                        loginError = null,
                    )
                }
            }

            OnboardingStep.WELCOME, OnboardingStep.CONNECTED, OnboardingStep.FIRST_SYNC, OnboardingStep.BATTERY_TIP -> Unit
        }
    }

    override fun onServerUrlChange(value: String) {
        probeJob?.cancel()
        setState { it.copy(serverUrl = value, serverCheck = ServerCheck.Idle) }
    }

    override fun onConnect() {
        val current = state.value
        if (current.serverCheck == ServerCheck.Checking) return
        if (current.serverUrl.isBlank()) {
            setState { it.copy(serverCheck = ServerCheck.Failed(TandemError.InvalidUrl(InvalidUrlReason.EMPTY))) }
            return
        }
        setState { it.copy(serverCheck = ServerCheck.Checking) }
        probeJob = viewModelScope.launch {
            // An unexpected throwable must show the generic error, not kill the app.
            val result = catchingUnexpected { accountRepository.probe(current.serverUrl) }
            setState { now ->
                when (result) {
                    is Outcome.Failure -> now.copy(serverCheck = ServerCheck.Failed(result.error))
                    is Outcome.Success -> withServer(now, result.value)
                }
            }
        }
    }

    private fun withServer(now: OnboardingUiState, server: ServerInfo): OnboardingUiState = when (server.compatibility) {
        ServerCompatibility.SUPPORTED -> now.toLoginStep(server)

        ServerCompatibility.UNTESTED_NEWER, ServerCompatibility.UNTESTED_OLDER, ServerCompatibility.UNRECOGNIZED ->
            now.copy(serverCheck = ServerCheck.NeedsConfirmation(server))

        ServerCompatibility.TOO_OLD -> now.copy(serverCheck = ServerCheck.TooOld(server))
    }

    override fun onContinueInSafeMode() {
        val check = state.value.serverCheck as? ServerCheck.NeedsConfirmation ?: return
        setState { it.toLoginStep(check.server) }
    }

    private fun OnboardingUiState.toLoginStep(server: ServerInfo) = copy(
        step = OnboardingStep.LOGIN,
        server = server,
        serverCheck = ServerCheck.Idle,
        loginError = null,
    )

    override fun onUsernameChange(value: String) = setState { it.copy(username = value, loginError = null) }

    override fun onPasswordChange(value: String) = setState { it.copy(password = value, loginError = null) }

    override fun onTokenChange(value: String) = setState { it.copy(token = value, loginError = null) }

    override fun onLoginMethodChange(method: LoginMethod) {
        if (state.value.loginInProgress) return
        setState { it.copy(loginMethod = method, loginError = null) }
    }

    override fun onLogin() {
        val current = state.value
        if (!current.canLogIn) return
        val server = current.server ?: return
        setState { it.copy(loginInProgress = true, loginError = null) }
        viewModelScope.launch {
            // An unexpected throwable (a bug, a broken release build) shows the generic login
            // error instead of killing the app; the user can try again.
            val result = catchingUnexpected {
                when (current.loginMethod) {
                    LoginMethod.PASSWORD -> accountRepository.login(server, current.username, current.password)
                    LoginMethod.TOKEN -> accountRepository.loginWithToken(server, current.token)
                }
            }
            setState { now ->
                when (result) {
                    is Outcome.Success -> now.copy(
                        step = OnboardingStep.CONNECTED,
                        loginInProgress = false,
                        password = "",
                        token = "",
                        connected = ConnectedSummary(
                            serverAddress = server.displayAddress,
                            serverVersion = result.value.serverVersion ?: server.version,
                            accountLabel = result.value.label,
                            safeMode = server.compatibility.isSafeMode,
                        ),
                    )

                    is Outcome.Failure -> now.copy(
                        loginInProgress = false,
                        loginError = loginErrorFor(result.error, current.loginMethod),
                    )
                }
            }
        }
    }

    override fun onStartDemo() {
        if (demoStarting) return
        demoStarting = true
        viewModelScope.launch {
            // Only leave the intro when demo mode is really on; a failed settings write keeps
            // the user here instead of bouncing between screens.
            if (sessionRepository.startDemo() is Outcome.Success) eventChannel.send(OnboardingEvent.DemoStarted)
            demoStarting = false
        }
    }

    override fun onContinueAfterLogin() {
        if (state.value.step != OnboardingStep.CONNECTED) return
        setState { it.copy(step = OnboardingStep.FIRST_SYNC, firstSync = FirstSyncState()) }
        startFirstSync()
    }

    override fun onRetryFirstSync() {
        if (state.value.step != OnboardingStep.FIRST_SYNC || state.value.firstSync.error == null) return
        setState { it.copy(firstSync = it.firstSync.copy(error = null)) }
        startFirstSync()
    }

    override fun onContinueFirstSyncInBackground() {
        if (state.value.step != OnboardingStep.FIRST_SYNC) return
        // Only stops following it here; the sync itself goes on (SyncRepository.firstSync).
        firstSyncJob?.cancel()
        afterFirstSync()
    }

    override fun onBatteryTipDone() {
        if (state.value.step != OnboardingStep.BATTERY_TIP) return
        finish()
    }

    private fun startFirstSync() {
        firstSyncJob?.cancel()
        firstSyncJob = viewModelScope.launch {
            syncRepository.firstSync()
                // The contract says firstSync never throws for network errors; anything else
                // (a bug) must still leave the user a way on instead of a frozen step.
                .catch { emit(SyncProgress(SyncPhase.FAILED, error = TandemError.Unknown(it::class.simpleName))) }
                .collect { progress ->
                    when (progress.phase) {
                        SyncPhase.FAILED -> setState {
                            it.copy(firstSync = it.firstSync.copy(error = progress.error ?: TandemError.Unknown()))
                        }

                        SyncPhase.DONE -> {
                            setState { it.copy(firstSync = it.firstSync.copy(progress = progress)) }
                            afterFirstSync()
                        }

                        else -> setState {
                            val total = progress.total.takeIf { progress.phase == SyncPhase.RECIPE_INDEX }
                            it.copy(firstSync = it.firstSync.copy(progress = progress, recipeCount = total ?: it.firstSync.recipeCount))
                        }
                    }
                }
        }
    }

    /** The first sync is done or left to the background: the battery tip on Samsung, else the end. */
    private fun afterFirstSync() {
        if (config.showBatteryTip) {
            setState { it.copy(step = OnboardingStep.BATTERY_TIP) }
        } else {
            finish()
        }
    }

    private fun finish() {
        viewModelScope.launch { eventChannel.send(OnboardingEvent.Finished) }
    }

    private inline fun setState(transform: (OnboardingUiState) -> OnboardingUiState) {
        state.update(transform)
        save(state.value)
    }

    private fun save(value: OnboardingUiState) {
        savedStateHandle[KEY_STEP] = value.step.name
        savedStateHandle[KEY_URL] = value.serverUrl
        savedStateHandle[KEY_SERVER_BASE] = value.server?.baseUrl
        savedStateHandle[KEY_SERVER_VERSION] = value.server?.version
        savedStateHandle[KEY_SERVER_COMPATIBILITY] = value.server?.compatibility?.name
        savedStateHandle[KEY_LOGIN_METHOD] = value.loginMethod.name
        savedStateHandle[KEY_USERNAME] = value.username
        savedStateHandle[KEY_CONNECTED_ADDRESS] = value.connected?.serverAddress
        savedStateHandle[KEY_CONNECTED_VERSION] = value.connected?.serverVersion
        savedStateHandle[KEY_CONNECTED_LABEL] = value.connected?.accountLabel
        savedStateHandle[KEY_CONNECTED_SAFE_MODE] = value.connected?.safeMode
    }

    internal companion object {
        const val KEY_STARTED = "onboarding.started"
        const val KEY_STEP = "onboarding.step"
        const val KEY_URL = "onboarding.url"
        const val KEY_SERVER_BASE = "onboarding.server.base"
        const val KEY_SERVER_VERSION = "onboarding.server.version"
        const val KEY_SERVER_COMPATIBILITY = "onboarding.server.compatibility"
        const val KEY_LOGIN_METHOD = "onboarding.login.method"
        const val KEY_USERNAME = "onboarding.login.username"
        const val KEY_CONNECTED_ADDRESS = "onboarding.connected.address"
        const val KEY_CONNECTED_VERSION = "onboarding.connected.version"
        const val KEY_CONNECTED_LABEL = "onboarding.connected.label"
        const val KEY_CONNECTED_SAFE_MODE = "onboarding.connected.safe"

        fun restore(handle: SavedStateHandle, config: OnboardingConfig): OnboardingUiState {
            val server = run {
                val base = handle.get<String>(KEY_SERVER_BASE) ?: return@run null
                val version = handle.get<String>(KEY_SERVER_VERSION) ?: return@run null
                val compatibility = enumOrNull<ServerCompatibility>(handle.get<String>(KEY_SERVER_COMPATIBILITY))
                    ?: return@run null
                ServerInfo(base, version, compatibility)
            }
            val connected = run {
                ConnectedSummary(
                    serverAddress = handle.get<String>(KEY_CONNECTED_ADDRESS) ?: return@run null,
                    serverVersion = handle.get<String>(KEY_CONNECTED_VERSION) ?: return@run null,
                    accountLabel = handle.get<String>(KEY_CONNECTED_LABEL) ?: return@run null,
                    safeMode = handle.get<Boolean>(KEY_CONNECTED_SAFE_MODE) ?: false,
                )
            }
            val saved = enumOrNull<OnboardingStep>(handle.get<String>(KEY_STEP)) ?: OnboardingStep.WELCOME
            val step = when {
                saved == OnboardingStep.LOGIN && server == null -> OnboardingStep.SERVER
                saved == OnboardingStep.CONNECTED && connected == null -> OnboardingStep.WELCOME
                else -> saved
            }
            return OnboardingUiState(
                step = step,
                serverUrl = handle.get<String>(KEY_URL) ?: config.defaultServerUrl,
                server = server,
                loginMethod = enumOrNull<LoginMethod>(handle.get<String>(KEY_LOGIN_METHOD)) ?: LoginMethod.PASSWORD,
                username = handle.get<String>(KEY_USERNAME).orEmpty(),
                connected = connected,
            )
        }

        private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
            name?.let { value -> enumValues<E>().firstOrNull { it.name == value } }

        /** Maps a login failure to what the login step says (plan 6.2: 400, 429, no space). */
        fun loginErrorFor(error: TandemError, method: LoginMethod): LoginError = when (error.dataProblem()) {
            DataProblem.NO_SPACE -> LoginError.NoSpace

            DataProblem.SECURE_STORAGE -> LoginError.StorageFailed

            else -> when (error) {
                is TandemError.BadRequest ->
                    if (method == LoginMethod.PASSWORD) LoginError.WrongCredentials else LoginError.InvalidToken

                is TandemError.Throttled -> LoginError.Throttled(error.retryAfter)

                is TandemError.Unauthorized ->
                    if (method == LoginMethod.TOKEN && error.source == UnauthorizedSource.TANDOOR) {
                        LoginError.InvalidToken
                    } else {
                        LoginError.Other(error)
                    }

                else -> LoginError.Other(error)
            }
        }
    }
}
