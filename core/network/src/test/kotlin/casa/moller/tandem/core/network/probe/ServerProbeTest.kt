package casa.moller.tandem.core.network.probe

import casa.moller.tandem.core.model.error.InvalidUrlReason
import casa.moller.tandem.core.model.error.NotTandoorReason
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.TlsErrorKind
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.core.network.error.ErrorClassifier
import casa.moller.tandem.core.network.http.TandemHttpClients
import casa.moller.tandem.core.network.http.await
import casa.moller.tandem.core.network.testing.Fixture
import casa.moller.tandem.core.network.testing.HttpsServerRule
import casa.moller.tandem.core.network.testing.TEST_USER_AGENT
import casa.moller.tandem.core.network.testing.TestTls
import casa.moller.tandem.core.network.testing.emptyResponse
import casa.moller.tandem.core.network.testing.htmlResponse
import casa.moller.tandem.core.network.testing.jsonResponse
import casa.moller.tandem.core.network.testing.testClient
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

class ServerProbeTest {
    @get:Rule
    val https = HttpsServerRule()

    @get:Rule
    val wrongHost = HttpsServerRule(TestTls.wrongHost)

    private val probe = ServerProbe(testClient())

    private fun settings(version: String) =
        Fixture.load("01-server-settings-no-auth").toMockResponse(mapOf("version" to JsonPrimitive(version)))

    @Test
    fun recognisesTandoor() = runBlocking {
        https.enqueue(Fixture.load("01-server-settings-no-auth").toMockResponse())
        val result = probe.probe(https.url("/"))
        val server = (result as Outcome.Success).value
        assertEquals(https.url("/"), server.baseUrl)
        assertEquals("2.6.13", server.version)
        assertEquals(Compatibility.SUPPORTED, server.compatibility)
        assertEquals(5, server.settings.shoppingMinAutosyncIntervalOrNull())

        val request = https.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/server-settings/current/", request.url.encodedPath)
        assertNull("the probe never sends a token", request.headers["Authorization"])
        assertEquals(TEST_USER_AGENT, request.headers["User-Agent"])
        assertEquals(1, https.server.requestCount)
    }

    @Test
    fun pastedDeepLinkInASubPathInstall() = runBlocking {
        // The deep link itself is tried first; Tandoor serves its app page (or a login redirect)
        // for that path, so the probe falls back to the cut base.
        https.enqueue(htmlResponse(200), Fixture.load("01-server-settings-no-auth").toMockResponse())
        val server = (probe.probe(https.url("/tandoor/recipe/12/?share=abc")) as Outcome.Success).value
        assertEquals(https.url("/tandoor/"), server.baseUrl)
        assertEquals("/tandoor/recipe/12/api/server-settings/current/", https.takeRequest().url.encodedPath)
        assertEquals("/tandoor/api/server-settings/current/", https.takeRequest().url.encodedPath)
        assertEquals(2, https.server.requestCount)
    }

    @Test
    fun pastedDeepLinkFallsBackAfterALoginRedirect() = runBlocking {
        https.enqueue(
            Fixture.load("29-not-found-unknown-path").toMockResponse(),
            Fixture.load("01-server-settings-no-auth").toMockResponse(),
        )
        val server = (probe.probe(https.url("/advanced-search?query=pasta")) as Outcome.Success).value
        assertEquals(https.url("/"), server.baseUrl)
        assertEquals(2, https.server.requestCount)
    }

    @Test
    fun subPathFromTandoorsOwnDocsIsFound() = runBlocking {
        // SCRIPT_NAME=/recipes, the example in Tandoor's configuration docs.
        https.enqueue(Fixture.load("01-server-settings-no-auth").toMockResponse())
        val server = (probe.probe(https.url("/recipes")) as Outcome.Success).value
        assertEquals(https.url("/recipes/"), server.baseUrl)
        assertEquals("/recipes/api/server-settings/current/", https.takeRequest().url.encodedPath)
        assertEquals(1, https.server.requestCount)
    }

    @Test
    fun subPathNamedLikeARouteIsFoundWithOneRequest() = runBlocking {
        https.enqueue(Fixture.load("01-server-settings-no-auth").toMockResponse())
        val server = (probe.probe(https.url("/books/")) as Outcome.Success).value
        assertEquals(https.url("/books/"), server.baseUrl)
        assertEquals(1, https.server.requestCount)
    }

    @Test
    fun onlyNotTandoorAnswersFallBackToTheCutBase() = runBlocking {
        // A proxy refusing the typed path is final: the cut base is not tried.
        https.enqueue(emptyResponse(401), Fixture.load("01-server-settings-no-auth").toMockResponse())
        assertEquals(
            Outcome.Failure(TandemError.Unauthorized(UnauthorizedSource.PROXY)),
            probe.probe(https.url("/tandoor/recipe/12/")),
        )
        assertEquals(1, https.server.requestCount)
    }

