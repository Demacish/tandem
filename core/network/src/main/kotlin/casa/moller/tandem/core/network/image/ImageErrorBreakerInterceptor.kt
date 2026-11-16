package casa.moller.tandem.core.network.image

import casa.moller.tandem.core.network.http.originKey
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Pauses image requests after repeated client errors (plan 4.1). A firewall such as CrowdSec
 * counts 400/403/404 answers per distinct path (`http-probing`: more than 10 in about 100 s); a
 * grid of recipes whose images were deleted on the server would be exactly that. After
 * [threshold] such answers within [windowNanos] from one origin, every image request to that
 * origin fails at once with an [IOException] for [cooldownNanos] (the image shows its
 * placeholder), then requests resume.
 *
 * Shared by every image client (it lives in the base client), counted per origin (scheme, host,
 * port; fix-09): a firewall guards its own server, so third-party hosts (the recipe inbox's blog
 * and CDN images) never pause the Tandoor server's images, and the other way round.
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

    /** One origin's recent errors and pause. */
    private class OriginState {
        val errors = ArrayDeque<Long>()
        var openUntil: Long? = null
    }

    private val origins = HashMap<String, OriginState>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val origin = originKey(chain.request().url)
        if (isOpen(origin)) throw IOException("Image requests paused after repeated client errors")
        val response = chain.proceed(chain.request())
        if (response.code in COUNTED) record(origin)
        return response
    }

    /** True while requests to [url]'s origin are paused. */
    fun isOpen(url: HttpUrl): Boolean = isOpen(originKey(url))

    /** True while requests to any origin are paused. */
    @Synchronized
    fun isOpen(): Boolean = origins.keys.toList().any { isOpen(it) }

    @Synchronized
    private fun isOpen(origin: String): Boolean {
        val state = origins[origin] ?: return false
        val until = state.openUntil ?: return false
        if (nanoTime() - until < 0) return true
        origins.remove(origin)
        return false
    }

    @Synchronized
    private fun record(origin: String) {
        val now = nanoTime()
        forgetQuietOrigins(now)
        val state = origins.getOrPut(origin) { OriginState() }
        state.errors.addLast(now)
        while (state.errors.isNotEmpty() && now - state.errors.first() > windowNanos) state.errors.removeFirst()
        if (state.errors.size >= threshold) state.openUntil = now + cooldownNanos
    }

    /** Drops origins that are not paused and whose errors are all outside the window, so the map stays small. */
    private fun forgetQuietOrigins(now: Long) {
        origins.entries.removeAll { (_, state) ->
            val until = state.openUntil
            val paused = until != null && now - until < 0
            !paused && state.errors.all { now - it > windowNanos }
        }
    }

    companion object {
        /** Half of CrowdSec's `http-probing` limit, like the sync's circuit breaker (plan 4.1). */
        const val DEFAULT_THRESHOLD: Int = 5
        const val DEFAULT_WINDOW_NANOS: Long = 100_000_000_000L
        const val DEFAULT_COOLDOWN_NANOS: Long = 300_000_000_000L
        private val COUNTED = setOf(400, 403, 404)
    }
}
