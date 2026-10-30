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
 *
 * Fas S (integration): `POST /v1/messages` (the fridge photo, the SDK's beta Messages call with
 * structured output) answers a message whose text block is [ITEMS], as Claude would; [messages]
 * keeps what the request carried (never the photo or the key).
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

    /** A `POST /v1/messages` as it arrived: the model, whether a JPEG and the JSON schema output were in it. */
    data class Message(val model: String?, val hadJpeg: Boolean, val structuredOutput: Boolean, val betaHeader: String?)

    val messages: MutableList<Message> = CopyOnWriteArrayList()

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

            request.method == "POST" && path == "/v1/messages" -> {
                val body = request.body?.utf8().orEmpty()
                messages += Message(
                    model = MODEL.find(body)?.groupValues?.get(1),
                    hadJpeg = "image/jpeg" in body && "base64" in body,
                    structuredOutput = "json_schema" in body,
                    betaHeader = request.headers.values("anthropic-beta").joinToString(","),
                )
                json(OK, message())
            }

            else -> json(NOT_FOUND, """{"type":"error","error":{"type":"not_found_error","message":"fake"}}""")
        }
    }

    /** A finished Claude message whose text is [ITEMS] (the JSON the schema asks for). */
    private fun message(): String {
        val text = ITEMS.replace("\"", "\\\"")
        return """{"id":"msg_fake","type":"message","role":"assistant","model":"claude-opus-5-5",""" +
            """"content":[{"type":"text","text":"$text"}],"stop_reason":"end_turn","stop_sequence":null,""" +
            """"usage":{"input_tokens":10,"output_tokens":5}}"""
    }

    private fun json(code: Int, body: String): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    companion object {
        /** The only key the fake accepts: obviously not a real one. */
        const val KEY = "sk-test-not-a-real-key"

        /**
         * What "the AI saw": two foods of the recordings (sure) and one it is unsure about, which
         * the review list must start as skipped (confidence below 0.6).
         */
        const val ITEMS =
            """{"items":[{"name":"mjölk","amount":1,"unit":"l","confidence":0.9,"note":null},""" +
                """{"name":"smör","amount":null,"unit":null,"confidence":0.8,"note":"ett paket"},""" +
                """{"name":"kaviar","amount":null,"unit":null,"confidence":0.3,"note":"kanske"}]}"""

        private val MODEL = Regex("\"model\"\\s*:\\s*\"([^\"]+)\"")

        private const val OK = 200
        private const val UNAUTHORIZED = 401
        private const val NOT_FOUND = 404
    }
}
