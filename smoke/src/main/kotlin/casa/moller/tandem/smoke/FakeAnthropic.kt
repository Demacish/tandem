package casa.moller.tandem.smoke

import java.util.concurrent.CopyOnWriteArrayList
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest

/**
 * Fas S (ai-foto lane): a fake Anthropic endpoint inside [FakeTandoor]'s HTTPS server (the same
 * test CA), reached through Settings → AI's own base URL. It proves that the R8-minified app's
 * official Anthropic SDK (`anthropic-java`: its OkHttp client, Jackson and the model types) still
 * builds a request and reads an answer: "Testa nyckeln" is `GET /v1/models/{model}`. Only the
 * fake key `sk-test-not-a-real-key` is ever accepted; no real AI service is called.
 */
class FakeAnthropic {
    /** One request as the server saw it: never the key itself, only whether it was the expected one. */
    data class Seen(
        val method: String,
        val path: String,
        val keyMatched: Boolean,
        val hadAuthorization: Boolean,
        val sdkVersionHeader: Boolean,
    )

    val requests: MutableList<Seen> = CopyOnWriteArrayList()

    /** Answers `/v1/...`; null for every other path (Tandoor's). */
    fun answer(request: RecordedRequest): MockResponse? {
        val path = request.url.encodedPath
        if (!path.startsWith("/v1/")) return null
        val key = request.headers["x-api-key"]
        val seen = Seen(
            method = request.method,
            path = path,
            keyMatched = key == KEY,
            hadAuthorization = request.headers["Authorization"] != null,
            sdkVersionHeader = request.headers["anthropic-version"] != null,
        )
        requests += seen
        return when {
            !seen.keyMatched -> json(UNAUTHORIZED, """{"type":"error","error":{"type":"authentication_error","message":"fake"}}""")

            request.method == "GET" && path.startsWith("/v1/models/") -> {
                val model = path.removePrefix("/v1/models/")
                json(OK, """{"type":"model","id":"$model","display_name":"Fake model","created_at":"2026-01-01T00:00:00Z"}""")
            }

            else -> json(NOT_FOUND, """{"type":"error","error":{"type":"not_found_error","message":"fake"}}""")
        }
    }

    private fun json(code: Int, body: String): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    companion object {
        /** The only key the fake accepts: obviously not a real one. */
        const val KEY = "sk-test-not-a-real-key"

        private const val OK = 200
        private const val UNAUTHORIZED = 401
        private const val NOT_FOUND = 404
    }
}
