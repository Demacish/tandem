package casa.moller.tandem.wear.link

import casa.moller.tandem.core.watchlink.WatchLink
import casa.moller.tandem.wear.TandemWearApplication
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.launch

/**
 * The phone's requests and messages (plan §7 #33): pair, unpair and status are answered by
 * [WatchPairing]; the timers go to `WearTimers`. The Data Layer only delivers from the phone app
 * with the same package and signing key; nothing here is logged.
 */
class WatchListenerService : WearableListenerService() {
    private val graph
        get() = (application as TandemWearApplication).graph

    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        if (path !in ANSWERED) return null
        val reply = TaskCompletionSource<ByteArray>()
        graph.scope.launch {
            runCatching { answer(path, request) }
                .onSuccess { reply.setResult(it) }
                .onFailure { reply.setException(it as? Exception ?: IllegalStateException(it)) }
        }
        return reply.task
    }

    private suspend fun answer(path: String, request: ByteArray): ByteArray = when (path) {
        WatchLink.PATH_PAIR -> graph.pairing.pair(request)
        WatchLink.PATH_UNPAIR -> graph.pairing.unpair(request)
        else -> graph.pairing.status()
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == WatchLink.PATH_TIMERS) {
            val data = event.data
            graph.scope.launch { graph.timers.onMirror(data) }
        }
    }

    private companion object {
        val ANSWERED: Set<String> = setOf(WatchLink.PATH_PAIR, WatchLink.PATH_UNPAIR, WatchLink.PATH_STATUS)
    }
}
