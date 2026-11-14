package casa.moller.tandem.core.watch

import casa.moller.tandem.core.data.sync.RequestGuard
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.core.network.api.TandoorApiFactory
import casa.moller.tandem.core.network.http.TandemHttpClients
import casa.moller.tandem.core.network.http.TokenBucket
import java.util.concurrent.TimeUnit
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fas 5 (wear lane): the watch token on the wire, as Tandoor 2.6.13 and 2.7.x answer it (FACTS c,
 * g; fixtures 08-access-token-create and Phase5WearTokenContractTest): `POST /api/access-token/`
 * with the PHONE's token as Bearer, `{scope: "read write", expires}`, the plaintext token and id
 * read from the 201; `DELETE /api/access-token/{id}/`, a 404 counts as already deleted.
 */
class TandoorWatchTokensTest {
    private val rootCa = HeldCertificate.Builder().certificateAuthority(0).commonName("Tandem test CA").build()
    private val serverCertificates = HandshakeCertificates.Builder()
        .heldCertificate(
            HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").signedBy(rootCa).build(),
        )
        .build()
    private val clientTrust = HandshakeCertificates.Builder().addTrustedCertificate(rootCa.certificate).build()
    private val server = MockWebServer()
    private lateinit var tokens: TandoorWatchTokens
    private val guard = RecordingGuard()
    private val expires = Instant.parse("2027-02-12T10:00:00Z")

    @Before
    fun setUp() {
        server.useHttps(serverCertificates.sslSocketFactory())
        server.protocolNegotiationEnabled = false
        server.start()
        val limit = TokenBucket(permitsPerSecond = 1000, burst = 1000)
        val client = TandemHttpClients.create("Tandem/0.0.0-test (Android 36)", false, limit)
            .newBuilder()
            .sslSocketFactory(clientTrust.sslSocketFactory(), clientTrust.trustManager)
            .build()
        tokens = TandoorWatchTokens(TandoorApiFactory(client), guard)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    @Test
    fun theWatchTokenIsCreatedWithThePhonesTokenAndReadFromThe201() = runTest {
        server.enqueue(
            json(
                201,
                """{"id": 7, "token": "tda_00000000_0000_0000_0000_000000000007", """ +
                    """"expires": "2027-02-12T11:00:00+01:00", "scope": "read write"}""",
            ),
        )

        val created = tokens.create(ACCOUNT, baseUrl(), "tda_phone", expires)

        val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/access-token/", request.url.encodedPath)
        assertEquals("Bearer tda_phone", request.headers["Authorization"])
        val body = Json.parseToJsonElement(requireNotNull(request.body).utf8()).jsonObject
        assertEquals("read write", body["scope"]?.jsonPrimitive?.content)
        assertTrue(body["expires"]?.jsonPrimitive?.content.orEmpty().startsWith("2027-02-12T"))
        val token = (created as Outcome.Success).value
        assertEquals(7L, token.id)
        assertEquals("tda_00000000_0000_0000_0000_000000000007", token.token)
        assertEquals(expires, token.expires)
        assertEquals(server.url("/").toString(), token.baseUrl)
        assertTrue(!token.toString().contains("tda_"))
    }

    @Test
    fun aMaskedTokenIsNoToken() = runTest {
        server.enqueue(json(201, """{"id": 7, "token": "tda_************_******_***********0007", "scope": "read write"}"""))

        assertTrue((tokens.create(ACCOUNT, baseUrl(), "tda_phone", expires) as Outcome.Failure).error is TandemError.Decode)
    }

    @Test
    fun deletingIsDoneAlsoWhenTheTokenIsAlreadyGone() = runTest {
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(json(404, """{"detail": "No AccessToken matches the given query."}"""))
        server.enqueue(json(403, """{"detail": "Authentication credentials were not provided."}"""))

        assertEquals(Outcome.Success(Unit), tokens.delete(ACCOUNT, baseUrl(), "tda_phone", 7))
        assertEquals(Outcome.Success(Unit), tokens.delete(ACCOUNT, baseUrl(), "tda_phone", 7))
        assertEquals(
            Outcome.Failure(TandemError.Unauthorized(UnauthorizedSource.TANDOOR)),
            tokens.delete(ACCOUNT, baseUrl(), "tda_phone", 7),
        )

        val first = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("DELETE", first.method)
        assertEquals("/api/access-token/7/", first.url.encodedPath)
    }

    // --- 0.8.0 review (fix-08): every request passes the sync's guard (plan §5.4) ---

    @Test
    fun aRequestTheGuardHoldsBackIsNeverSent() = runTest {
        guard.refuse = TandemError.Unauthorized(UnauthorizedSource.TANDOOR)

        val created = tokens.create(ACCOUNT, baseUrl(), "tda_phone", expires, background = true)
        val deleted = tokens.delete(ACCOUNT, baseUrl(), "tda_phone", 7)

        assertEquals(Outcome.Failure(TandemError.Unauthorized(UnauthorizedSource.TANDOOR)), created)
        assertEquals(Outcome.Failure(TandemError.Unauthorized(UnauthorizedSource.TANDOOR)), deleted)
        assertEquals(0, server.requestCount)
        assertEquals(listOf(ACCOUNT to true, ACCOUNT to false), guard.checked)
        assertTrue("nothing sent, nothing recorded", guard.recorded.isEmpty())
    }

    @Test
    fun theAnswerIsRecordedInTheGuard() = runTest {
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "120").build())
        server.enqueue(MockResponse.Builder().code(403).addHeader("Content-Type", "text/html").body("<html>blocked</html>").build())
        server.enqueue(json(404, """{"detail": "No AccessToken matches the given query."}"""))

        val throttled = tokens.create(ACCOUNT, baseUrl(), "tda_phone", expires, background = true)
        assertTrue((throttled as Outcome.Failure).error is TandemError.Throttled)
        tokens.delete(ACCOUNT, baseUrl(), "tda_phone", 7)
        // A 404 counts as deleted, but still counts in the circuit breaker like every 404.
        assertEquals(Outcome.Success(Unit), tokens.delete(ACCOUNT, baseUrl(), "tda_phone", 7))

        assertEquals(3, guard.recorded.size)
        assertTrue(guard.recorded[0].second is TandemError.Throttled)
        assertEquals(TandemError.NotFound, guard.recorded[2].second)
        assertTrue(guard.recorded.all { it.first == ACCOUNT })
    }

    /** The sync's guard as the watch token sees it: what was asked and recorded. */
    private class RecordingGuard : RequestGuard {
        var refuse: TandemError? = null
        val checked = mutableListOf<Pair<String, Boolean>>()
        val recorded = mutableListOf<Pair<String, TandemError?>>()

        override fun check(accountId: String, background: Boolean): TandemError? {
            checked += accountId to background
            return refuse
        }

        override fun record(accountId: String, error: TandemError?) {
            recorded += accountId to error
        }
    }

    private companion object {
        const val ACCOUNT = "account-1"
    }
}
