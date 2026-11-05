package casa.moller.tandem.smoke

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fas 4 (integration): a UnifiedPush distributor for the release smoke test. The test APK declares
 * [FakeDistributorReceiver], so the app's connector finds it like ntfy and talks to it with the
 * real broadcasts (unifiedpush.org/developers/spec/android: REGISTER with a token, NEW_ENDPOINT
 * back, MESSAGE with the bytes, MESSAGE_ACK from the app). Nothing in the app under test knows
 * it: the connector's receiver, `TandemPushService` and the Web Push decryption run as shipped,
 * through R8. No real distributor and no network are involved.
 */
object FakeDistributor {
    /** One registration: the app's [token] and the [endpoint] handed out for it. */
    data class Registration(val token: String, val application: String, val endpoint: String)

    private const val TAG = "FakeDistributor"
    private const val NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
    private const val MESSAGE = "org.unifiedpush.android.connector.MESSAGE"
    private const val EXTRA_TOKEN = "token"
    private const val EXTRA_APPLICATION = "application"
    private const val EXTRA_ENDPOINT = "endpoint"
    private const val EXTRA_ID = "id"
    private const val EXTRA_BYTES = "bytesMessage"

    private val registrations = ConcurrentHashMap<String, Registration>()

    /** The tokens the app unregistered, in order. */
    val unregistered: MutableList<String> = CopyOnWriteArrayList()

    /** The ids of the messages the app acknowledged. */
    val acknowledged: MutableList<String> = CopyOnWriteArrayList()

    /** Forgets everything: every test starts as a fresh install of the distributor. */
    fun reset() {
        registrations.clear()
        unregistered.clear()
        acknowledged.clear()
    }

    /** The app asked for an endpoint: the same one again for a known token, as a real distributor does. */
    fun onRegister(context: Context, intent: Intent) {
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        val application = intent.getStringExtra(EXTRA_APPLICATION) ?: return
        val registration = registrations.getOrPut(token) {
            Registration(token, application, "https://localhost/up/${UUID.randomUUID()}")
        }
        Log.i(TAG, "REGISTER from $application")
        context.sendBroadcast(
            Intent(NEW_ENDPOINT)
                .setPackage(application)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_ENDPOINT, registration.endpoint)
                .putExtra(EXTRA_ID, UUID.randomUUID().toString()),
        )
    }

    fun onUnregister(intent: Intent) {
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        registrations.remove(token)
        unregistered += token
        Log.i(TAG, "UNREGISTER")
    }

    fun onAcknowledged(intent: Intent) {
        intent.getStringExtra(EXTRA_ID)?.let { acknowledged += it }
    }

    /** Delivers [message] (the body the push service POSTed to [endpoint]) to the app; false for an unknown endpoint. */
    fun deliver(context: Context, endpoint: String, message: ByteArray): Boolean {
        val registration = registrations.values.firstOrNull { it.endpoint == endpoint } ?: return false
        context.sendBroadcast(
            Intent(MESSAGE)
                .setPackage(registration.application)
                .putExtra(EXTRA_TOKEN, registration.token)
                .putExtra(EXTRA_BYTES, message)
                .putExtra(EXTRA_ID, UUID.randomUUID().toString()),
        )
        Log.i(TAG, "MESSAGE of ${message.size} bytes")
        return true
    }
}

/** See [FakeDistributor]; declared in this module's manifest. */
class FakeDistributorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "org.unifiedpush.android.distributor.REGISTER" -> FakeDistributor.onRegister(context, intent)
            "org.unifiedpush.android.distributor.UNREGISTER" -> FakeDistributor.onUnregister(intent)
            "org.unifiedpush.android.distributor.MESSAGE_ACK" -> FakeDistributor.onAcknowledged(intent)
        }
    }
}
