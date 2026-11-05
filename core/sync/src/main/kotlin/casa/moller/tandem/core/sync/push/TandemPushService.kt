package casa.moller.tandem.core.sync.push

import casa.moller.tandem.core.data.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Fas 4 (push lane): the app's UnifiedPush service (declared in core:sync's manifest, not
 * exported; the connector binds to it). Hands everything to [FastPushReceiver] in the app scope
 * and returns at once: a wake-up only enqueues work, the endpoint's registration is one request.
 */
@AndroidEntryPoint
internal class TandemPushService : PushService() {
    @Inject
    lateinit var receiver: FastPushReceiver

    @Inject
    @field:ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val keys = endpoint.pubKeySet
        scope.launch { receiver.onNewEndpoint(instance, endpoint.url, keys?.pubKey, keys?.auth) }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val content = message.content
        scope.launch { receiver.onMessage(instance, content) }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        scope.launch { receiver.onRegistrationFailed(instance) }
    }

    override fun onUnregistered(instance: String) {
        scope.launch { receiver.onUnregistered(instance) }
    }
}
