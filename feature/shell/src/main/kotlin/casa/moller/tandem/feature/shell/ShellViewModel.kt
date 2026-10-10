package casa.moller.tandem.feature.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.common.AppBuildInfo
import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.data.settings.AppearanceRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the shell's screens can ask for. Implemented by [ShellViewModel]; tests use a fake. */
interface ShellActions {
    /** Checks the server's version again ("Mer", pull-to-refresh or "Try again"). */
    fun refreshServerInfo()

    /**
     * The app came (back) to the foreground: checks the server version again if the last check
     * is at least [ShellViewModel.RESUME_CHECK_INTERVAL_MILLIS] old (plan 3.6 point 3).
     */
    fun onAppResumed()

    /** Logs out; [revokeToken] also deletes this device's token on the server (plan 4.2 step 4). */
    fun logout(revokeToken: Boolean)

    fun setThemeMode(mode: ThemeMode)
}

/**
 * State of the logged-in (or demo) app shell: the account card, theme and logout under "Mer".
 *
 * The server version is checked once when the shell opens for an account (that is, on app start
 * and after a login), when the app returns to the foreground if the last check is at least 15
 * minutes old, and on pull-to-refresh (plan 3.6 point 3).
 */
@HiltViewModel
class ShellViewModel internal constructor(
    private val sessionRepository: SessionRepository,
    private val appearanceRepository: AppearanceRepository,
    private val messages: ShellMessageQueue,
    buildInfo: AppBuildInfo,
    private val applicationScope: CoroutineScope,
    /** Monotonic time in milliseconds, for the resume throttle. */
    private val elapsedMillis: () -> Long,
) : ViewModel(),
    ShellActions {

    @Inject
    constructor(
        sessionRepository: SessionRepository,
        appearanceRepository: AppearanceRepository,
        messages: ShellMessageQueue,
        buildInfo: AppBuildInfo,
        @ApplicationScope applicationScope: CoroutineScope,
    ) : this(
        sessionRepository = sessionRepository,
        appearanceRepository = appearanceRepository,
        messages = messages,
        buildInfo = buildInfo,
        applicationScope = applicationScope,
        elapsedMillis = { System.nanoTime() / NANOS_PER_MILLI },
    )

    private val session: StateFlow<Session?> =
        sessionRepository.session.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val operations = MutableStateFlow(Operations())
    private var checkedAccountId: String? = null

    /** When the last server check finished (successfully or not); null before the first one. */
    private var lastCheckFinishedAt: Long? = null

    private val appVersion = AppVersion(buildInfo.versionName, buildInfo.versionCode, buildInfo.distribution)

    val uiState: StateFlow<ShellUiState> = combine(
        session,
        appearanceRepository.themeMode,
        operations,
    ) { current, theme, ops ->
        ShellUiState(
            mode = current.toShellMode(),
            themeMode = theme,
            refreshing = ops.refreshing,
            refreshError = ops.refreshError,
            loggingOut = ops.loggingOut,
            appVersion = appVersion,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ShellUiState(appVersion = appVersion))

    init {
        viewModelScope.launch {
            session.collect { current ->
                // Once per account and shell: app start, or the first time after a login.
                if (current is Session.LoggedIn && current.account.id != checkedAccountId) {
                    checkedAccountId = current.account.id
                    refreshServerInfo()
                }
            }
        }
    }

    override fun refreshServerInfo() {
        if (operations.value.refreshing || session.value !is Session.LoggedIn) return
        operations.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val result = sessionRepository.refreshServerInfo()
            // Also after a failure, so a broken server is not probed on every resume (plan 4.1).
            lastCheckFinishedAt = elapsedMillis()
            operations.update { it.copy(refreshing = false, refreshError = (result as? Outcome.Failure)?.error) }
        }
    }

    override fun onAppResumed() {
        if (session.value !is Session.LoggedIn) return
        val last = lastCheckFinishedAt
        if (last != null && elapsedMillis() - last < RESUME_CHECK_INTERVAL_MILLIS) return
        refreshServerInfo()
    }

    override fun logout(revokeToken: Boolean) {
        if (operations.value.loggingOut) return
        operations.update { it.copy(loggingOut = true) }
        // Application scope: the account is removed before the token is revoked, so the shell
        // (and this ViewModel) goes away while the request still runs. The result must still
        // reach the user.
        applicationScope.launch {
            val result = sessionRepository.logout(revokeToken)
            // Posted to the app-wide queue: after a successful logout the shell is about to go away.
            messages.post(messageFor(result))
            operations.update { it.copy(loggingOut = false, refreshError = null) }
        }
    }

    override fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            if (appearanceRepository.setThemeMode(mode) is Outcome.Failure) messages.post(ShellMessage.SETTINGS_NOT_SAVED)
        }
    }

    private data class Operations(val refreshing: Boolean = false, val refreshError: TandemError? = null, val loggingOut: Boolean = false)

    internal companion object {
        /** Plan 3.6 point 3: at most one automatic version check per 15 minutes on resume. */
        const val RESUME_CHECK_INTERVAL_MILLIS: Long = 15 * 60 * 1000L
        private const val NANOS_PER_MILLI = 1_000_000L

        fun messageFor(result: Outcome<TokenRevocation>): ShellMessage = when (result) {
            is Outcome.Failure -> ShellMessage.LOGOUT_FAILED

            is Outcome.Success -> when (result.value) {
                TokenRevocation.FAILED -> ShellMessage.LOGGED_OUT_TOKEN_NOT_REVOKED

                TokenRevocation.UNKNOWN_TOKEN_ID, TokenRevocation.NOT_OWNED -> ShellMessage.LOGGED_OUT_PASTED_TOKEN

                TokenRevocation.REVOKED, TokenRevocation.ALREADY_INVALID, TokenRevocation.SKIPPED ->
                    ShellMessage.LOGGED_OUT
            }
        }
    }
}
