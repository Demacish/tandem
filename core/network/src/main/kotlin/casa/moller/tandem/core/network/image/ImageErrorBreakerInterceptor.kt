package casa.moller.tandem.core.network.image

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Pauses image requests after repeated client errors (plan 4.1). A firewall such as CrowdSec
 * counts 400/403/404 answers per distinct path (`http-probing`: more than 10 in about 100 s); a
 * grid of recipes whose images were deleted on the server would be exactly that. After
 * [threshold] such answers within [windowNanos], every image request fails at once with an
 * [IOException] for [cooldownNanos] (the image shows its placeholder), then requests resume.
 *
 * Shared by every image client (it lives in the base client), so the limit is per app.
 */
class ImageErrorBreakerInterceptor(
    private val threshold: Int = DEFAULT_THRESHOLD,
    private val windowNanos: Long = DEFAULT_WINDOW_NANOS,
    private val cooldownNanos: Long = DEFAULT_COOLDOWN_NANOS,
    private val nanoTime: () -> Long = System::nanoTime,
) : Interceptor {
    init {
        require(threshold > 0) { "threshold must be > 0" }
    }

    private val errors = ArrayDeque<Long>()
    private var openUntil: Long? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        if (isOpen()) throw IOException("Image requests paused after repeated client errors")
        val response = chain.proceed(chain.request())
        if (response.code in COUNTED) record()
        return response
    }

    /** True while requests are paused. */
    @Synchronized
    fun isOpen(): Boolean {
        val until = openUntil ?: return false
        if (nanoTime() - until < 0) return true
        openUntil = null
        errors.clear()
        return false
    }

    @Synchronized
    private fun record() {
        val now = nanoTime()
        errors.addLast(now)
        while (errors.isNotEmpty() && now - errors.first() > windowNanos) errors.removeFirst()
        if (errors.size >= threshold) openUntil = now + cooldownNanos
    }

    companion object {
        /** Half of CrowdSec's `http-probing` limit, like the sync's circuit breaker (plan 4.1). */
        const val DEFAULT_THRESHOLD: Int = 5
        const val DEFAULT_WINDOW_NANOS: Long = 100_000_000_000L
        const val DEFAULT_COOLDOWN_NANOS: Long = 300_000_000_000L
        private val COUNTED = setOf(400, 403, 404)
    }
}
