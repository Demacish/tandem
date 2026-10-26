package casa.moller.tandem.feature.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.common.AppBuildInfo
import casa.moller.tandem.core.data.account.AccountDirectory
import casa.moller.tandem.core.data.demo.DemoDataRepository
import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.server.ServerStatusRepository
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.data.settings.AppearanceRepository
import casa.moller.tandem.core.data.settings.SettingsRepository
import casa.moller.tandem.core.data.sync.SyncRepository
import casa.moller.tandem.core.data.timer.TimerController
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.server.ServerFeature
import casa.moller.tandem.core.model.server.ServerVersionChange
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerCapabilities
import casa.moller.tandem.core.model.timer.TimerSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
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

    /** "Reset demo data" in demo mode: the sample recipes as they were shipped. */
    fun resetDemoData()

    /** Closes the "Tandoor was updated" banner ([ShellUiState.serverUpdatedTo]). */
    fun dismissServerUpdate()

    /** "Try again" on the banner of a held-back sync ([ShellUiState.syncHeldBack]): a sync the user asked for. */
    fun retrySync()

    /** "Stop" on the timer bar: the ringing timer [id] stops ringing. */
    fun stopTimer(id: Long)

    /**
     * Phase 2 (konto lane): "Export changes" in the logout dialog: the shown account's unsent
     * changes as JSON for the share sheet ([ShellViewModel.pendingExports]), before they are deleted.
     */
    fun exportPendingChanges()
}

/**
 * State of the logged-in (or demo) app shell: the account card, theme and logout under "Mer".
 *
 * The server version is checked once when the shell opens for an account (that is, on app start
 * and after a login), when the app returns to the foreground if the last check is at least 15
 * minutes old, and on pull-to-refresh (plan 3.6 point 3).
 *
 * A server update that got no system notification (plan B18: notifications are asked for only at
 * the first timer) is shown once as a banner ([ShellUiState.serverUpdatedTo]); it is marked as
 * shown in the store as soon as it appears, so it does not come back after process death.
 */
