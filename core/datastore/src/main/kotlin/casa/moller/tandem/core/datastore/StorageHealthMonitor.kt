package casa.moller.tandem.core.datastore

import casa.moller.tandem.core.model.settings.RecoveryReason
import casa.moller.tandem.core.model.settings.StorageHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Collects storage problems that were handled by resetting data instead of crashing. The app
 * observes [health] and shows the recovery screen while [StorageHealth.recoveryNeeded] is true.
 *
 * The state lives in memory only: after a reset the files are valid again, so the next app
 * start is healthy (with no accounts, which sends the user to login).
 */
class StorageHealthMonitor {
    private val state = MutableStateFlow(StorageHealth.Healthy)

    val health: StateFlow<StorageHealth> = state.asStateFlow()

    /** Records that storage was reset for [reason]. */
    fun report(reason: RecoveryReason) {
        state.update { it.copy(recoveryReasons = it.recoveryReasons + reason) }
    }

    /**
     * Withdraws [reason] because the problem went away on its own (e.g. the accounts could be
     * read again after a transient Keystore failure). Other reasons stay.
     */
    fun resolve(reason: RecoveryReason) {
        state.update { it.copy(recoveryReasons = it.recoveryReasons - reason) }
    }

    /** Called when the user has dealt with the recovery screen. */
    fun acknowledge() {
        state.value = StorageHealth.Healthy
    }
}
