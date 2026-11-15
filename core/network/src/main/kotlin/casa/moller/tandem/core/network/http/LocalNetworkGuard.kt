package casa.moller.tandem.core.network.http

import casa.moller.tandem.core.model.error.TandemError
import java.io.IOException
import javax.net.ssl.SSLException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Fas 5 (sdk37 lane): Android 17's local network protection as Tandem sees it (docs/BYGGE.md
 * "Target SDK 37"; the rules are `core:common` `LocalNetworkRules`).
 *
 * An app that targets API 37 cannot reach the local network without `ACCESS_LOCAL_NETWORK`; the
 * system gives no error of its own (a TCP connection just times out). The guard tells whether
 * that is the case, so the intro can ask for the permission in context and a failed request
 * becomes [TandemError.LocalNetworkDenied] ("allow Nearby devices") instead of a timeout.
 */
interface LocalNetworkGuard {

    /** True when this device and build know the protection at all (Android 17+ and target 37+). */
    fun enforced(): Boolean

    /** True when Android blocks this app from the local network right now (enforced and not granted). */
    fun permissionMissing(): Boolean

    /**
     * True when traffic to [host] is local network traffic: an mDNS name, or a name or literal that
     * resolves to a local address. May ask DNS: never call it on the main thread.
     */
    fun isLocal(host: String): Boolean

    /** No protection: Android 16 and older, the Wear OS app (target 36) and tests. */
    object None : LocalNetworkGuard {
        override fun enforced(): Boolean = false

        override fun permissionMissing(): Boolean = false

        override fun isLocal(host: String): Boolean = false
    }
}

/**
 * Turns a failed connection into [TandemError.LocalNetworkDenied] when Android 17 is what kept it
 * from the server: the permission is missing and the host is local. It never blocks a request
 * itself (a false "local" must not stop a server that a VPN would reach), and it only looks at the
 * host after a failure, so a working connection costs nothing.
 *
 * Errors that already carry a meaning stay as they are: Tandem's own [TandemIOException]s, TLS
 * failures (the connection reached a server) and cancelled calls.
 */
class LocalNetworkInterceptor(private val guard: LocalNetworkGuard) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return try {
            chain.proceed(request)
        } catch (e: TandemIOException) {
            throw e
        } catch (e: SSLException) {
            throw e
        } catch (e: IOException) {
            if (!blockedByAndroid(chain, e)) throw e
            throw TandemIOException(TandemError.LocalNetworkDenied).apply { initCause(e) }
        }
    }

    /** The order matters: the DNS lookup in [LocalNetworkGuard.isLocal] runs last and only when needed. */
    private fun blockedByAndroid(chain: Interceptor.Chain, e: IOException): Boolean = when {
        chain.call().isCanceled() -> false
        e.reachedAServer() -> false
        !guard.permissionMissing() -> false
        else -> guard.isLocal(chain.request().url.host)
    }

    /** A TLS failure on any route (OkHttp attaches the other routes' failures as suppressed): a server answered. */
    private fun Throwable.reachedAServer(): Boolean {
        val seen = mutableListOf<Throwable>()
        val queue = ArrayDeque<Throwable>().apply { add(this@reachedAServer) }
        while (queue.isNotEmpty() && seen.size < MAX_RELATED) {
            val next = queue.removeFirst()
            if (seen.any { it === next }) continue
            if (next is SSLException) return true
            seen += next
            next.cause?.let { queue += it }
            queue += next.suppressed
        }
        return false
    }

    private companion object {
        const val MAX_RELATED = 16
    }
}
