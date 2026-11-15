package casa.moller.tandem.core.data.sync

import casa.moller.tandem.core.model.error.TandemError
import kotlinx.coroutines.flow.StateFlow

/**
 * The one answer to "is the app offline?" for every offline notice: the shell's "Offline" chip,
 * Idag's "Offline: showing what is stored on the device", the recipe screen and the search
 * (0.8.0 device test B19: these kept their own copy of a failed check and stayed for minutes after
 * the network came back).
 *
 * - [offline] is true while the device has no network, or while the active server's last answer
 *   was a connectivity failure (no route, unknown host, timeout). Never in demo mode.
 * - Every request core:sync sends (`RemoteCaller`) and every request reported through
 *   [RequestGuard] updates it: any answer from the server clears it, a connectivity failure sets
 *   it. Requests sent elsewhere (the shell's server check) hand their result to [report].
 * - When the network comes back while the server's last answer was a failure, one server check
 *   is sent after a short pause (flapping networks are debounced, the guard of plan §5.4 is
 *   asked first), so the notices follow reality without the user doing anything.
 *
 * - **Threading:** main-safe and non-blocking.
 */
interface ServerReachability {
    /** True while the app is offline (see the class description); starts with the current state. */
    val offline: StateFlow<Boolean>

    /**
     * Records the result of one request to the active server that did not pass core:sync: null
     * for a success. Errors that are not connectivity problems also count as "the server answered".
     */
    fun report(error: TandemError?)
}
