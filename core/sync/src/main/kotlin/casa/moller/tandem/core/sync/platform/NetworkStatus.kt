package casa.moller.tandem.core.sync.platform

import kotlinx.coroutines.flow.StateFlow

/** Whether the device has a network that claims internet access (plan 3.5: offline first). */
internal interface NetworkStatus {
    /** True when a network with internet capability is the default network. */
    val online: StateFlow<Boolean>
}
