package casa.moller.tandem.smoke

import java.util.concurrent.CopyOnWriteArrayList
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest

/**
 * Fas S komplettering: fake Gemini and OpenAI endpoints inside [FakeTandoor]'s HTTPS server (the
 * same test CA), reached through Settings → AI's own base URL ([GEMINI_PATH], [OPENAI_PATH] under
 * [FakeTandoor.baseUrl]). They prove that core:ai's REST clients (their own OkHttp client and the
 * kotlinx JSON trees) work in the R8-minified app, as [FakeAnthropic] does for the Anthropic SDK:
 * "Testa nyckeln" (`models.get` / `GET /models/{model}`) and the fridge photo (`generateContent`
 * with `inlineData` and a response schema / `POST /responses` with an `input_image` and a strict
 * JSON schema), both answering [FakeAnthropic.ITEMS] as the model's text. Only the fake key
 * [FakeAnthropic.KEY] is accepted; no real AI service is called. [requests] and [photos] keep what
 * the requests carried, never the photo or the key.
 */
class FakeAiServices {
    enum class Provider { GEMINI, OPENAI }

    /** One request as the server saw it: never the key itself, only whether it was the expected one. */
    data class Seen(
        val provider: Provider,
        val method: String,
        val path: String,
        val keyMatched: Boolean,
        val tandoorHeader: Boolean,
        val cookie: Boolean,
    )

    /** A fridge photo request: the model, whether a JPEG and a JSON schema were in it. */
    data class Photo(val provider: Provider, val model: String?, val hadJpeg: Boolean, val structuredOutput: Boolean)

    val requests: MutableList<Seen> = CopyOnWriteArrayList()
    val photos: MutableList<Photo> = CopyOnWriteArrayList()

    /** Answers [GEMINI_PATH] and [OPENAI_PATH] requests; null for every other path. */
    fun answer(request: RecordedRequest): MockResponse? {
        val path = request.url.encodedPath
        return when {
            path.startsWith("$GEMINI_PATH/") -> gemini(request, path.removePrefix(GEMINI_PATH))
            path.startsWith("$OPENAI_PATH/") -> openAi(request, path.removePrefix(OPENAI_PATH))
            else -> null
        }
    }

    /** Gemini: the key in `x-goog-api-key`, never in the URL or an `Authorization` header. */
    private fun gemini(request: RecordedRequest, path: String): MockResponse {
        val seen = seen(Provider.GEMINI, request, keyMatched = request.headers["x-goog-api-key"] == FakeAnthropic.KEY)
        val generate = GENERATE.find(path)
        return when {
            !seen.keyMatched || seen.tandoorHeader || FakeAnthropic.KEY in request.url.toString() ->
                json(BAD_REQUEST, """{"error":{"code":400,"status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""")

            request.method == "GET" && path.startsWith("/models/") ->
                json(OK, """{"name":"models/${path.removePrefix("/models/")}","displayName":"Fake model"}""")

            request.method == "POST" && generate != null -> {
                val body = request.body?.utf8().orEmpty()
                photos += Photo(
                    provider = Provider.GEMINI,
                    model = generate.groupValues[1],
                    hadJpeg = "inlineData" in body && "image/jpeg" in body,
                    structuredOutput = "responseSchema" in body && "application/json" in body,
                )
                json(OK, """{"candidates":[{"finishReason":"STOP","content":{"role":"model","parts":[{"text":"${escaped()}"}]}}]}""")
            }

            else -> json(NOT_FOUND, """{"error":{"code":404,"status":"NOT_FOUND"}}""")
        }
    }

    /** OpenAI: the key as a Bearer token (the only `Authorization` header that may arrive here). */
    private fun openAi(request: RecordedRequest, path: String): MockResponse {
        val seen = seen(Provider.OPENAI, request, keyMatched = request.headers["Authorization"] == "Bearer ${FakeAnthropic.KEY}")
        return when {
            !seen.keyMatched -> json(UNAUTHORIZED, """{"error":{"type":"invalid_request_error","code":"invalid_api_key"}}""")

            request.method == "GET" && path.startsWith("/models/") ->
                json(OK, """{"id":"${path.removePrefix("/models/")}","object":"model","owned_by":"fake"}""")

            request.method == "POST" && path == "/responses" -> {
                val body = request.body?.utf8().orEmpty()
                photos += Photo(
                    provider = Provider.OPENAI,
                    model = MODEL.find(body)?.groupValues?.get(1),
                    hadJpeg = "input_image" in body && "data:image/jpeg;base64," in body,
                    structuredOutput = "json_schema" in body && "\"strict\":true" in body,
                )
                val message = """{"type":"message","role":"assistant","content":[{"type":"output_text","text":"${escaped()}"}]}"""
                json(OK, """{"status":"completed","output":[$message]}""")
            }

            else -> json(NOT_FOUND, """{"error":{"code":"model_not_found"}}""")
        }
    }

    private fun seen(provider: Provider, request: RecordedRequest, keyMatched: Boolean): Seen {
        val authorization = request.headers["Authorization"]
        val seen = Seen(
            provider = provider,
            method = request.method,
            path = request.url.encodedPath,
            keyMatched = keyMatched,
            // Anything of Tandoor's: its tokens look like `tda_…`; Gemini must get no Authorization at all.
            tandoorHeader = authorization != null && (provider == Provider.GEMINI || "tda_" in authorization),
            cookie = request.headers["Cookie"] != null,
        )
        requests += seen
        return seen
    }

    private fun escaped(): String = FakeAnthropic.ITEMS.replace("\"", "\\\"")

    private fun json(code: Int, body: String): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    companion object {
        /** Settings → AI's own base URL for Gemini is `<FakeTandoor.baseUrl>` + this (like `…/v1beta`). */
        const val GEMINI_PATH = "/gemini/v1beta"

        /** The same for OpenAI (like `…/v1`). */
        const val OPENAI_PATH = "/openai/v1"

        private val GENERATE = Regex("^/models/([^/:]+):generateContent$")
        private val MODEL = Regex("\"model\"\\s*:\\s*\"([^\"]+)\"")
        private const val OK = 200
        private const val BAD_REQUEST = 400
        private const val UNAUTHORIZED = 401
        private const val NOT_FOUND = 404
    }
}
