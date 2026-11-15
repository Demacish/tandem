package casa.moller.tandem.core.network.http

import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.network.error.ErrorClassifier
import casa.moller.tandem.core.network.testing.TEST_USER_AGENT
import java.io.IOException
import java.net.ConnectException
import java.net.ServerSocket
import javax.net.ssl.SSLHandshakeException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Fas 5 (sdk37 lane): a failed connection becomes "allow the local network" only when Android 17
 * is what blocked it (docs/BYGGE.md "Target SDK 37").
 */
class LocalNetworkInterceptorTest {

    private class FakeGuard(var missing: Boolean, var local: Boolean) : LocalNetworkGuard {
        val askedHosts = mutableListOf<String>()

        override fun enforced(): Boolean = true

        override fun permissionMissing(): Boolean = missing

        override fun isLocal(host: String): Boolean {
            askedHosts += host
            return local
        }
    }

    /** A port nobody listens on: connecting is refused at once, like a blocked socket fails. */
    private val closedPort: Int = ServerSocket(0).use { it.localPort }

    private fun client(guard: LocalNetworkGuard, inner: Interceptor = Interceptor { it.proceed(it.request()) }): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(LocalNetworkInterceptor(guard))
            .addInterceptor(inner)
            .build()

    private fun call(client: OkHttpClient, url: String = "http://127.0.0.1:$closedPort/api/"): IOException = try {
        client.newCall(Request.Builder().url(url).build()).execute().close()
        fail("the call should fail")
        error("unreachable")
    } catch (e: IOException) {
        e
    }

    @Test
    fun aFailedConnectionToALocalServerWithoutThePermissionIsLocalNetworkDenied() {
        val guard = FakeGuard(missing = true, local = true)
        val failure = call(client(guard))

        assertEquals(TandemError.LocalNetworkDenied, ErrorClassifier().classify(failure))
        assertTrue("the cause is kept for the log", failure.cause is ConnectException)
        assertEquals(listOf("127.0.0.1"), guard.askedHosts)
    }

    @Test
    fun withThePermissionTheFailureStaysWhatItWas() {
        val guard = FakeGuard(missing = false, local = true)
        val failure = call(client(guard))

        assertEquals(TandemError.NetworkUnavailable, ErrorClassifier().classify(failure))
        assertTrue("no DNS lookup when nothing is blocked", guard.askedHosts.isEmpty())
    }

    @Test
    fun aServerOutsideTheLocalNetworkKeepsItsError() {
        val failure = call(client(FakeGuard(missing = true, local = false)))

        assertEquals(TandemError.NetworkUnavailable, ErrorClassifier().classify(failure))
    }

    @Test
    fun tandemsOwnErrorsAndTlsFailuresPassUnchanged() {
        val guard = FakeGuard(missing = true, local = true)
        val blocked = TandemIOException(TandemError.Blocked)
        assertSame(blocked, call(client(guard) { throw blocked }))

        val tls = SSLHandshakeException("untrusted")
        assertSame(tls, call(client(guard) { throw tls }))

        // A TLS failure on another route means a server answered: not the local network protection.
        val routes = ConnectException("refused").apply { addSuppressed(SSLHandshakeException("untrusted")) }
        assertSame(routes, call(client(guard) { throw routes }))
        assertTrue(guard.askedHosts.isEmpty())
    }

    @Test
    fun aWorkingConnectionNeverAsksTheGuard() {
        val guard = FakeGuard(missing = true, local = true)
        val ok = client(guard) { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody())
                .build()
        }
        ok.newCall(Request.Builder().url("https://192.168.1.2/api/").build()).execute().use { assertEquals(200, it.code) }
        assertTrue(guard.askedHosts.isEmpty())
    }

    @Test
    fun theSharedClientHasTheInterceptorOutermost() {
        val guard = FakeGuard(missing = true, local = true)
        val shared = TandemHttpClients.create(TEST_USER_AGENT, debugLogging = false, localNetwork = guard)
        assertTrue(shared.interceptors.first() is LocalNetworkInterceptor)
        // The Wear OS app and the tests: no protection by default.
        val plain = TandemHttpClients.create(TEST_USER_AGENT, debugLogging = false)
        val failure = call(plain, "https://127.0.0.1:$closedPort/api/")
        assertEquals(TandemError.NetworkUnavailable, ErrorClassifier().classify(failure))
    }
}
