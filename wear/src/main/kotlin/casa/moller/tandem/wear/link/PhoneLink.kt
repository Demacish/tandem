package casa.moller.tandem.wear.link

import android.content.Context
import casa.moller.tandem.core.watchlink.TimerAction
import casa.moller.tandem.core.watchlink.TimerMirror
import casa.moller.tandem.core.watchlink.WatchCodec
import casa.moller.tandem.core.watchlink.WatchLink
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** The watch's way to the phone app over the Data Layer (only for the timers; never the token). */
interface PhoneLink {
    /** Sends [action] to the phone; false when no phone with Tandem is reachable. */
    suspend fun sendTimerAction(action: TimerAction): Boolean

    /** The phone's timers now, or null when no phone with Tandem answers. */
    suspend fun requestTimers(): TimerMirror?
}

/** [PhoneLink] on Google Play services' Wearable API; without Play services it reaches nothing. */
class PlayPhoneLink(private val context: Context) : PhoneLink {
    // Made on first use, so a process that never talks to the phone never touches Play services.
    private val capabilities by lazy { Wearable.getCapabilityClient(context) }
    private val messages by lazy { Wearable.getMessageClient(context) }

    override suspend fun sendTimerAction(action: TimerAction): Boolean {
        val nodes = phones()
        if (nodes.isEmpty()) return false
        val bytes = WatchCodec.encode(TimerAction.serializer(), action)
        return nodes.any { node -> attempt { messages.sendMessage(node, WatchLink.PATH_TIMER_ACTION, bytes).await() } != null }
    }

    override suspend fun requestTimers(): TimerMirror? = phones().firstNotNullOfOrNull { node ->
        attempt { messages.sendRequest(node, WatchLink.PATH_TIMERS_REQUEST, ByteArray(0)).await() }
            ?.let { WatchCodec.decode(TimerMirror.serializer(), it) }
    }

    private suspend fun phones(): List<String> =
        attempt { capabilities.getCapability(WatchLink.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await() }
            ?.nodes
            ?.map { it.id }
            .orEmpty()
}

/**
 * Runs a Play services call; an [ApiException] (no Play services, nothing paired, the node went
 * away) or a refused call ([IllegalStateException]) is null.
 */
internal suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (e: ApiException) {
    null
} catch (e: IllegalStateException) {
    null
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
