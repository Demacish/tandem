package casa.moller.tandem.core.network.openfoodfacts

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.network.http.AuthInterceptor
import casa.moller.tandem.core.network.testing.HttpsServerRule
import casa.moller.tandem.core.network.testing.TestTls
import casa.moller.tandem.core.network.testing.jsonResponse
import kotlinx.coroutines.runBlocking
import okhttp3.CookieJar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Fas S (hemma-data lane): the Open Food Facts client (plan §8.8) against an HTTPS MockWebServer
 * with two recorded public answers (`src/test/resources/openfoodfacts/`). Never the real service.
 */
class OpenFoodFactsClientTest {
    @get:Rule
    val server = HttpsServerRule()

    private val userAgent = OpenFoodFactsClient.userAgent("0.4.0-test")

    private val httpClient = OpenFoodFactsClient.httpClient(userAgent) {
        sslSocketFactory(TestTls.clientTrust.sslSocketFactory(), TestTls.clientTrust.trustManager)
    }

    private val client by lazy { OpenFoodFactsClient(httpClient, server.url("/")) }

    private fun recorded(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("openfoodfacts/$name.json")) { "missing $name" }.readText()

    @Test
    fun aKnownCodeIsFoundAndOnlyTheCodeGoesOut() = runBlocking {
        server.enqueue(jsonResponse(200, recorded("product-found")))

        val answer = client.product("737628064502")

        val found = (answer as Outcome.Success).value as OpenFoodFactsResult.Found
        assertEquals("0737628064502", found.code)
        assertEquals("155 g", found.product.quantity)
        assertTrue(found.product.productName!!.startsWith("Thai peanut noodle kit"))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v2/product/737628064502", request.url.encodedPath)
        // The fixed field list is the only parameter: nothing about the user or the device.
        assertEquals(setOf("fields"), request.url.queryParameterNames)
        assertEquals(OpenFoodFactsClient.FIELDS, request.url.queryParameter("fields"))
        assertEquals("Tandem/0.4.0-test (+https://github.com/Demacish/tandem)", request.headers["User-Agent"])
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["Cookie"])
    }

    @Test
    fun anUnknownCodeIsNotFound() = runBlocking {
        server.enqueue(jsonResponse(404, recorded("product-not-found")))
        assertEquals(Outcome.Success(OpenFoodFactsResult.NotFound), client.product("2999999999992"))

        // A 200 with status 0 (older answers) is "not found" too.
        server.enqueue(jsonResponse(200, """{"code":"2999999999992","status":0,"status_verbose":"product not found"}"""))
        assertEquals(Outcome.Success(OpenFoodFactsResult.NotFound), client.product("2999999999992"))
    }

    @Test
    fun throttlingAndServerErrorsAreTypedFailures() = runBlocking {
        server.enqueue(jsonResponse(429, "{}", "Retry-After" to "30"))
        val throttled = (client.product("2999999999992") as Outcome.Failure).error
        assertTrue("$throttled", throttled is TandemError.Throttled)

        server.enqueue(jsonResponse(503, "{}"))
        assertEquals(Outcome.Failure(TandemError.ServerError(503)), client.product("2999999999992"))

        server.enqueue(jsonResponse(200, "not json"))
        assertTrue(client.product("2999999999992") is Outcome.Failure)
    }

    @Test
    fun anythingButDigitsIsRefusedWithoutARequest() = runBlocking {
        assertTrue(client.product("12345abc") is Outcome.Failure)
        assertTrue(client.product("") is Outcome.Failure)
        assertEquals(0, server.server.requestCount)
    }

    @Test
    fun theClientSharesNothingWithTandoorsClients() {
        val client = OpenFoodFactsClient.httpClient(userAgent)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertNull(client.cache)
        assertTrue(client.interceptors.none { it is AuthInterceptor })
        assertTrue(client.networkInterceptors.isEmpty())
        assertEquals(1, client.dispatcher.maxRequestsPerHost)
        assertEquals(false, client.followSslRedirects)
    }
}
