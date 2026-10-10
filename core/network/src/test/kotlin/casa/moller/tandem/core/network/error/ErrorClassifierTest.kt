package casa.moller.tandem.core.network.error

import casa.moller.tandem.core.model.error.InvalidUrlReason
import casa.moller.tandem.core.model.error.NotTandoorReason
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.TlsErrorKind
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.core.network.dto.AccessTokenDto
import casa.moller.tandem.core.network.dto.TokenAuthResponseDto
import casa.moller.tandem.core.network.http.TandemIOException
import casa.moller.tandem.core.network.json.TandoorJson
import casa.moller.tandem.core.network.testing.Fixture
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ErrorClassifierTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-09T06:00:00Z"), ZoneOffset.UTC)
    private val classifier = ErrorClassifier(clock)

    private fun classifyFixture(name: String): TandemError? {
        val fixture = Fixture.load(name)
        val headers = Headers.Builder().apply { fixture.headers.forEach { (k, v) -> add(k, v) } }.build()
        return classifier.classifyHttp(
            status = fixture.status,
            headers = headers,
            contentType = fixture.contentType()?.toMediaTypeOrNull(),
            bodyText = fixture.bodyText,
        )
    }

    private fun classify(
        status: Int,
        body: String? = null,
        contentType: String? = null,
        headers: List<Pair<String, String>> = emptyList(),
    ): TandemError? = classifier.classifyHttp(
        status = status,
        headers = Headers.Builder().apply { headers.forEach { (k, v) -> add(k, v) } }.build(),
        contentType = contentType?.toMediaTypeOrNull(),
        bodyText = body,
    )

    // --- Observed fixtures (Tandoor 2.6.13) ---

    @Test
    fun successFixturesAreNotErrors() {
        listOf(
            "01-server-settings-no-auth", "02-server-settings-invalid-token", "03-login-json",
            "08-access-token-create", "09-access-token-list-fresh", "11-user-by-id",
            "12-user-preference-list", "13-space-current", "30-revoke-login-token",
        ).forEach { assertNull(it, classifyFixture(it)) }
    }

    @Test
    fun everyTokenErrorIsUnauthorizedFromTandoor() {
        // Missing, unknown, garbage, empty Bearer, expired and revoked tokens: all 403 + JSON.
        listOf(
            "15-invalid-token-missing-get", "16-invalid-token-missing-post",
            "17-invalid-token-unknownWellFormed-get", "18-invalid-token-unknownWellFormed-post",
            "19-invalid-token-garbage-get", "20-invalid-token-garbage-post",
            "21-invalid-token-emptyBearer-get", "22-invalid-token-emptyBearer-post",
            "23-invalid-token-accept-language-sv", "25-expired-token-get",
            "31-revoked-login-token-get", "35-revoked-device-token-get", "36-revoked-device-token-post",
        ).forEach {
            assertEquals(it, TandemError.Unauthorized(UnauthorizedSource.TANDOOR), classifyFixture(it))
        }
    }

    @Test
    fun jsonNotFoundIsNotFound() {
        assertEquals(TandemError.NotFound, classifyFixture("27-not-found-recipe"))
        assertEquals(TandemError.NotFound, classifyFixture("28-not-found-access-token"))
    }

    @Test
    fun unknownApiPathRedirectsToLogin() {
        assertEquals(
            TandemError.NotTandoor(NotTandoorReason.LOGIN_REDIRECT, 302),
            classifyFixture("29-not-found-unknown-path"),
        )
    }

    @Test
    fun loginBadRequestsCarryFieldErrors() {
        val wrong = classifyFixture("06-login-wrong-password") as TandemError.BadRequest
        assertEquals(listOf("Unable to log in with provided credentials."), wrong.nonFieldErrors)

        val missing = classifyFixture("07-login-missing-fields") as TandemError.BadRequest
        assertEquals(setOf("username", "password"), missing.fieldErrors.keys)
        assertEquals(listOf("This field is required."), missing.fieldErrors["username"])
        assertEquals(emptyList<String>(), missing.nonFieldErrors)
    }

    @Test
    fun throttledReadsRetryAfterSeconds() {
        assertEquals(TandemError.Throttled(86380.seconds), classifyFixture("37-login-throttled"))
    }

    // --- Synthetic responses for the branches Tandoor itself does not produce ---

    @Test
    fun status401IsUnauthorizedFromProxy() {
        assertEquals(TandemError.Unauthorized(UnauthorizedSource.PROXY), classify(401))
        assertEquals(TandemError.Unauthorized(UnauthorizedSource.PROXY), classify(401, "<html></html>", "text/html"))
    }

    @Test
    fun status403WithoutJsonIsBlocked() {
        assertEquals(TandemError.Blocked, classify(403))
        assertEquals(TandemError.Blocked, classify(403, "Forbidden", "text/plain"))
        assertEquals(TandemError.Blocked, classify(403, "<html><body>Access denied</body></html>", "text/html"))
        assertEquals(TandemError.Blocked, classify(403, "[]", "application/json"))
    }

    @Test
    fun status403WithJsonObjectIsUnauthorizedWhateverTheLanguage() {
        assertEquals(
            TandemError.Unauthorized(UnauthorizedSource.TANDOOR),
            classify(403, """{"detail":"Autentiseringsuppgifter ej tillhandahållna."}""", "application/json"),
        )
    }

    @Test
    fun htmlNotFoundIsNotTandoor() {
        assertEquals(
            TandemError.NotTandoor(NotTandoorReason.UNEXPECTED_STATUS, 404),
            classify(404, "<!DOCTYPE html><html>Not Found</html>", "text/html"),
        )
        assertEquals(TandemError.NotTandoor(NotTandoorReason.UNEXPECTED_STATUS, 404), classify(404))
    }

    @Test
    fun otherRedirectsAreNotTandoor() {
        assertEquals(
            TandemError.NotTandoor(NotTandoorReason.REDIRECT, 301),
            classify(301, headers = listOf("Location" to "https://sso.example.com/auth")),
        )
        assertEquals(TandemError.NotTandoor(NotTandoorReason.REDIRECT, 308), classify(308))
    }

    @Test
    fun successWithHtmlIsNotTandoor() {
        assertEquals(
            TandemError.NotTandoor(NotTandoorReason.HTML_RESPONSE, 200),
            classify(200, "<html><body>Login</body></html>", "text/html"),
        )
        assertEquals(
            TandemError.NotTandoor(NotTandoorReason.HTML_RESPONSE, 200),
            classify(200, "<!DOCTYPE html><html></html>"),
        )
        assertNull(classify(200, """{"a":1}""", "application/json"))
        assertNull(classify(204))
    }

    @Test
    fun badRequestVariants() {
        assertEquals(TandemError.BadRequest(), classify(400))
        assertEquals(TandemError.BadRequest(), classify(400, "<html>Bad Request</html>", "text/html"))
        assertEquals(
            TandemError.BadRequest(mapOf("name" to listOf("too long"), "detail" to listOf("x"))),
            classify(400, """{"name":"too long","detail":["x"]}""", "application/json"),
        )
    }

    @Test
    fun throttledVariants() {
        assertEquals(TandemError.Throttled(null), classify(429))
        assertEquals(TandemError.Throttled(null), classify(429, headers = listOf("Retry-After" to "soon")))
        assertEquals(
            TandemError.Throttled(120.seconds),
            classify(429, headers = listOf("Retry-After" to "Fri, 09 Oct 2026 06:02:00 GMT")),
        )
        assertEquals(
            TandemError.Throttled(kotlin.time.Duration.ZERO),
            classify(429, headers = listOf("Retry-After" to "Thu, 08 Oct 2026 06:02:00 GMT")),
        )
    }

    @Test
    fun serverErrors() {
        listOf(500, 502, 503, 504).forEach { assertEquals(TandemError.ServerError(it), classify(it, "<html/>", "text/html")) }
    }

    @Test
    fun unexpectedClientStatusIsUnknown() {
        assertEquals(TandemError.Unknown("HTTP 405"), classify(405, """{"detail":"Method not allowed"}""", "application/json"))
        assertEquals(TandemError.Unknown("HTTP 418"), classify(418))
    }

    // --- Exceptions ---

    @Test
    fun ioExceptions() {
        assertEquals(TandemError.Timeout, classifier.classify(SocketTimeoutException("Read timed out")))
        assertEquals(TandemError.Timeout, classifier.classify(InterruptedIOException("timeout")))
        assertEquals(TandemError.UnknownHost, classifier.classify(UnknownHostException("recept.example.com")))
        assertEquals(TandemError.NetworkUnavailable, classifier.classify(ConnectException("Connection refused")))
        assertEquals(TandemError.NetworkUnavailable, classifier.classify(IOException("unexpected end of stream")))
    }

    @Test
    fun tlsExceptions() {
        assertEquals(
            TandemError.TlsError(TlsErrorKind.HOSTNAME_MISMATCH),
            classifier.classify(SSLPeerUnverifiedException("Hostname x not verified")),
        )
        val untrusted = SSLHandshakeException("PKIX path building failed").apply {
            initCause(CertificateException("unable to find valid certification path"))
        }
        assertEquals(TandemError.TlsError(TlsErrorKind.UNTRUSTED_CERTIFICATE), classifier.classify(untrusted))
        val androidStyle = SSLHandshakeException("Trust anchor not found").apply {
            initCause(CertPathValidatorException("Trust anchor for certification path not found."))
        }
        assertEquals(TandemError.TlsError(TlsErrorKind.UNTRUSTED_CERTIFICATE), classifier.classify(androidStyle))
        assertEquals(
            TandemError.TlsError(TlsErrorKind.HANDSHAKE_FAILED),
            classifier.classify(SSLHandshakeException("no cipher suites in common")),
        )
    }

    @Test
    fun tlsFailureOnOneRouteWinsOverConnectionRefusedOnAnother() {
        // OkHttp's fast fallback: ::1 refused, 127.0.0.1 failed the handshake (suppressed).
        val refused = ConnectException("Failed to connect to localhost/[0:0:0:0:0:0:0:1]:443").apply {
            addSuppressed(SSLPeerUnverifiedException("Hostname localhost not verified"))
        }
        assertEquals(TandemError.TlsError(TlsErrorKind.HOSTNAME_MISMATCH), classifier.classify(refused))
        val untrusted = IOException("route failed").apply {
            addSuppressed(
                SSLHandshakeException("PKIX path building failed").apply {
                    initCause(CertificateException("unable to find valid certification path"))
                },
            )
        }
        assertEquals(TandemError.TlsError(TlsErrorKind.UNTRUSTED_CERTIFICATE), classifier.classify(untrusted))
    }

    @Test
    fun otherThrowables() {
        // Only the exception type: a decoder message can quote the response body (tokens).
        assertEquals(TandemError.Decode("SerializationException"), classifier.classify(SerializationException("bad json")))
        assertEquals(
            TandemError.InvalidUrl(InvalidUrlReason.CLEARTEXT_NOT_ALLOWED),
            classifier.classify(TandemIOException(TandemError.InvalidUrl(InvalidUrlReason.CLEARTEXT_NOT_ALLOWED))),
        )
        assertEquals(TandemError.Unknown("java.lang.IllegalStateException"), classifier.classify(IllegalStateException("boom")))
    }

    @Test
    fun retrofitHttpExceptionUsesStatusAndErrorBody() {
        val json = "application/json".toMediaType()
        val unauthorized = HttpException(
            Response.error<Any>(403, """{"detail":"Authentication credentials were not provided."}""".toResponseBody(json)),
        )
        assertEquals(TandemError.Unauthorized(UnauthorizedSource.TANDOOR), classifier.classify(unauthorized))

        val blocked = HttpException(Response.error<Any>(403, "".toResponseBody(null)))
        assertEquals(TandemError.Blocked, classifier.classify(blocked))

        val notFound = HttpException(Response.error<Any>(404, """{"detail":"No Recipe matches the given query."}""".toResponseBody(json)))
        assertEquals(TandemError.NotFound, classifier.classify(notFound))
    }

    // --- Decode errors never carry the response body (finding: tokens in TandemError) ---

    private fun decodeDetail(block: () -> Unit): String {
        val outcome = runBlocking { apiCall(classifier) { block() } }
        val error = (outcome as casa.moller.tandem.core.model.error.Outcome.Failure).error
        return (error as TandemError.Decode).detail.orEmpty()
    }

    @Test
    fun decodeErrorOfALoginResponseNeverContainsTheToken() {
        // A newer Tandoor changes the type of one field; the body is short enough that
        // kotlinx.serialization quotes all of it in its message.
        val body = """{"id":1,"token":"tda_11111111_2222_3333_4444_555555555555","scope":"read write app",""" +
            """"expires":"2031-10-09T06:00:00Z","user_id":1,"test":true}"""
        val detail = decodeDetail { TandoorJson.decodeFromString(TokenAuthResponseDto.serializer(), body) }

        assertFalse(detail, detail.contains("tda_"))
        assertFalse(detail, detail.contains("555555555555"))
        assertTrue(detail, detail.contains("$.test"))
    }

    @Test
    fun decodeErrorOfACreatedTokenNeverContainsTheToken() {
        val body = """{"id":"0b3f6c1e-uuid","token":"tda_99999999_8888_7777_6666_555555555555",""" +
            """"expires":"2027-10-09T06:00:00Z","scope":"read write"}"""
        val detail = decodeDetail { TandoorJson.decodeFromString(AccessTokenDto.serializer(), body) }

        assertFalse(detail, detail.contains("tda_"))
        assertFalse(detail, detail.contains("uuid"))
        assertTrue(detail, detail.contains("$.id"))
    }

    @Test
    fun decodeErrorKeepsMissingFieldNamesOnly() {
        val detail = decodeDetail {
            TandoorJson.decodeFromString(RequiredToken.serializer(), """{"other":"tda_secret_value"}""")
        }
        assertFalse(detail, detail.contains("tda_"))
        assertTrue(detail, detail.contains("missing token"))
    }

    @Test
    fun decodeErrorFromAPlainMessageKeepsOnlyTheType() {
        val error = classifier.classify(SerializationException("JSON input: {\"token\":\"tda_abc\"}"))
        assertEquals(TandemError.Decode("SerializationException"), error)
    }

    @kotlinx.serialization.Serializable
    private data class RequiredToken(val token: String)
}
