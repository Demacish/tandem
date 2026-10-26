package casa.moller.tandem.core.data.account

import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.model.account.ServerAccount

/** What [AccountDirectory.remove] did (konto lane). */
sealed interface AccountRemoval {
    /**
     * The account is gone from the device, with its local data.
     *
     * @property revocation what happened to its device token (plan 4.2 step 4).
     * @property activeAccount the account that is active now (another one after removing the
     *   active account), null when none is left.
     */
    data class Removed(val revocation: TokenRevocation, val activeAccount: ServerAccount?) : AccountRemoval

    /**
     * Nothing was removed: [count] changes of the account wait in the outbox. The user must see
     * them first (export, send) and confirm; the next call passes `discardPendingChanges = true`.
     */
    data class PendingChanges(val count: Int) : AccountRemoval
}
