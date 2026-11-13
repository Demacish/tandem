package casa.moller.tandem.core.watch

import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.watchlink.TimerMirror
import casa.moller.tandem.core.watchlink.WatchCodec
import casa.moller.tandem.core.watchlink.WatchLink
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the watch asks the phone (Fas 5, wear lane): its timers when the watch app opens, and
 * "Stoppa"/"Avbryt" on a timer. Only Tandem on the watch can reach it (same package and key).
 */
@AndroidEntryPoint
class PhoneWearListenerService : WearableListenerService() {
    @Inject
    internal lateinit var mirror: WatchTimerMirror

    @Inject
    @ApplicationScope
    internal lateinit var scope: CoroutineScope

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == WatchLink.PATH_TIMER_ACTION) {
            val data = event.data
            scope.launch { mirror.act(data) }
        }
    }

    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? =
        if (path == WatchLink.PATH_TIMERS_REQUEST) Tasks.forResult(WatchCodec.encode(TimerMirror.serializer(), mirror.mirror())) else null
}
