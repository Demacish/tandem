package casa.moller.tandem.feature.shell

import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.model.account.TokenOrigin
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.settings.ThemeMode

/** What the shell shows; built by [ShellViewModel] from the session and the settings. */
data class ShellUiState(
    val mode: ShellMode = ShellMode.Unavailable,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** A server check is running (on start or pull-to-refresh). */
    val refreshing: Boolean = false,
    /** Why the last server check failed; cleared by the next successful one. */
    val refreshError: TandemError? = null,
    val loggingOut: Boolean = false,
    val appVersion: AppVersion = AppVersion("", 0, ""),
) {
    val isDemo: Boolean
        get() = mode is ShellMode.Demo

    val isSafeMode: Boolean
        get() = (mode as? ShellMode.Connected)?.account?.compatibility?.isSafeMode == true
}

sealed interface ShellMode {
    /** Logged in to a Tandoor server. */
    data class Connected(val account: AccountSummary) : ShellMode

    /** Demo mode: no server (plan 6.2). */
    data object Demo : ShellMode

    /**
     * No account (yet). Only seen for a moment while the app switches to the intro after a
     * logout, or before the session is read.
     */
    data object Unavailable : ShellMode
}

/** The account card under "Mer". */
data class AccountSummary(
    val displayName: String,
    val username: String,
    /** `https://example.com/tandoor`, exactly as stored. */
    val serverUrl: String,
    /** `example.com/tandoor`, for headings. */
    val serverAddress: String,
    val serverVersion: String?,
    val compatibility: ServerCompatibility,
    val spaceName: String?,
    /**
     * Where the stored token came from. Only a token Tandem created can be revoked at logout; a
     * pasted one belongs to the user (plan 4.2 step 4), so the logout dialog offers no revoke.
     */
    val tokenOrigin: TokenOrigin = TokenOrigin.CREATED,
)

/** Version of this build, for "Om Tandem". */
data class AppVersion(val name: String, val code: Long, val distribution: String)

internal fun Session?.toShellMode(): ShellMode = when (this) {
    is Session.LoggedIn -> ShellMode.Connected(
        AccountSummary(
            displayName = account.label,
            username = account.username,
            serverUrl = account.baseUrl,
            serverAddress = ServerInfo.displayAddress(account.baseUrl),
            serverVersion = serverVersion,
            compatibility = compatibility,
            spaceName = account.spaceName,
            tokenOrigin = account.tokenOrigin,
        ),
    )

    Session.Demo -> ShellMode.Demo

    Session.NoAccount, is Session.RecoveryNeeded, null -> ShellMode.Unavailable
}
