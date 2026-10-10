package casa.moller.tandem.feature.shell

import casa.moller.tandem.core.data.server.ServerCompatibility
import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.data.settings.AppearanceRepository
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.account.TokenOrigin
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.settings.ThemeMode
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal val ACCOUNT = ServerAccount(
    id = "account-1",
    baseUrl = "https://tandoor.example.com",
    username = "anna",
    displayName = "Anna Andersson",
    userId = 7,
    spaceId = 1,
    spaceName = "Hemma",
    tokenId = 42,
    serverVersion = "2.6.13",
    addedAt = Instant.parse("2026-10-09T12:00:00Z"),
    tokenOrigin = TokenOrigin.CREATED,
)

internal val LOGGED_IN = Session.LoggedIn(ACCOUNT, "2.6.13", ServerCompatibility.SUPPORTED)

internal val CONNECTED_STATE = ShellUiState(
    mode = LOGGED_IN.toShellMode(),
    appVersion = AppVersion("0.0.1-dev", 1, "sideload"),
)

internal val DEMO_STATE = CONNECTED_STATE.copy(mode = ShellMode.Demo)

/** Logged in with a token the user pasted (it is never revoked by the app). */
internal val PASTED_STATE = CONNECTED_STATE.copy(
    mode = LOGGED_IN.copy(account = ACCOUNT.copy(tokenOrigin = TokenOrigin.PASTED)).toShellMode(),
)

internal class FakeSessionRepository(initial: Session = LOGGED_IN) : SessionRepository {
    val state = MutableStateFlow(initial)
    val logouts = mutableListOf<Boolean>()
    var refreshes = 0
    var refreshResult: Outcome<ServerInfo> =
        Outcome.Success(ServerInfo("https://tandoor.example.com/", "2.6.14", ServerCompatibility.SUPPORTED))
    var logoutResult: Outcome<TokenRevocation> = Outcome.Success(TokenRevocation.REVOKED)

    /** When set, refreshServerInfo waits for it (to observe the "refreshing" state). */
    var refreshGate: CompletableDeferred<Unit>? = null

    /** When set, logout waits for it (the token revocation is still running). */
    var logoutGate: CompletableDeferred<Unit>? = null

    override val session: Flow<Session> = state

    override suspend fun refreshServerInfo(): Outcome<ServerInfo> {
        refreshes++
        refreshGate?.await()
        val result = refreshResult
        val current = state.value
        if (result is Outcome.Success && current is Session.LoggedIn) {
            state.value = current.copy(serverVersion = result.value.version)
        }
        return result
    }

    override suspend fun logout(revokeToken: Boolean): Outcome<TokenRevocation> {
        logouts += revokeToken
        logoutGate?.await()
        val result = logoutResult
        if (result is Outcome.Success) state.value = Session.NoAccount
        return result
    }

    override suspend fun startDemo(): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun leaveDemo(): Outcome<Unit> = Outcome.Success(Unit)

    override fun acknowledgeRecovery() = Unit

    override suspend fun clearAppData(): Outcome<Unit> = Outcome.Success(Unit)
}

internal class FakeAppearanceRepository : AppearanceRepository {
    val mode = MutableStateFlow(ThemeMode.SYSTEM)
    var result: Outcome<Unit> = Outcome.Success(Unit)

    override val themeMode: Flow<ThemeMode> = mode

    override suspend fun setThemeMode(mode: ThemeMode): Outcome<Unit> {
        if (result is Outcome.Success) this.mode.value = mode
        return result
    }
}

internal class RecordingActions : ShellActions {
    val logouts = mutableListOf<Boolean>()
    val themes = mutableListOf<ThemeMode>()
    var refreshes = 0

    override fun refreshServerInfo() {
        refreshes++
    }

    override fun onAppResumed() = Unit

    override fun logout(revokeToken: Boolean) {
        logouts += revokeToken
    }

    override fun setThemeMode(mode: ThemeMode) {
        themes += mode
    }
}
