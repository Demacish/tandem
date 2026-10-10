package casa.moller.tandem.core.network.http

import casa.moller.tandem.core.model.error.InvalidUrlReason
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.network.error.ErrorClassifier
import casa.moller.tandem.core.network.testing.HttpsServerRule
import casa.moller.tandem.core.network.testing.TEST_USER_AGENT
import casa.moller.tandem.core.network.testing.jsonResponse
import casa.moller.tandem.core.network.testing.testClient
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher as ServerDispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

class TandemHttpClientsTest {
    @get:Rule
    val https = HttpsServerRule()

    @Test
    fun timeoutsLimitsAndRedirectPolicy() {
        val client = TandemHttpClients.create(TEST_USER_AGENT, debugLogging = false)
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
        assertEquals(30_000, client.writeTimeoutMillis)
        assertEquals(60_000, client.callTimeoutMillis)
        assertEquals(2, client.dispatcher.maxRequestsPerHost)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)

        val long = TandemHttpClients.longRunning(client)
        assertEquals(120_000, long.readTimeoutMillis)
        assertEquals(120_000, long.writeTimeoutMillis)
        assertEquals(10_000, long.connectTimeoutMillis)
        // Shared dispatcher and pool: the per-host limit covers imports too.
        assertSame(client.dispatcher, long.dispatcher)
        assertSame(client.connectionPool, long.connectionPool)
        assertNotSame(client, long)
    }

    @Test
    fun sendsUserAgent() = runBlocking {
        https.enqueue(jsonResponse(200, "{}"))
        testClient().newCall(Request.Builder().url(https.url("/api/x/")).build()).await().close()
        assertEquals("Tandem/1.2.3-test (Android 36)", https.takeRequest().headers["User-Agent"])
    }

    @Test
    fun refusesCleartext() = runBlocking {
        try {
            testClient().newCall(Request.Builder().url("http://localhost:1/api/").build()).await()
            fail("http:// must be refused")
        } catch (e: TandemIOException) {
            assertEquals(TandemError.InvalidUrl(InvalidUrlReason.CLEARTEXT_NOT_ALLOWED), e.error)
        }
        assertEquals(0, https.server.requestCount)
    }

    @Test
    fun doesNotFollowRedirects() = runBlocking {
        https.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/accounts/login/?next=/api/nope/")
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .build(),
        )
        val response = testClient().newCall(Request.Builder().url(https.url("/api/nope/")).build()).await()
        response.use { assertEquals(302, it.code) }
        assertEquals(1, https.server.requestCount)
    }

    @Test
    fun atMostTwoConcurrentRequestsPerHost() = runBlocking {
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        https.server.dispatcher = object : ServerDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val now = inFlight.incrementAndGet()
                maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                Thread.sleep(150)
                inFlight.decrementAndGet()
                return jsonResponse(200, "{}")
            }
        }
        val client = testClient()
        (1..6).map { i ->
            async { client.newCall(Request.Builder().url(https.url("/api/item/$i/")).build()).await().close() }
        }.awaitAll()
        assertEquals(6, https.server.requestCount)
        assertEquals(2, maxInFlight.get())
    }

    @Test
    fun debugLoggingRedactsAuthorizationAndNeverLogsBodies() = runBlocking {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val client = testClient(debugLogging = true, logger = { lines += it })
            .newBuilder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer tda_secret_token").build())
            }
            .build()
        https.enqueue(jsonResponse(200, """{"token":"tda_response_secret"}"""))
        client.newCall(Request.Builder().url(https.url("/api/access-token/")).build()).await().use { it.body.string() }

        val log = lines.joinToString("\n")
        assertTrue(log, log.contains("Authorization: ██"))
        assertFalse(log, log.contains("tda_secret_token"))
        assertFalse(log, log.contains("tda_response_secret"))
        assertEquals("Bearer tda_secret_token", https.takeRequest().headers["Authorization"])
    }

    @Test
    fun releaseBuildsDoNotLog() = runBlocking {
        val lines = mutableListOf<String>()
        https.enqueue(jsonResponse(200, "{}"))
        testClient(debugLogging = false, logger = { lines += it })
            .newCall(Request.Builder().url(https.url("/")).build()).await().close()
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun timeoutIsClassified() = runBlocking {
        https.enqueue(jsonResponse(200, "{}").newBuilder().headersDelay(2, TimeUnit.SECONDS).build())
        val client = testClient().newBuilder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        try {
            client.newCall(Request.Builder().url(https.url("/slow/")).build()).await().close()
            fail("expected a timeout")
        } catch (e: java.io.IOException) {
            assertEquals(TandemError.Timeout, ErrorClassifier.Default.classify(e))
        }
    }
}
