package casa.moller.tandem.core.network.testing

import casa.moller.tandem.core.network.http.TandemHttpClients
import casa.moller.tandem.core.network.http.TokenBucket
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.rules.ExternalResource

const val TEST_USER_AGENT = "Tandem/1.2.3-test (Android 36)"

/** A private CA and certificates for HTTPS MockWebServers (the client rejects cleartext). */
object TestTls {
    private val rootCa: HeldCertificate = HeldCertificate.Builder()
        .certificateAuthority(0)
        .commonName("Tandem test CA")
        .build()

    private fun serverCertificate(vararg names: String): HandshakeCertificates {
        val held = HeldCertificate.Builder()
            .commonName(names.first())
            .apply { names.forEach { addSubjectAlternativeName(it) } }
            .signedBy(rootCa)
            .build()
        return HandshakeCertificates.Builder().heldCertificate(held).build()
    }

    /** Valid for the names MockWebServer uses. */
    val localhost: HandshakeCertificates = serverCertificate("localhost", "127.0.0.1")

    /** Signed by the trusted CA, but for another host name. */
    val wrongHost: HandshakeCertificates = serverCertificate("tandoor.invalid")

    /** Client side: trusts only the test CA. */
    val clientTrust: HandshakeCertificates = HandshakeCertificates.Builder()
        .addTrustedCertificate(rootCa.certificate)
        .build()
}

/** Starts an HTTPS MockWebServer (HTTP/1.1) for one test. */
class HttpsServerRule(private val certificates: HandshakeCertificates = TestTls.localhost) : ExternalResource() {
    val server: MockWebServer = MockWebServer()

    override fun before() {
        server.useHttps(certificates.sslSocketFactory())
        server.protocolNegotiationEnabled = false
        server.start()
    }

    override fun after() {
        server.close()
    }

    /** e.g. `https://localhost:12345/` or with a sub-path. */
    fun url(path: String = "/"): String = server.url(path).toString()

    fun takeRequest(): RecordedRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "No request arrived within 5 s" }

    fun enqueue(vararg responses: MockResponse) = responses.forEach { server.enqueue(it) }
}

/** The production client pipeline, trusting the test CA. */
fun testClient(
    rateLimiter: TokenBucket = TokenBucket(permitsPerSecond = 1000, burst = 1000),
    debugLogging: Boolean = false,
    logger: (String) -> Unit = {},
): OkHttpClient = TandemHttpClients.create(TEST_USER_AGENT, debugLogging, rateLimiter) { logger(it) }
    .newBuilder()
    .sslSocketFactory(TestTls.clientTrust.sslSocketFactory(), TestTls.clientTrust.trustManager)
    .build()

fun jsonResponse(code: Int, body: String, vararg headers: Pair<String, String>): MockResponse = MockResponse.Builder()
    .code(code)
    .addHeader("Content-Type", "application/json")
    .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
    .body(body)
    .build()

fun htmlResponse(code: Int, body: String = "<!DOCTYPE html><html><body>Hello</body></html>"): MockResponse = MockResponse.Builder()
    .code(code)
    .addHeader("Content-Type", "text/html; charset=utf-8")
    .body(body)
    .build()

fun emptyResponse(code: Int, vararg headers: Pair<String, String>): MockResponse = MockResponse.Builder()
    .code(code)
    .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
    .build()

/** The repository's `api-schema/` folder. */
object ApiSchema {
    fun dir(): File {
        val path = System.getProperty("tandem.apiSchemaDir")
        require(!path.isNullOrBlank()) { "tandem.apiSchemaDir is not set (core/network/build.gradle.kts)" }
        return File(path).also { require(it.isDirectory) { "Not a directory: $it" } }
    }
}

/** One recorded exchange from `api-schema/fixtures/<version>` (2.6.13, or 2.7.1 for Tandoor 2.7), read in place. */
class Fixture(val name: String, private val root: JsonObject) {
    private val request = root.getValue("request").jsonObject
    private val response = root.getValue("response").jsonObject

    val method: String get() = request.getValue("method").jsonPrimitive.content
    val path: String get() = request.getValue("path").jsonPrimitive.content
    val status: Int get() = response.getValue("status").jsonPrimitive.content.toInt()
    val headers: Map<String, String>
        get() = response["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
    val body: JsonElement? get() = response["body"]
    val bodyText: String
        get() = response["body"]?.toString() ?: (response["bodyText"] as? JsonPrimitive)?.content.orEmpty()

    fun contentType(): String? = headers["content-type"]

    /** The recorded response, optionally with some top-level body fields replaced. */
    fun toMockResponse(overrides: Map<String, JsonElement> = emptyMap()): MockResponse {
        val text = if (overrides.isEmpty()) {
            bodyText
        } else {
            JsonObject(body!!.jsonObject + overrides).toString()
        }
        // Read outside the builder: inside `apply`, `headers` would mean MockResponse.Builder.headers.
        val recordedHeaders = headers.filterKeys { it !in SKIPPED_HEADERS }
        return MockResponse.Builder()
            .code(status)
            .apply { recordedHeaders.forEach { (name, value) -> addHeader(name, value) } }
            .body(text)
            .build()
    }

    companion object {
        private val SKIPPED_HEADERS = setOf("content-length", "transfer-encoding", "connection")
        private val json = Json { ignoreUnknownKeys = true }

        /** `fas1/recipe-flat` or `13-space-current`, below `api-schema/fixtures/<version>/` (2.6.13 by default). */
        fun load(name: String, version: String = "2.6.13"): Fixture {
            val file = File(fixtureDir(version), "$name.json")
            require(file.isFile) { "Missing fixture $file" }
            return Fixture(name, json.parseToJsonElement(file.readText()).jsonObject)
        }

        /** `api-schema/fixtures/2.6.13`, read in place (system property set by core/network/build.gradle.kts). */
        fun fixtureDir(version: String = "2.6.13"): File = File(ApiSchema.dir(), "fixtures/$version")

        /** Every recorded exchange (files with a `request`), as names for [load]. */
        fun allNames(version: String = "2.6.13"): List<String> {
            val root = fixtureDir(version)
            return root.walkTopDown()
                .filter { it.isFile && it.extension == "json" }
                .filter { json.parseToJsonElement(it.readText()).let { e -> e is JsonObject && "request" in e } }
                .map { it.relativeTo(root).invariantSeparatorsPath.removeSuffix(".json") }
                .sorted()
                .toList()
        }
    }
}
