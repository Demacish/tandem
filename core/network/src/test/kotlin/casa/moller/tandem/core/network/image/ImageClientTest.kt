package casa.moller.tandem.core.network.image

import casa.moller.tandem.core.network.http.RateLimitInterceptor
import casa.moller.tandem.core.network.http.TandemHttpClients
import casa.moller.tandem.core.network.http.TokenProvider
import casa.moller.tandem.core.network.http.await
import casa.moller.tandem.core.network.testing.HttpsServerRule
import casa.moller.tandem.core.network.testing.emptyResponse
import casa.moller.tandem.core.network.testing.testClient
import java.io.IOException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ImageClientTest {
    @get:Rule
    val tandoor = HttpsServerRule()

    @get:Rule
    val otherHost = HttpsServerRule()

    private val apiClient = testClient()
    private val imageBase = TandemHttpClients.images(apiClient)
    private val factory = TandoorImageClientFactory(imageBase)
    private val token = TokenProvider { "tda_device_token" }

    private fun png(): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "image/png")
        .addHeader("Content-Disposition", "attachment; filename=\"\"")
        .body("png")
        .build()

    private fun OkHttpClient.get(url: String) = runBlocking { newCall(Request.Builder().url(url).build()).await().use { it.code } }

    @Test
    fun sendsTheTokenOnlyToTheAccountsOrigin() {
        val client = factory.create(tandoor.url("/"), token)
        tandoor.enqueue(png())
        otherHost.enqueue(png())
        assertEquals(200, client.get(tandoor.url("/media/recipes/a_2.png")))
        assertEquals(200, client.get(otherHost.url("/media/recipes/a_2.png")))
        assertEquals("Bearer tda_device_token", tandoor.takeRequest().headers["Authorization"])
        assertNull("never to another origin", otherHost.takeRequest().headers["Authorization"])
    }

    @Test
    fun aSubPathAccountKeepsTheTokenInsideItsPath() {
        val client = factory.create(tandoor.url("/tandoor/"), token)
        tandoor.enqueue(png(), png())
        client.get(tandoor.url("/tandoor/media/recipes/a_2.png"))
        client.get(tandoor.url("/other-app/media/x.png"))
        assertEquals("Bearer tda_device_token", tandoor.takeRequest().headers["Authorization"])
        assertNull(tandoor.takeRequest().headers["Authorization"])
    }

    @Test
    fun aRedirectToAnotherHostDropsTheToken() {
        val client = factory.create(tandoor.url("/"), token)
        tandoor.enqueue(emptyResponse(302, "Location" to otherHost.url("/bucket/a_2.png")))
        otherHost.enqueue(png())
        assertEquals(200, client.get(tandoor.url("/media/recipes/a_2.png")))
        assertEquals("Bearer tda_device_token", tandoor.takeRequest().headers["Authorization"])
        val redirected = otherHost.takeRequest()
        assertEquals("/bucket/a_2.png", redirected.url.encodedPath)
        assertNull(redirected.headers["Authorization"])
    }

    @Test
    fun imagesHaveTheirOwnDispatcherAndRateLimit() {
        assertNotSame(apiClient.dispatcher, imageBase.dispatcher)
        assertEquals(TandemHttpClients.IMAGE_MAX_REQUESTS_PER_HOST, imageBase.dispatcher.maxRequestsPerHost)
        assertEquals(TandemHttpClients.MAX_REQUESTS_PER_HOST, apiClient.dispatcher.maxRequestsPerHost)
        val apiLimiters = apiClient.interceptors.filterIsInstance<RateLimitInterceptor>()
        val imageLimiters = imageBase.interceptors.filterIsInstance<RateLimitInterceptor>()
        assertEquals(1, apiLimiters.size)
        assertEquals(1, imageLimiters.size)
        assertNotSame(apiLimiters.single(), imageLimiters.single())
        // Same connection pool, User-Agent and HTTPS rule.
        assertSame(apiClient.connectionPool, imageBase.connectionPool)
        assertTrue(imageBase.followRedirects)
        assertFalse(imageBase.followSslRedirects)
        // Per-account clients share the image dispatcher.
        assertSame(imageBase.dispatcher, factory.create(tandoor.url("/"), token).dispatcher)
    }

    @Test
    fun refusesCleartextBaseUrls() {
        assertThrows(IllegalArgumentException::class.java) { factory.create("http://tandoor.example.com/", token) }
        assertThrows(IllegalArgumentException::class.java) { factory.create("not a url", token) }
    }

    @Test
    fun breakerPausesAfterRepeatedClientErrors() {
        var now = 0L
        val breaker = ImageErrorBreakerInterceptor(threshold = 3, windowNanos = 100, cooldownNanos = 1_000, nanoTime = { now })
        val client = TandoorImageClientFactory(TandemHttpClients.images(apiClient, breaker = breaker)).create(tandoor.url("/"), token)
        repeat(3) { tandoor.enqueue(emptyResponse(404)) }
        repeat(3) { assertEquals(404, client.get(tandoor.url("/media/recipes/gone_$it.png"))) }
        assertTrue(breaker.isOpen())
        assertThrows(IOException::class.java) { client.get(tandoor.url("/media/recipes/next.png")) }
        repeat(3) { tandoor.takeRequest() }

        now = 2_000
        assertFalse(breaker.isOpen())
        tandoor.enqueue(png())
        assertEquals(200, client.get(tandoor.url("/media/recipes/next.png")))
        assertEquals("/media/recipes/next.png", tandoor.takeRequest().url.encodedPath)
    }

    @Test
    fun breakerForgetsOldErrors() {
        var now = 0L
        val breaker = ImageErrorBreakerInterceptor(threshold = 2, windowNanos = 100, cooldownNanos = 1_000, nanoTime = { now })
        val client = TandoorImageClientFactory(TandemHttpClients.images(apiClient, breaker = breaker)).create(tandoor.url("/"), token)
        tandoor.enqueue(emptyResponse(404), emptyResponse(404))
        client.get(tandoor.url("/media/a.png"))
        now = 500
        client.get(tandoor.url("/media/b.png"))
        assertFalse("the first error is outside the window", breaker.isOpen())
    }
}
