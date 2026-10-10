package casa.moller.tandem.core.network.api

import casa.moller.tandem.core.model.error.NotTandoorReason
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.core.network.error.apiCall
import casa.moller.tandem.core.network.http.TokenProvider
import casa.moller.tandem.core.network.http.await
import casa.moller.tandem.core.network.testing.Fixture
import casa.moller.tandem.core.network.testing.HttpsServerRule
import casa.moller.tandem.core.network.testing.htmlResponse
import casa.moller.tandem.core.network.testing.jsonResponse
import casa.moller.tandem.core.network.testing.testClient
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

class TandoorApiFactoryTest {
    @get:Rule
    val tandoor = HttpsServerRule()

    @get:Rule
    val otherHost = HttpsServerRule()

    private val factory = TandoorApiFactory(testClient())
    private val token = TokenProvider { "tda_device_token" }

    @Test
    fun sendsBearerTokenToTheServer() = runBlocking {
        tandoor.enqueue(Fixture.load("13-space-current").toMockResponse())
        val api = factory.create(tandoor.url("/"), token)
        val space = api.space.currentSpace()
        assertEquals(1L, space.id)
        val request = tandoor.takeRequest()
        assertEquals("/api/space/current/", request.url.encodedPath)
        assertEquals("Bearer tda_device_token", request.headers["Authorization"])
    }

    @Test
    fun subPathInstallPrefixesEveryPath() = runBlocking {
        tandoor.enqueue(Fixture.load("11-user-by-id").toMockResponse(), Fixture.load("34-revoke-device-token").toMockResponse())
        val api = factory.create(tandoor.url("/tandoor"), token)
        assertEquals(tandoor.url("/tandoor/"), api.baseUrl)
        api.users.user(1)
        api.auth.deleteAccessToken(2)
        assertEquals("/tandoor/api/user/1/", tandoor.takeRequest().url.encodedPath)
        val delete = tandoor.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/tandoor/api/access-token/2/", delete.url.encodedPath)
        assertEquals("Bearer tda_device_token", delete.headers["Authorization"])
    }

    @Test
    fun neverSendsTheTokenToAnotherHost() = runBlocking {
        val api = factory.create(tandoor.url("/"), token)
        otherHost.enqueue(jsonResponse(200, "{}"), jsonResponse(200, "{}"))
        // Another port is another origin, even on the same host name.
        api.httpClient.newCall(Request.Builder().url(otherHost.url("/api/space/current/")).build()).await().close()
        assertNull(otherHost.takeRequest().headers["Authorization"])
        // An Authorization header set by someone else is stripped for other origins as well.
        api.httpClient.newCall(
            Request.Builder().url(otherHost.url("/media/x.jpg")).header("Authorization", "Bearer leaked").build(),
        ).await().close()
        assertNull(otherHost.takeRequest().headers["Authorization"])
    }

    @Test
    fun neverSendsTheTokenToAnotherHostNameOnTheSamePort() = runBlocking {
        val api = factory.create(tandoor.url("/"), token)
        tandoor.enqueue(jsonResponse(200, "{}"))
        // Same server and port, but addressed as 127.0.0.1 instead of localhost.
        val sameServerOtherName = tandoor.server.url("/api/space/current/").newBuilder().host("127.0.0.1").build()
        api.httpClient.newCall(Request.Builder().url(sameServerOtherName).build()).await().close()
        assertNull(tandoor.takeRequest().headers["Authorization"])
    }

    @Test
    fun neverSendsTheTokenOutsideTheBasePath() = runBlocking {
        val api = factory.create(tandoor.url("/tandoor/"), token)
        tandoor.enqueue(jsonResponse(200, "{}"), jsonResponse(200, "{}"))
        api.httpClient.newCall(Request.Builder().url(tandoor.url("/other-app/api/")).build()).await().close()
        assertNull(tandoor.takeRequest().headers["Authorization"])
        api.httpClient.newCall(Request.Builder().url(tandoor.url("/tandoorX/api/")).build()).await().close()
        assertNull(tandoor.takeRequest().headers["Authorization"])
    }

    @Test
    fun noTokenNoHeader() = runBlocking {
        tandoor.enqueue(Fixture.load("01-server-settings-no-auth").toMockResponse())
        val api = factory.create(tandoor.url("/"), TokenProvider.NONE)
        assertEquals("2.6.13", api.server.serverSettings().version)
        assertNull(tandoor.takeRequest().headers["Authorization"])
    }

    @Test
    fun rejectsNonHttpsBaseUrls() {
        assertThrows(IllegalArgumentException::class.java) { factory.create("http://recept.example.com/", token) }
        assertThrows(IllegalArgumentException::class.java) { factory.create("recept.example.com", token) }
    }

    @Test
    fun unknownApiPathIsNotFollowed() = runBlocking {
        tandoor.enqueue(Fixture.load("29-not-found-unknown-path").toMockResponse())
        val api = factory.create(tandoor.url("/"), token)
        val result = apiCall { api.space.currentSpace() }
        assertEquals(Outcome.Failure(TandemError.NotTandoor(NotTandoorReason.LOGIN_REDIRECT, 302)), result)
        assertEquals(1, tandoor.server.requestCount)
    }

    @Test
    fun htmlSuccessIsNotTandoor() = runBlocking {
        tandoor.enqueue(htmlResponse(200))
        val api = factory.create(tandoor.url("/"), token)
        val result = apiCall { api.space.currentSpace() }
        assertEquals(Outcome.Failure(TandemError.NotTandoor(NotTandoorReason.HTML_RESPONSE, 200)), result)
    }

    @Test
    fun brokenJsonIsDecodeError() = runBlocking {
        tandoor.enqueue(jsonResponse(200, """{"id": "not-a-number""""))
        val api = factory.create(tandoor.url("/"), token)
        val result = apiCall { api.space.currentSpace() }
        assertEquals(TandemError.Decode::class, (result as Outcome.Failure).error::class)
    }

    @Test
    fun baseUrlIsKeptAsGiven() {
        val api = factory.create(tandoor.url("/tandoor/"), token)
        assertEquals(tandoor.url("/tandoor/").toHttpUrl().toString(), api.baseUrl)
    }

    @Test
    fun tokenThatCannotBeAHeaderFailsTheCallInsteadOfCrashing() = runBlocking {
        // OkHttp throws IllegalArgumentException for such a header value; from an enqueued
        // (suspend) call that would be rethrown on the dispatcher thread and kill the app.
        for (bad in listOf("tda_\u00F6", "tda_a\nb", "\u201Ctda_a\u201D")) {
            val api = factory.create(tandoor.url("/"), TokenProvider { bad })
            val result = apiCall { api.space.currentSpace() }
            assertEquals(bad, Outcome.Failure(TandemError.Unauthorized(UnauthorizedSource.TANDOOR)), result)
        }
        assertEquals(0, tandoor.server.requestCount)
    }
}