    @Test
    fun bothCandidatesNotTandoorReportsTheCutBase() = runBlocking {
        https.enqueue(htmlResponse(200), htmlResponse(404))
        assertEquals(
            Outcome.Failure(TandemError.NotTandoor(NotTandoorReason.UNEXPECTED_STATUS, 404)),
            probe.probe(https.url("/tandoor/recipe/12/")),
        )
        assertEquals(2, https.server.requestCount)
    }

    @Test
    fun requestCapCoversCandidatesAndRedirects() = runBlocking {
        // The typed path uses all 3 requests (two redirects, then not Tandoor), so the cut base
        // is never tried: it would be a 4th request.
        https.enqueue(
            emptyResponse(307, "Location" to "/a/recipe/1/api/server-settings/current/"),
            emptyResponse(307, "Location" to "/b/recipe/1/api/server-settings/current/"),
            htmlResponse(200),
            Fixture.load("01-server-settings-no-auth").toMockResponse(),
        )
        val result = probe.probe(https.url("/tandoor/recipe/12/"))
        assertEquals(Outcome.Failure(TandemError.NotTandoor(NotTandoorReason.HTML_RESPONSE, 200)), result)
        assertEquals(ServerProbe.MAX_REQUESTS, https.server.requestCount)
    }

    @Test
    fun followsHttpsRedirectToAnotherBasePath() = runBlocking {
        https.enqueue(
            emptyResponse(301, "Location" to "/tandoor/api/server-settings/current/"),
            Fixture.load("01-server-settings-no-auth").toMockResponse(),
        )
        val server = (probe.probe(https.url("/")) as Outcome.Success).value
        assertEquals(https.url("/tandoor/"), server.baseUrl)
        assertEquals("/api/server-settings/current/", https.takeRequest().url.encodedPath)
        assertEquals("/tandoor/api/server-settings/current/", https.takeRequest().url.encodedPath)
    }

    @Test
    fun refusesRedirectToCleartext() = runBlocking {
        https.enqueue(emptyResponse(301, "Location" to "http://localhost/api/server-settings/current/"))
        assertEquals(
            Outcome.Failure(TandemError.InvalidUrl(InvalidUrlReason.CLEARTEXT_NOT_ALLOWED)),
            probe.probe(https.url("/")),
        )
        assertEquals(1, https.server.requestCount)
    }

    @Test
    fun redirectToLoginMeansNotTheApi() = runBlocking {
        https.enqueue(Fixture.load("29-not-found-unknown-path").toMockResponse())
        assertEquals(
            Outcome.Failure(TandemError.NotTandoor(NotTandoorReason.LOGIN_REDIRECT, 302)),
            probe.probe(https.url("/wrong/")),
        )
    }

    @Test
    fun redirectToSsoPortalIsNotFollowed() = runBlocking {
        https.enqueue(emptyResponse(302, "Location" to "https://sso.example.com/login?rd=x"))
        assertEquals(
            Outcome.Failure(TandemError.NotTandoor(NotTandoorReason.REDIRECT, 302)),
            probe.probe(https.url("/")),
        )
        assertEquals(1, https.server.requestCount)
    }

    @Test
    fun atMostThreeRequests() = runBlocking {
        repeat(5) { i ->
            https.enqueue(emptyResponse(307, "Location" to "/hop$i/api/server-settings/current/"))
        }
        assertEquals(
            Outcome.Failure(TandemError.NotTandoor(NotTandoorReason.TOO_MANY_REDIRECTS)),
            probe.probe(https.url("/")),
        )
        assertEquals(ServerProbe.MAX_REQUESTS, https.server.requestCount)
    }

    @Test
    fun notTandoorResponses() = runBlocking {
        https.enqueue(
            htmlResponse(200),
            htmlResponse(404),
            jsonResponse(200, """{"name":"some other api"}"""),
            jsonResponse(200, """[1,2,3]"""),
            jsonResponse(404, """{"detail":"Not found."}"""),
        )
        val base = https.url("/")
        assertEquals(Outcome.Failure(TandemError.NotTandoor(NotTandoorReason.HTML_RESPONSE, 200)), probe.probe(base))
        assertEquals(Outcome.Failure(TandemError.NotTandoor(NotTandoorReason.UNEXPECTED_STATUS, 404)), probe.probe(base))
        assertEquals(Outcome.Failure(TandemError.NotTandoor(NotTandoorReason.MISSING_VERSION, 200)), probe.probe(base))
        assertEquals(Outcome.Failure(TandemError.NotTandoor(NotTandoorReason.NOT_JSON, 200)), probe.probe(base))
        assertEquals(Outcome.Failure(TandemError.NotTandoor(NotTandoorReason.UNEXPECTED_STATUS)), probe.probe(base))
    }

