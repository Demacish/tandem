package casa.moller.tandem.core.sync.testing

import casa.moller.tandem.core.network.http.TandemHttpClients
import casa.moller.tandem.core.network.http.TokenBucket
import casa.moller.tandem.core.testing.fixtures.TandoorFixtures
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/** A private CA for the HTTPS test server (Tandem refuses cleartext, plan 5.5). */
internal object TestTls {
    private val rootCa: HeldCertificate = HeldCertificate.Builder()
        .certificateAuthority(0)
        .commonName("Tandem sync test CA")
        .build()

    val server: HandshakeCertificates = HandshakeCertificates.Builder()
        .heldCertificate(
            HeldCertificate.Builder()
                .commonName("localhost")
                .addSubjectAlternativeName("localhost")
                .addSubjectAlternativeName("127.0.0.1")
                .signedBy(rootCa)
                .build(),
        )
        .build()

    val client: HandshakeCertificates = HandshakeCertificates.Builder()
        .addTrustedCertificate(rootCa.certificate)
        .build()

    /** The production client pipeline (limits, HTTPS only, no redirects) trusting the test CA. */
    fun httpClient(): OkHttpClient = TandemHttpClients.create("Tandem/0-test (Android 36)", false, TokenBucket(1000, 1000))
        .newBuilder()
        .sslSocketFactory(client.sslSocketFactory(), client.trustManager)
        .build()
}

/**
 * A Tandoor 2.6.13 stand-in: an HTTPS MockWebServer answering with the recorded Phase 1
 * fixtures (`api-schema/fixtures/2.6.13/fas1`). Tests override single paths with [respond] and
 * read what arrived in [requests] (`METHOD path?query`).
 */
internal class FakeTandoorServer {
    private val server = MockWebServer()
    private val overrides = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val inFlightDetails = AtomicInteger()

    /** Every request as `METHOD /path?query`, in arrival order. */
    val requests: MutableList<String> = CopyOnWriteArrayList()

    /** The highest number of recipe detail requests that were in progress at the same time. */
    val maxParallelDetails = AtomicInteger()

    /**
     * Phase 2 (inköp-data lane): answers requests before the recorded routes (e.g. a stateful
     * shopping list, `FakeShoppingTandoor`); null = not handled.
     */
    @Volatile
    var handler: ((RecordedRequest) -> MockResponse?)? = null

    /** Milliseconds each recipe detail answer is held back (to observe parallelism). */
    @Volatile
    var detailDelayMillis: Long = 0

    val baseUrl: String
        get() = server.url("/").toString()

    fun start() {
        server.useHttps(TestTls.server.sslSocketFactory())
        server.protocolNegotiationEnabled = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        }
        server.start()
    }

    fun close() {
        server.close()
    }

    /** Answers [method] [path] (without query) with [response] from now on. */
    fun respond(method: String, path: String, response: (RecordedRequest) -> MockResponse) {
        overrides["$method $path"] = response
    }

    fun requestsTo(method: String, path: String): List<String> =
        requests.filter { it == "$method $path" || it.startsWith("$method $path?") }

    private fun answer(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        val query = request.url.encodedQuery?.let { "?$it" }.orEmpty()
        requests += "${request.method} $path$query"
        overrides["${request.method} $path"]?.let { return it(request) }
        handler?.invoke(request)?.let { return it }
        val detail = RECIPE_PATH.find(path)
        if (request.method == "GET" && detail != null) {
            val running = inFlightDetails.incrementAndGet()
            maxParallelDetails.accumulateAndGet(running) { a, b -> maxOf(a, b) }
            try {
                if (detailDelayMillis > 0) Thread.sleep(detailDelayMillis)
                return fixture(RECIPES[detail.groupValues[1]] ?: "fas1/recipe-deleted-get")
            } finally {
                inFlightDetails.decrementAndGet()
            }
        }
        if (request.method == "GET" && path == "/api/recipe/" && HOME_QUERIES.any { request.url.queryParameter(it) != null }) {
            // Today's carousel queries: empty unless a test overrides them.
            return json(200, EMPTY_PAGE)
        }
        val name = ROUTES["${request.method} $path"] ?: return json(404, """{"detail":"Not found."}""")
        return fixture(name)
    }

    companion object {
        private val RECIPE_PATH = Regex("^/api/recipe/(\\d+)/$")
        private val HOME_QUERIES = listOf("timescooked_gte", "new", "viewedon_gte", "cookedon_lte")
        const val EMPTY_PAGE = """{"count":0,"next":null,"previous":null,"results":[]}"""

        private val ROUTES = mapOf(
            "GET /api/server-settings/current/" to "01-server-settings-no-auth",
            "GET /api/space/current/" to "fas1/space-current",
            "GET /api/user-preference/" to "fas1/user-preference",
            "GET /api/recipe/flat/" to "fas1/recipe-flat",
            "GET /api/recipe/" to "fas1/recipe-list-default",
            "GET /api/keyword/" to "fas1/keyword-list",
            "GET /api/food/" to "fas1/food-list",
            "GET /api/unit/" to "fas1/unit-list",
            "GET /api/meal-type/" to "fas1/meal-type-list",
            "GET /api/meal-plan/" to "fas1/meal-plan-list",
            "GET /api/cook-log/" to "fas1/cook-log-list-recipe",
            "POST /api/view-log/" to "fas1/view-log-create",
            // Phase 2 (inköp-data lane): every sync run reads the shopping list and its small tables.
            "GET /api/shopping-list-entry/" to "fas2/shopping-list-entry-list",
            "GET /api/shopping-list/" to "fas2/shopping-list-list",
            "GET /api/supermarket/" to "fas2/supermarket-list",
            "GET /api/supermarket-category/" to "fas2/supermarket-category-list",
            // Phase 2 (böcker lane): the role and the books are read with the small tables.
            "GET /api/user-space/all_personal/" to "14-user-space-all-personal",
            "GET /api/recipe-book/" to "fas1/recipe-book-list",
            "GET /api/recipe-book-entry/" to "fas1/recipe-book-entry-list",
        )

        /** Recipe id -> recorded `GET /api/recipe/{id}/`; 12 was deleted (404). */
        val RECIPES = mapOf(
            "1" to "fas1/recipe-pyttipanna",
            "2" to "fas1/recipe-svamppasta",
            "3" to "fas1/recipe-pannkakor",
            "4" to "fas1/recipe-kanelbullar",
            "5" to "fas1/recipe-kottbullar",
            "6" to "fas1/recipe-shakshuka",
            "7" to "fas1/recipe-artsoppa",
            "8" to "fas1/recipe-gronsakssoppa",
            "9" to "fas1/recipe-smorgastarta",
            "10" to "fas1/recipe-appelkaka",
            "11" to "fas1/recipe-rarakor",
        )

        fun fixture(name: String): MockResponse {
            val exchange = TandoorFixtures.exchange(name)
            return json(exchange.status, exchange.body.orEmpty())
        }

        fun json(code: Int, body: String): MockResponse = MockResponse.Builder()
            .code(code)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()
    }
}