@HiltViewModel
class ShellViewModel internal constructor(
    private val sessionRepository: SessionRepository,
    appearanceRepository: AppearanceRepository,
    private val messages: ShellMessageQueue,
    buildInfo: AppBuildInfo,
    private val applicationScope: CoroutineScope,
    private val syncRepository: SyncRepository,
    private val demoData: DemoDataRepository,
    private val serverStatus: ServerStatusRepository,
    settingsRepository: SettingsRepository,
    private val timerController: TimerController,
    // Phase 2 (konto lane): household, role and stored accounts for the account card.
    private val accounts: AccountDirectory,
    permissions: SpacePermissionsRepository,
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
        syncRepository: SyncRepository,
        demoData: DemoDataRepository,
        serverStatus: ServerStatusRepository,
        settingsRepository: SettingsRepository,
        timerController: TimerController,
        accounts: AccountDirectory,
        permissions: SpacePermissionsRepository,
    ) : this(
        sessionRepository = sessionRepository,
        appearanceRepository = appearanceRepository,
        messages = messages,
        buildInfo = buildInfo,
        applicationScope = applicationScope,
        syncRepository = syncRepository,
        demoData = demoData,
        serverStatus = serverStatus,
        settingsRepository = settingsRepository,
        timerController = timerController,
        accounts = accounts,
        permissions = permissions,
        elapsedMillis = { System.nanoTime() / NANOS_PER_MILLI },
    )

    private val session: StateFlow<Session?> =
        sessionRepository.session.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val operations = MutableStateFlow(Operations())
    private var checkedAccountId: String? = null

    /** When the last server check finished (successfully or not); null before the first one. */
    private var lastCheckFinishedAt: Long? = null

    private val appVersion = AppVersion(buildInfo.versionName, buildInfo.versionCode, buildInfo.distribution)

    // Phase 1 (sync lane): the "Offline" chip also follows the sync's own network pause; the
    // integration lane adds the "Log in again" banner for a token Tandoor no longer accepts.
    private val syncPause = syncRepository.status.map { it.pause }.distinctUntilChanged()

    /** The server update shown in the banner until the user closes it (already marked as shown). */
    private val serverUpdate = MutableStateFlow<ServerVersionChange?>(null)

    // Phase 1b (inställningar lane): the settings rows' summaries and the timer bar on every tab.
    // fix-ui (0.2.0 review): the server's features for the account card and the outbox count
    // for the logout dialog.
    private val phase1b = combine(
        settingsRepository.settings,
        timerController.capabilities,
        timerController.timers,
        serverStatus.observeStatus().map { if (it.isDemo) emptySet() else it.capabilities.features }.distinctUntilChanged(),
        syncRepository.status.map { it.pendingChanges }.distinctUntilChanged(),
    ) { settings, capabilities, timers, features, pending ->
        Phase1b(settings.toSummary(capabilities), timers, features, pending)
    }

    // Phase 2 (konto lane): the household and the role on the account card, the stored accounts' count.
    private val accountCard = combine(
        accounts.observeHousehold(),
        permissions.permissions.map { it.role }.distinctUntilChanged(),
        accounts.observeAccounts().map { it.size }.distinctUntilChanged(),
    ) { household, role, count -> AccountCard(household?.name, role, count) }

    private val exports = Channel<String>(Channel.BUFFERED)

    /** Phase 2 (konto lane): the exported unsent changes, for the share sheet (one-off). */
    val pendingExports: Flow<String> = exports.receiveAsFlow()

    val uiState: StateFlow<ShellUiState> = combine(
        combine(session, appearanceRepository.themeMode) { current, theme -> current to theme },
        operations,
        syncPause,
        combine(serverUpdate, accountCard) { update, card -> update to card },
        phase1b,
    ) { (current, theme), ops, pause, (update, card), extras ->
        val mode = current.toShellMode()
        ShellUiState(
            mode = mode,
            themeMode = theme,
            refreshing = ops.refreshing,
            refreshError = ops.refreshError,
            loggingOut = ops.loggingOut,
            appVersion = appVersion,
            syncOffline = pause == SyncPause.OFFLINE,
            // Only for the account that is shown: never in demo mode or while switching.
            loginExpired = pause == SyncPause.UNAUTHORIZED && mode is ShellMode.Connected,
            resettingDemo = ops.resettingDemo,
            // Only for the account that is shown, and never next to the safe mode banner.
            serverUpdatedTo = update?.newVersion?.takeIf { mode is ShellMode.Connected && current.isTested },
            settings = extras.settings,
            // Only for the account that is shown: never in demo mode or while switching.
            syncHeldBack = pause?.takeIf { it in HELD_BACK && mode is ShellMode.Connected },
            timers = extras.timers,
            timerSource = current.timerSource,
            pendingChanges = extras.pendingChanges.takeIf { mode is ShellMode.Connected } ?: 0,
            serverFeatures = extras.serverFeatures.takeIf { mode is ShellMode.Connected }.orEmpty(),
            household = card.household.takeIf { mode is ShellMode.Connected },
            role = card.role.takeIf { mode is ShellMode.Connected } ?: SpaceRole.UNKNOWN,
            storedAccounts = card.storedAccounts,
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
        viewModelScope.launch {
            combine(session, serverStatus.observeVersionNotice()) { current, change ->
                // Never in demo mode, and never for an untested version (the safe mode banner).
                change?.takeIf { current.isTested && it.compatibility == ServerCompatibility.SUPPORTED }
            }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { change ->
                    serverUpdate.value = change
                    serverStatus.markVersionNoticeShown(change)
                }
        }
    }

    override fun dismissServerUpdate() {
        serverUpdate.value = null
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

    override fun retrySync() {
        if (session.value !is Session.LoggedIn) return
        syncRepository.requestSync(SyncReason.USER_REFRESH)
    }

    override fun exportPendingChanges() {
        val account = (session.value as? Session.LoggedIn)?.account ?: return
        viewModelScope.launch {
            when (val export = accounts.exportPendingChanges(account.id)) {
                is Outcome.Success -> exports.send(export.value)
                is Outcome.Failure -> messages.post(ShellMessage.EXPORT_FAILED)
            }
        }
    }

    override fun stopTimer(id: Long) {
        viewModelScope.launch { timerController.stopRinging(id) }
    }

    override fun resetDemoData() {
        if (operations.value.resettingDemo || session.value != Session.Demo) return
        operations.update { it.copy(resettingDemo = true) }
        viewModelScope.launch {
            val result = demoData.resetDemoData()
            operations.update { it.copy(resettingDemo = false) }
            messages.post(if (result is Outcome.Success) ShellMessage.DEMO_DATA_RESET else ShellMessage.DEMO_DATA_RESET_FAILED)
        }
    }

    private data class Operations(
        val refreshing: Boolean = false,
        val refreshError: TandemError? = null,
        val loggingOut: Boolean = false,
        val resettingDemo: Boolean = false,
    )

    private data class AccountCard(val household: String?, val role: SpaceRole, val storedAccounts: Int)

    private data class Phase1b(
        val settings: SettingsSummary,
        val timers: List<CookTimer>,
        val serverFeatures: Set<ServerFeature>,
        val pendingChanges: Int,
    )

    /** The source the shown recipes belong to (core:model `TimerSource`). */
    private val Session?.timerSource: String?
        get() = when (this) {
            is Session.LoggedIn -> TimerSource.of(demoMode = false, accountId = account.id)
            Session.Demo -> TimerSource.of(demoMode = true, accountId = null)
            else -> null
        }

    /** Logged in to a server that runs a tested version. */
    private val Session?.isTested: Boolean
        get() = this is Session.LoggedIn && compatibility == ServerCompatibility.SUPPORTED

    internal companion object {
        /** Plan 3.6 point 3: at most one automatic version check per 15 minutes on resume. */
        const val RESUME_CHECK_INTERVAL_MILLIS: Long = 15 * 60 * 1000L
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Pauses the shell shows as "held back" with "Try again" (plan 4.1). */
        private val HELD_BACK = setOf(SyncPause.BLOCKED, SyncPause.CIRCUIT_BREAKER)

        private fun AppSettings.toSummary(capabilities: TimerCapabilities) = SettingsSummary(
            dynamicColor = dynamicColor,
            timerMode = timerMode,
            syncInterval = syncInterval,
            downloadAllRecipes = downloadAllRecipes,
            timersNeedAttention = capabilities.alarmMayBeSilent || capabilities.alarmMayBeLate,
        )

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