    @Test
    fun blockedProxyAndServerErrorsKeepTheirMeaning() = runBlocking {
        https.enqueue(emptyResponse(403), emptyResponse(401), emptyResponse(502), jsonResponse(429, "{}", "Retry-After" to "60"))
        val base = https.url("/")
        assertEquals(Outcome.Failure(TandemError.Blocked), probe.probe(base))
        assertEquals(Outcome.Failure(TandemError.Unauthorized(UnauthorizedSource.PROXY)), probe.probe(base))
        assertEquals(Outcome.Failure(TandemError.ServerError(502)), probe.probe(base))
        assertEquals(TandemError.Throttled::class, (probe.probe(base) as Outcome.Failure).error::class)
    }

    @Test
    fun classifiesCompatibility() = runBlocking {
        https.enqueue(settings("2.7.0"), settings("2.5.3"), settings("1.5.19"), settings("nightly"))
        val base = https.url("/")
        assertEquals(Compatibility.UNTESTED_NEWER, (probe.probe(base) as Outcome.Success).value.compatibility)
        assertEquals(Compatibility.UNTESTED_OLDER, (probe.probe(base) as Outcome.Success).value.compatibility)
        assertEquals(Compatibility.TOO_OLD, (probe.probe(base) as Outcome.Success).value.compatibility)
        val nightly = (probe.probe(base) as Outcome.Success).value
        assertEquals(Compatibility.UNRECOGNIZED, nightly.compatibility)
        assertEquals("nightly", nightly.version)
    }

    @Test
    fun invalidInputSendsNothing() = runBlocking {
        assertEquals(
            Outcome.Failure(TandemError.InvalidUrl(InvalidUrlReason.CLEARTEXT_NOT_ALLOWED)),
            probe.probe("http://" + https.server.hostName + ":" + https.server.port + "/"),
        )
        assertEquals(Outcome.Failure(TandemError.InvalidUrl(InvalidUrlReason.EMPTY)), probe.probe(" "))
        assertEquals(0, https.server.requestCount)
    }

    @Test
    fun readsTheResponseOnTheIoDispatcherNotTheCallersThread() = runBlocking {
        // Android forbids socket reads on the main thread; the probe is started from it.
        var dispatched = 0
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatched++
                Dispatchers.IO.dispatch(context, block)
            }
        }
        https.enqueue(Fixture.load("01-server-settings-no-auth").toMockResponse())

        val result = ServerProbe(testClient(), ErrorClassifier.Default, io).probe(https.url("/"))

        assertEquals("2.6.13", (result as Outcome.Success).value.version)
        assertTrue("the response must be evaluated on the I/O dispatcher", dispatched > 0)
    }

    @Test
    fun untrustedCertificate() = runBlocking {
        // The production pipeline without the test CA: the server's chain is not trusted.
        val client = TandemHttpClients.create(TEST_USER_AGENT, debugLogging = false)
        val expected = Outcome.Failure(TandemError.TlsError(TlsErrorKind.UNTRUSTED_CERTIFICATE))
        val actual = ServerProbe(client).probe(https.url("/"))
        if (actual != expected) fail("expected $expected but was $actual\n" + rawFailure(client, https.url("/")))
    }

    @Test
    fun certificateForAnotherHost() = runBlocking {
        val expected = Outcome.Failure(TandemError.TlsError(TlsErrorKind.HOSTNAME_MISMATCH))
        val actual = probe.probe(wrongHost.url("/"))
        if (actual != expected) fail("expected $expected but was $actual\n" + rawFailure(testClient(), wrongHost.url("/")))
    }

    /** The raw exception chain of a direct call, to explain an unexpected classification. */
    private suspend fun rawFailure(client: OkHttpClient, url: String): String = try {
        client.newCall(Request.Builder().url(url).build()).await().close()
        "the direct call succeeded"
    } catch (e: IOException) {
        e.stackTraceToString().take(6000)
    }

    @Test
    fun unreachableServer() = runBlocking {
        // A port that just stopped listening: connection refused.
        val dead = MockWebServer().apply { start() }
        val url = dead.url("/").newBuilder().scheme("https").build().toString()
        dead.close()
        assertEquals(Outcome.Failure(TandemError.NetworkUnavailable), probe.probe(url))
    }

    @Test
    fun unknownHost() = runBlocking {
        val noDns = ServerProbe(
            testClient().newBuilder().dns { throw java.net.UnknownHostException(it) }.build(),
        )
        assertEquals(Outcome.Failure(TandemError.UnknownHost), noDns.probe("recept.example.com"))
    }
}
