package casa.moller.tandem.core.sync.account

import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.server.ServerVersionChange
import casa.moller.tandem.core.network.capabilities.TandoorVersionTable

/**
 * What a logged-in account means for its stored mirror row in `server_account` (pure, unit
 * tested; applied by [AccountMirror]).
 *
 * @property clearStoredData the stored rows belong to another user, space or server under the
 *   same local id: they are removed before the account uses the database (plan 4.1: the outbox
 *   only continues for the SAME account and space).
 * @property versionChange the server reports another version than the one stored last time
 *   (plan B18), or null.
 */
internal data class MirrorDecision(val clearStoredData: Boolean, val versionChange: ServerVersionChange?) {
    companion object {
        fun of(stored: ServerAccountEntity?, account: ServerAccount): MirrorDecision {
            if (stored == null) return MirrorDecision(clearStoredData = false, versionChange = null)
            val sameIdentity = stored.userId == account.userId &&
                stored.spaceId == account.spaceId &&
                stored.baseUrl.trimEnd('/') == account.baseUrl.trimEnd('/')
            if (!sameIdentity) return MirrorDecision(clearStoredData = true, versionChange = null)
            val previous = stored.serverVersion?.takeIf { it.isNotBlank() }
            val current = account.serverVersion?.takeIf { it.isNotBlank() }
            val change = if (previous != null && current != null && previous != current) {
                ServerVersionChange(previous, current, TandoorVersionTable.assess(current).compatibility)
            } else {
                null
            }
            return MirrorDecision(clearStoredData = false, versionChange = change)
        }
    }
}
