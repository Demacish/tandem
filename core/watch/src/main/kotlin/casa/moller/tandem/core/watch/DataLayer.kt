package casa.moller.tandem.core.watch

import android.content.Context
import casa.moller.tandem.core.watchlink.WatchLink
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** A watch the Data Layer knows. [nearby]: Bluetooth or the same Wi-Fi, not through the cloud. */
data class WatchNode(val id: String, val name: String, val nearby: Boolean)

/** The Data Layer could not be used (no Play services, no Wear OS app) or the call failed. */
class DataLayerException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The Wearable Data Layer as the phone uses it (an interface, so the pairing is tested with a fake
 * Data Layer). Only messages and requests with a reply: nothing is stored as a DataItem, which the
 * platform may sync through the cloud.
 */
interface DataLayer {
    /** Every connected Wear OS node. @throws DataLayerException when the Data Layer is unavailable. */
    suspend fun connectedNodes(): List<WatchNode>

    /** The reachable nodes that have Tandem (the watch app's capability). */
    suspend fun watchesWithTandem(): List<WatchNode>

    /** Sends a request to [nodeId] and waits for its reply. */
    suspend fun request(nodeId: String, path: String, data: ByteArray): ByteArray

    /** Sends a message to [nodeId] (no reply). */
    suspend fun send(nodeId: String, path: String, data: ByteArray)
}

/** [DataLayer] on Google Play services' Wearable API. */
@Singleton
internal class PlayDataLayer @Inject constructor(@ApplicationContext private val context: Context) : DataLayer {
    // Made on first use: a phone that never opens "Klockan" and never runs a timer never asks.
    private val nodes by lazy { Wearable.getNodeClient(context) }
    private val capabilities by lazy { Wearable.getCapabilityClient(context) }
    private val messages by lazy { Wearable.getMessageClient(context) }

    override suspend fun connectedNodes(): List<WatchNode> = call {
        nodes.connectedNodes.await().map { WatchNode(it.id, it.displayName, it.isNearby) }
    }

    override suspend fun watchesWithTandem(): List<WatchNode> = call {
        capabilities.getCapability(WatchLink.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await()
            .nodes
            .map { WatchNode(it.id, it.displayName, it.isNearby) }
    }

    override suspend fun request(nodeId: String, path: String, data: ByteArray): ByteArray = call {
        messages.sendRequest(nodeId, path, data).await()
    }

    override suspend fun send(nodeId: String, path: String, data: ByteArray) {
        call { messages.sendMessage(nodeId, path, data).await() }
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiException) {
        throw DataLayerException("Data Layer: status ${e.statusCode}", e)
    } catch (e: IllegalStateException) {
        throw DataLayerException("Data Layer refused the call", e)
    }
}

/** Waits for a Play services [Task] without blocking a thread. */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> continuation.resumeWithException(error)
            task.isCanceled -> continuation.cancel()
            else -> continuation.resume(task.result)
        }
    }
}
