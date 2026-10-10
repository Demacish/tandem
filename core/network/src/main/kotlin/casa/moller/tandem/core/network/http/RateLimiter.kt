package casa.moller.tandem.core.network.http

import java.io.IOException
import java.io.InterruptedIOException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Token bucket (implemented as GCRA, all in integer nanoseconds): up to [burst] requests at once,
 * then [permitsPerSecond] on average (plan 4.1: about 5 requests per second, to stay far below
 * CrowdSec's scenarios). A caller that has to wait reserves its slot first, so waiting callers
 * are served in order.
 *
 * A cancelled call must not hold up the others: [acquire] checks its cancellation before
 * reserving and while waiting (in slices of at most [WAIT_SLICE_NANOS]), and hands its slot back
 * when it gives up, if no later caller has reserved after it.
 */
class TokenBucket(
    permitsPerSecond: Int = DEFAULT_PERMITS_PER_SECOND,
    burst: Int = DEFAULT_PERMITS_PER_SECOND,
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleeper: (Long) -> Unit = ::sleepNanos,
) {
    init {
        require(permitsPerSecond > 0) { "permitsPerSecond must be > 0" }
        require(burst > 0) { "burst must be > 0" }
    }

    private val intervalNanos: Long = NANOS_PER_SECOND / permitsPerSecond
    private val toleranceNanos: Long = intervalNanos * (burst - 1)
    private var theoreticalArrival: Long = nanoTime()

    /**
     * Blocks until the caller may send a request.
     *
     * @param isCanceled checked before reserving and between wait slices.
     * @throws IOException ("Canceled") if the call was cancelled; no slot is used up then.
     */
    fun acquire(isCanceled: () -> Boolean = { false }) {
        if (isCanceled()) throw canceled()
        val (wait, reservedUntil) = reserveSlot()
        var remaining = wait
        while (remaining > 0) {
            if (isCanceled()) {
                release(reservedUntil)
                throw canceled()
            }
            val slice = minOf(remaining, WAIT_SLICE_NANOS)
            sleeper(slice)
            remaining -= slice
        }
        if (wait > 0 && isCanceled()) {
            release(reservedUntil)
            throw canceled()
        }
    }

    /** Reserves the next slot and returns how many nanoseconds the caller has to wait for it. */
    fun reserve(): Long = reserveSlot().first

    /** The wait for the reserved slot and the theoretical arrival time right after reserving it. */
    @Synchronized
    private fun reserveSlot(): Pair<Long, Long> {
        val now = nanoTime()
        val tat = maxOf(theoreticalArrival, now)
        val wait = maxOf(0L, tat - toleranceNanos - now)
        theoreticalArrival = tat + intervalNanos
        return wait to theoreticalArrival
    }

    /** Gives a slot back, unless a later caller already reserved after it (then it is left). */
    @Synchronized
    private fun release(reservedUntil: Long) {
        if (theoreticalArrival == reservedUntil) theoreticalArrival -= intervalNanos
    }

    companion object {
        const val DEFAULT_PERMITS_PER_SECOND: Int = 5

        /** Longest single sleep, so a cancellation is noticed within this time. */
        const val WAIT_SLICE_NANOS: Long = 50_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val NANOS_PER_MILLI = 1_000_000L

        private fun sleepNanos(nanos: Long) {
            try {
                Thread.sleep(nanos / NANOS_PER_MILLI, (nanos % NANOS_PER_MILLI).toInt())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Interrupted while rate limited").apply { initCause(e) }
            }
        }

        private fun canceled() = IOException("Canceled")
    }
}

/**
 * Applies a shared [TokenBucket] to every request of the client (and of clients derived from it).
 * OkHttp runs application interceptors even for a call that was cancelled while it was queued,
 * so the interceptor passes the call's cancellation state to the bucket.
 */
class RateLimitInterceptor(private val bucket: TokenBucket) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        bucket.acquire { call.isCanceled() }
        return chain.proceed(chain.request())
    }
}
