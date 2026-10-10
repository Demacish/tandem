package casa.moller.tandem.smoke

import android.content.res.AssetManager
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.rules.ExternalResource

/**
 * A Tandoor 2.6.13 stand-in for the smoke test: an HTTPS MockWebServer on localhost that answers
 * the endpoints of the intro, the shell and logout with the sanitized recordings in
 * `api-schema/fixtures/2.6.13` (packaged as assets of this test APK).
 *
 * Its certificate is issued at runtime by the fixed, test-only CA in
 * `assets/tandem-smoke-test-ca.pem`, which only the app's `minified` build type trusts
 * (`app/src/minified/res/xml/network_security_config.xml`). The app therefore runs its normal
 * HTTPS path, with no "trust everything" switch anywhere.
 *
 * Tokens work like on the real server: the password login hands out [LOGIN_TOKEN], which may
 * create the device token [DEVICE_TOKEN]; every other API call needs one of the two as a Bearer
 * token, otherwise it gets Tandoor's 403. A deleted token stops working.
 */
class FakeTandoor(private val assets: AssetManager) : ExternalResource() {
    private val server = MockWebServer()
    private val revoked = CopyOnWriteArrayList<String>()

    /** `METHOD path` of every request, in order, e.g. `POST /api-token-auth/`. */
    val requests: MutableList<String> = CopyOnWriteArrayList()

    /** What the user types as the server address, e.g. `localhost:41234`. */
    val address: String get() = "localhost:${server.port}"

    override fun before() {
        val packaged = assets.list("").orEmpty().toSet()
        check(FIXTURE_CHECK in packaged && CA_ASSET in packaged) {
            "Test APK assets incomplete (api-schema/fixtures/2.6.13 not packaged?): $packaged"
        }
        val ca = HeldCertificate.decode(assets.open(CA_ASSET).bufferedReader().use { it.readText() })
        val serverCertificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .signedBy(ca)
            .build()
        val certificates = HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate, ca.certificate)
            .build()
        server.useHttps(certificates.sslSocketFactory())
        server.protocolNegotiationEnabled = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = try {
                respond(request).also { Log.i(TAG, "${request.method} ${request.url.encodedPath} -> ${it.code}") }
            } catch (e: Exception) {
                // Visible in logcat; the app then sees a 500 instead of a dropped connection.
                Log.e(TAG, "${request.method} ${request.url.encodedPath} failed in the fake server", e)
                MockResponse.Builder().code(500).body("fake Tandoor: ${e.javaClass.simpleName}").build()
            }
        }
        server.start()
    }

    override fun after() {
        server.close()
    }

    private fun respond(request: RecordedRequest): MockResponse {
        val method = request.method
        val path = request.url.encodedPath
        requests += "$method $path"
        val token = request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        val loggedIn = token != null && token in VALID_TOKENS && token !in revoked
        return when {
            method == "GET" && path == "/api/server-settings/current/" -> fixture("01-server-settings-no-auth")

            method == "POST" && path == "/api-token-auth/" -> {
                val body = request.body?.utf8().orEmpty()
                if ("\"username\"" in body && "\"password\"" in body) {
                    fixture("03-login-json")
                } else {
                    fixture("07-login-missing-fields")
                }
            }

            !loggedIn -> fixture(if (method == "GET") "15-invalid-token-missing-get" else "16-invalid-token-missing-post")

            method == "POST" && path == "/api/access-token/" ->
                if (token == LOGIN_TOKEN) fixture("08-access-token-create") else fixture("16-invalid-token-missing-post")

            method == "GET" && path == "/api/access-token/" -> fixture("09-access-token-list-fresh")

            method == "DELETE" && ACCESS_TOKEN_PATH.matches(path) -> {
                val id = ACCESS_TOKEN_PATH.find(path)!!.groupValues[1]
                TOKENS_BY_ID[id]?.let { revoked += it }
                fixture("34-revoke-device-token")
            }

            method == "GET" && path == "/api/space/current/" -> fixture("13-space-current")

            method == "GET" && path == "/api/user-preference/" -> fixture("12-user-preference-list")

            method == "GET" && USER_PATH.matches(path) -> fixture("11-user-by-id")

            // Tandoor sends unknown paths to its login page (FACTS g).
            else -> fixture("29-not-found-unknown-path")
        }
    }

    /** The recorded response of [name], without hop-by-hop and length headers. */
    private fun fixture(name: String): MockResponse {
        val root = json.parseToJsonElement(assets.open("$name.json").bufferedReader().use { it.readText() }).jsonObject
        val response = root.getValue("response").jsonObject
        val status = response.getValue("status").jsonPrimitive.content.toInt()
        val headers = response["headers"]?.jsonObject.orEmpty()
            .mapValues { it.value.jsonPrimitive.content }
            .filterKeys { it !in SKIPPED_HEADERS }
        val body = when (val recorded = response["body"]) {
            null, is JsonPrimitive -> (response["bodyText"] as? JsonPrimitive)?.content.orEmpty()
            else -> recorded.toString()
        }
        return MockResponse.Builder()
            .code(status)
            .apply { headers.forEach { (header, value) -> addHeader(header, value) } }
            .body(body)
            .build()
    }

    companion object {
        /** From `03-login-json`: the token `api-token-auth` returns. */
        const val LOGIN_TOKEN = "tda_00000000_0000_0000_0000_000000000001"

        /** From `08-access-token-create`: this device's own token (id 2). */
        const val DEVICE_TOKEN = "tda_00000000_0000_0000_0000_000000000002"

        private const val CA_ASSET = "tandem-smoke-test-ca.pem"
        private const val FIXTURE_CHECK = "01-server-settings-no-auth.json"
        private const val TAG = "FakeTandoor"
        private val VALID_TOKENS = setOf(LOGIN_TOKEN, DEVICE_TOKEN)
        private val TOKENS_BY_ID = mapOf("1" to LOGIN_TOKEN, "2" to DEVICE_TOKEN)
        private val ACCESS_TOKEN_PATH = Regex("^/api/access-token/(\\d+)/$")
        private val USER_PATH = Regex("^/api/user/\\d+/$")
        private val SKIPPED_HEADERS = setOf("content-length", "transfer-encoding", "connection")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
