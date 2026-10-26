package casa.moller.tandem.feature.shell.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.account.AccountDirectory
import casa.moller.tandem.core.data.account.AccountRemoval
import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.data.sync.SyncRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.ShellMessage
import casa.moller.tandem.feature.shell.ShellMessageQueue
import casa.moller.tandem.feature.shell.ShellViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Mer → Konton och servrar (Phase 2, konto lane): the stored accounts from [AccountDirectory],
 * switching, removing (the active one: logging out) and "Sync now".
 *
 * A switch or a removal of the active account replaces the whole shell (the app keys it by the
 * account), so both run in the application scope and report through the app-wide
 * [ShellMessageQueue]. Unsent changes are never discarded silently: the removal only discards
 * what the dialog warned about; if more arrived meanwhile, the dialog shows the new count instead.
 */
@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accounts: AccountDirectory,
    sessions: SessionRepository,
    private val sync: SyncRepository,
    private val messages: ShellMessageQueue,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val local = MutableStateFlow(Local())
    private val effects = Channel<AccountsEffect>(Channel.BUFFERED)

    /** One-off messages and the export to share. */
    val effect: Flow<AccountsEffect> = effects.receiveAsFlow()

    val uiState: StateFlow<AccountsUiState> = combine(
        accounts.observeAccounts(),
        sessions.session,
        sync.status,
        local,
    ) { stored, session, status, ops ->
        val items = stored.map { it.toItem() }
        val active = items.firstOrNull { it.isActive }
        AccountsUiState(
            loading = false,
            isDemo = session == Session.Demo,
            active = active,
            others = items.filterNot { it.isActive },
            busy = ops.busy,
            canSendNow = active != null && !active.compatibility.isSafeMode && status.pause !in BLOCKING_PAUSES,
            // The dialog follows the account: a new unsent change shows up in it at once.
            removal = ops.removalId?.let { id ->
                items.firstOrNull { it.id == id }?.let { RemovalPrompt(it, warnedPending = ops.warnedPending) }
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AccountsUiState())

    fun onAction(action: AccountsAction) {
        when (action) {
            is AccountsAction.SwitchTo -> switchTo(action.accountId)
            is AccountsAction.RequestRemoval -> requestRemoval(action.accountId)
            is AccountsAction.ConfirmRemoval -> confirmRemoval(action.revokeToken)
            AccountsAction.DismissRemoval -> local.update { it.copy(removalId = null, warnedPending = 0) }
            AccountsAction.ExportPending -> exportPending()
            AccountsAction.SyncNow -> syncNow()
        }
    }

    private fun switchTo(accountId: String) {
        if (local.value.busy) return
        local.update { it.copy(busy = true) }
        applicationScope.launch {
            val result = accounts.switchTo(accountId)
            local.update { it.copy(busy = false) }
            messages.post(if (result is Outcome.Success) ShellMessage.SWITCHED_ACCOUNT else ShellMessage.SWITCH_FAILED)
        }
    }

    private fun requestRemoval(accountId: String) {
        val item = (listOfNotNull(uiState.value.active) + uiState.value.others).firstOrNull { it.id == accountId } ?: return
        local.update { it.copy(removalId = accountId, warnedPending = item.pendingChanges) }
    }

    private fun confirmRemoval(revokeToken: Boolean) {
        val prompt = uiState.value.removal ?: return
        if (local.value.busy) return
        val account = prompt.account
        // More changes than the dialog warned about: show them first, remove nothing.
        if (account.pendingChanges > prompt.warnedPending) {
            local.update { it.copy(warnedPending = account.pendingChanges) }
            return
        }
        local.update { it.copy(busy = true) }
        applicationScope.launch {
            val result = accounts.remove(account.id, revokeToken, discardPendingChanges = prompt.warnedPending > 0)
            when (result) {
                is Outcome.Failure -> {
                    local.update { it.copy(busy = false) }
                    messages.post(if (account.isActive) ShellMessage.LOGOUT_FAILED else ShellMessage.ACCOUNT_REMOVE_FAILED)
                }

                is Outcome.Success -> when (val removal = result.value) {
                    // Changes arrived that the dialog had not shown yet: show them, keep the account.
                    is AccountRemoval.PendingChanges -> local.update { it.copy(busy = false, warnedPending = removal.count) }

                    is AccountRemoval.Removed -> {
                        local.update { it.copy(busy = false, removalId = null, warnedPending = 0) }
                        messages.post(messageFor(removal.revocation, wasActive = account.isActive))
                    }
                }
            }
        }
    }

    private fun exportPending() {
        val accountId = local.value.removalId ?: uiState.value.active?.id ?: return
        viewModelScope.launch {
            when (val export = accounts.exportPendingChanges(accountId)) {
                is Outcome.Success -> effects.send(AccountsEffect.SharePending(export.value))
                is Outcome.Failure -> effects.send(AccountsEffect.Message(R.string.accounts_export_failed))
            }
        }
    }

    private fun syncNow() {
        if (uiState.value.active == null) return
        sync.requestSync(SyncReason.USER_REFRESH)
        viewModelScope.launch { effects.send(AccountsEffect.Message(R.string.accounts_sync_started)) }
    }

    private data class Local(val busy: Boolean = false, val removalId: String? = null, val warnedPending: Int = 0)

    internal companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Pauses in which "Send first" cannot send anything now. */
        private val BLOCKING_PAUSES = setOf(SyncPause.OFFLINE, SyncPause.UNAUTHORIZED, SyncPause.SAFE_MODE)

        /** The toast after a removal: the logout's own messages for the active account. */
        fun messageFor(revocation: TokenRevocation, wasActive: Boolean): ShellMessage = if (wasActive) {
            ShellViewModel.messageFor(Outcome.Success(revocation))
        } else {
            when (revocation) {
                TokenRevocation.FAILED -> ShellMessage.ACCOUNT_REMOVED_TOKEN_NOT_REVOKED
                TokenRevocation.UNKNOWN_TOKEN_ID, TokenRevocation.NOT_OWNED -> ShellMessage.ACCOUNT_REMOVED_PASTED_TOKEN
                TokenRevocation.REVOKED, TokenRevocation.ALREADY_INVALID, TokenRevocation.SKIPPED -> ShellMessage.ACCOUNT_REMOVED
            }
        }
    }
}
