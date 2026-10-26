package casa.moller.tandem.core.sync.platform

import kotlinx.coroutines.flow.StateFlow

/** Whether the device has a network that claims internet access (plan 3.5: offline first). */
internal interface NetworkStatus {
    /** True when a network with internet capability is the default network. */
    val online: StateFlow<Boolean>

    /**
     * Phase 2 (integration lane): how often the device came back online (offline -> online) since
     * the app started. A list read before a reconnect may be stale: someone else may have changed
     * the server while this device could not see it (ShoppingReader.stillOnServerLocked).
     */
    val reconnections: Int get() = 0
}
