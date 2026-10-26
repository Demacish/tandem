package casa.moller.tandem.feature.shell.accounts

import androidx.annotation.StringRes
import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.model.account.AccountSummary
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.account.TokenOrigin
import casa.moller.tandem.core.model.server.ServerCompatibility

// Phase 2 (konto lane): Mer → Konton och servrar (decision B14, plan 6.2 "Konto/server", Stitch
// konto_servrar with the corrections in docs/design/README.md: no "copy backup string", no
// "remove every account" button, the server's real name and version).

/**
 * What the accounts page shows ([AccountsScreen]).
 *
 * @property active the logged-in account; null in demo mode (demo mode is never an account).
 * @property others every other stored account, oldest first.
 * @property busy a switch or a removal is running: the buttons wait.
 * @property canSendNow a sync could send the active account's unsent changes now ("Send first").
 * @property removal the open "Remove account?" dialog, kept across rotation.
 */
data class AccountsUiState(
    val loading: Boolean = true,
    val isDemo: Boolean = false,
    val active: AccountItem? = null,
    val others: List<AccountItem> = emptyList(),
    val busy: Boolean = false,
    val canSendNow: Boolean = false,
    val removal: RemovalPrompt? = null,
)

/** One stored account as the page shows it. */
data class AccountItem(
    val id: String,
    val displayName: String,
    val username: String,
    /** `https://example.com/tandoor`, exactly as stored. */
    val serverUrl: String,
    /** `example.com/tandoor`, for headings and buttons. */
    val serverAddress: String,
    val spaceName: String?,
    val householdName: String?,
    val role: SpaceRole,
    val serverVersion: String?,
    val compatibility: ServerCompatibility,
    val tokenOrigin: TokenOrigin,
    val pendingChanges: Int,
    val isActive: Boolean,
) {
    val isGuest: Boolean
        get() = role == SpaceRole.GUEST
}

/**
 * "Remove account?" for [account] (the active one: "Log out?"). [warnedPending] is how many unsent
 * changes the dialog has shown; the removal only discards changes the user was warned about.
 */
data class RemovalPrompt(val account: AccountItem, val warnedPending: Int = account.pendingChanges)

/** What the user can do on the accounts page. */
sealed interface AccountsAction {
    data class SwitchTo(val accountId: String) : AccountsAction

    /** Opens "Remove account?" (for the active account: "Log out?"). */
    data class RequestRemoval(val accountId: String) : AccountsAction

    /** Confirms the open dialog; [revokeToken] deletes the device's own token on the server. */
    data class ConfirmRemoval(val revokeToken: Boolean) : AccountsAction

    data object DismissRemoval : AccountsAction

    /** "Export changes" in the dialog: the account's unsent changes to the share sheet. */
    data object ExportPending : AccountsAction

    /** "Sync now" on the active account (also "Send first" in the dialog). */
    data object SyncNow : AccountsAction
}

/** One-off results the page shows. */
sealed interface AccountsEffect {
    data class Message(@StringRes val text: Int) : AccountsEffect

    /** The exported unsent changes (JSON), for the system share sheet. */
    data class SharePending(val json: String) : AccountsEffect
}

internal fun AccountSummary.toItem(): AccountItem = AccountItem(
    id = account.id,
    displayName = account.label,
    username = account.username,
    serverUrl = account.baseUrl,
    serverAddress = ServerInfo.displayAddress(account.baseUrl),
    spaceName = account.spaceName,
    householdName = household?.name,
    role = role,
    serverVersion = account.serverVersion,
    compatibility = compatibility,
    tokenOrigin = account.tokenOrigin,
    pendingChanges = pendingChanges,
    isActive = isActive,
)
