package casa.moller.tandem.feature.shell

import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.model.account.TokenOrigin
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.server.ServerFeature
import casa.moller.tandem.core.model.settings.SyncInterval
import casa.moller.tandem.core.model.settings.ThemeMode
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerMode

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
    /** The sync pauses because the device has no network (`SyncStatus.pause == OFFLINE`). */
    val syncOffline: Boolean = false,
    /**
     * Tandoor refused the account's token (`SyncStatus.pause == UNAUTHORIZED`): the sync stopped
     * and the shell shows "Log in again" (plan 4.1, 5.4). Never true in demo mode.
     */
    val loginExpired: Boolean = false,
    /** "Reset demo data" is running. */
    val resettingDemo: Boolean = false,
    /**
     * The server was updated to this (tested) version and no system notification told the user
     * (plan B18): the shell shows "Tandoor was updated to 2.6.15" once, until it is closed.
     */
    val serverUpdatedTo: String? = null,
    // --- Phase 1b (inställningar lane) ---
    /** What the settings rows under "More" summarise. */
    val settings: SettingsSummary = SettingsSummary(),
    /**
     * The sync of the shown account is held back ([SyncPause.BLOCKED] or
     * [SyncPause.CIRCUIT_BREAKER], plan 4.1): the shell shows a banner with "Try again". Null
     * otherwise, and always in demo mode.
     */
    val syncHeldBack: SyncPause? = null,
    /** Every timer the app follows (running, paused, ringing, finished), for the timer bar on every tab. */
    val timers: List<CookTimer> = emptyList(),
    // --- fix-ui (0.2.0 review) ---
    /**
     * Where the shown recipes come from (core:model `TimerSource`: demo mode or the account's id),
     * null without one. A timer of another source opens the timer list, never cook mode here.
     */
    val timerSource: String? = null,
    /**
     * Changes of the shown account that wait in the outbox (cook log, "recently viewed"),
     * `SyncStatus.pendingChanges`; a logout deletes them, so the logout dialog warns (plan 4.2 p4).
     * Always 0 in demo mode.
     */
    val pendingChanges: Int = 0,
    /**
     * The optional features Tandem found on the server (plan 3.6 capability map, §6.2
     * "Konto/server": kapabiliteter); empty in demo mode and before the first check.
     */
    val serverFeatures: Set<ServerFeature> = emptySet(),
) {
    val isDemo: Boolean
        get() = mode is ShellMode.Demo

    val isSafeMode: Boolean
        get() = (mode as? ShellMode.Connected)?.account?.compatibility?.isSafeMode == true
}

/**
 * The settings under "More" in a few words (Phase 1b): each row's supporting text.
 *
 * @property timersNeedAttention the system may let a timer ring late or silently
 *   (`TimerCapabilities.alarmMayBeLate` / `alarmMayBeSilent`); the permissions row says so.
 */
data class SettingsSummary(
    val dynamicColor: Boolean = false,
    val timerMode: TimerMode = TimerMode.IN_APP,
    val syncInterval: SyncInterval = SyncInterval.DEFAULT,
    val downloadAllRecipes: Boolean = false,
    val timersNeedAttention: Boolean = false,
)

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
