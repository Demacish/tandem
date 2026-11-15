package casa.moller.tandem.core.testing.data

import casa.moller.tandem.core.data.sync.ServerReachability
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.isConnectivityProblem
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [ServerReachability] the test sets ([offline]) or feeds like the real one ([report]: a
 * connectivity failure sets it, any other result clears it). [reports] lists every reported result.
 */
class FakeServerReachability(offline: Boolean = false) : ServerReachability {
    override val offline = MutableStateFlow(offline)

    val reports: MutableList<TandemError?> = CopyOnWriteArrayList()

    override fun report(error: TandemError?) {
        reports += error
        offline.value = error?.isConnectivityProblem == true
    }
}
