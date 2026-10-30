package casa.moller.tandem.smoke

import android.content.res.AssetManager
import java.util.concurrent.CopyOnWriteArrayList
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.rules.ExternalResource

/**
 * Fas S (integration): Open Food Facts' public read API for the release smoke test, on the fixed
 * address `https://localhost:47654/` that ONLY the app's `minified` build type uses
 * (`app/src/minified/kotlin/.../SmokeEndpointsModule.kt`; every other build calls the real Open
 * Food Facts). HTTPS with the same test CA as [FakeTandoor]. Knows one synthetic product
 * ([CODE] = [PRODUCT_NAME]); every other code is Open Food Facts' 404 `status: 0`.
 */
class FakeOpenFoodFacts(private val assets: AssetManager) : ExternalResource() {
    /** One lookup as it arrived: the path, the query and the headers that matter for privacy. */
    data class Seen(val path: String, val query: String?, val userAgent: String?, val hadAuthorization: Boolean, val hadCookie: Boolean)

    private val server = MockWebServer()

    val requests: MutableList<Seen> = CopyOnWriteArrayList()

    override fun before() {
        server.useHttps(FakeTandoor.serverCertificates(assets).sslSocketFactory())
        server.protocolNegotiationEnabled = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        }
        server.start(PORT)
    }

    override fun after() {
        server.close()
    }

    private fun answer(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        requests += Seen(
            path = path,
            query = request.url.encodedQuery,
            userAgent = request.headers["User-Agent"],
            hadAuthorization = request.headers["Authorization"] != null,
            hadCookie = request.headers["Cookie"] != null,
        )
        val code = path.removePrefix("/api/v2/product/").trimEnd('/')
        return if (code == CODE) {
            val product = """{"product_name":"$PRODUCT_NAME","brands":"Exempel","quantity":"250 g"}"""
            json(OK, """{"code":"$CODE","status":1,"status_verbose":"product found","product":$product}""")
        } else {
            json(NOT_FOUND, """{"code":"$code","status":0,"status_verbose":"product not found"}""")
        }
    }

    private fun json(code: Int, body: String): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    companion object {
        /** Must match the `minified` build type's `SmokeEndpointsModule` in `:app`. */
        const val PORT = 47654

        /** A synthetic EAN-13 in GS1's restricted range 20–29 (never a real product), check digit 2. */
        const val CODE = "2000000000022"
        const val PRODUCT_NAME = "Parmesan Exempel"

        private const val OK = 200
        private const val NOT_FOUND = 404
    }
}
