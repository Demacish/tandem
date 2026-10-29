package casa.moller.tandem.core.ai

import casa.moller.tandem.core.model.ai.AiKeyCheck
import casa.moller.tandem.core.model.ai.FridgeVisionError
import casa.moller.tandem.core.model.ai.FridgeVisionResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Gemini and OpenAI against an in-process fake server: request shape, answers and every failure. */
class RestFridgeVisionClientsTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private val http = AiHttpClient.create(callTimeoutSeconds = 1)
    private val gemini = GeminiFridgeVisionClient(http)
    private val openAi = OpenAiFridgeVisionClient(http)

    private fun geminiRequest() = FridgeVisionRequest(FAKE_KEY, "gemini-2.5-flash-lite", server.base("/v1beta"), PHOTO, "sv")

    private fun openAiRequest() = FridgeVisionRequest(FAKE_KEY, "gpt-5-nano", server.base("/v1"), PHOTO, "sv")

    private fun items(result: FridgeVisionResult) = (result as FridgeVisionResult.Items).items.map { it.name }

    // --- Gemini ---

    @Test
    fun geminiSendsThePhotoInlineWithTheSchemaAndOnlyItsOwnKeyHeader() = runTest {
        server.enqueue(json(200, geminiAnswer("""{\"items\":[{\"name\":\"mjölk\",\"confidence\":0.9}]}""")))

        val result = gemini.recognize(geminiRequest())

        assertEquals(listOf("mjölk"), items(result))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1beta/models/gemini-2.5-flash-lite:generateContent", request.url.encodedPath)
        assertEquals(FAKE_KEY, request.headers["x-goog-api-key"])
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["Cookie"])
        assertFalse(request.url.toString().contains(FAKE_KEY))
        val body = Json.parseToJsonElement(request.text()).jsonObject
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals("image/jpeg", parts[0].jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content)
        val config = body["generationConfig"]!!.jsonObject
        assertEquals("application/json", config["responseMimeType"]!!.jsonPrimitive.content)
        assertTrue(config["responseSchema"] is JsonObject)
        assertTrue(body["systemInstruction"].toString().contains("\\\"sv\\\""))
    }

    @Test
    fun geminiWrongKeyRateLimitBlockedMalformedAndTimeout() = runTest {
        val wrongKey = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.",""" +
            """"status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}"""
        server.enqueue(json(400, wrongKey))
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.InvalidKey), gemini.recognize(geminiRequest()))

        server.enqueue(json(403, """{"error":{"code":403,"status":"PERMISSION_DENIED"}}"""))
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.InvalidKey), gemini.recognize(geminiRequest()))

        server.enqueue(json(429, """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}""", "Retry-After" to "30"))
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.QuotaOrRateLimit(30)), gemini.recognize(geminiRequest()))

        server.enqueue(json(200, """{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.Refusal), gemini.recognize(geminiRequest()))

        server.enqueue(json(200, """{"candidates":[{"finishReason":"SAFETY","content":{"parts":[]}}]}"""))
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.Refusal), gemini.recognize(geminiRequest()))

        server.enqueue(json(200, geminiAnswer("Jag ser mjölk")))
        assertTrue((gemini.recognize(geminiRequest()) as FridgeVisionResult.Failed).error is FridgeVisionError.Unparseable)

        server.enqueue(json(200, "<html>proxy</html>"))
        assertTrue((gemini.recognize(geminiRequest()) as FridgeVisionResult.Failed).error is FridgeVisionError.Unparseable)

        server.enqueue(json(404, """{"error":{"code":404,"message":"models/x is not found","status":"NOT_FOUND"}}"""))
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.ModelUnavailable), gemini.recognize(geminiRequest()))

        server.enqueue(json(503, """{"error":{"code":503}}"""))
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.Network("http-503")), gemini.recognize(geminiRequest()))

        server.enqueue(slow())
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.Network("timeout")), gemini.recognize(geminiRequest()))
    }

    @Test
    fun geminiCutOffAtTheLimitKeepsWhatCameAndSkipsThoughts() = runTest {
        val answer = """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"thinking...","thought":true},""" +
            """{"text":"{\"items\":[{\"name\":\"ost\",\"confidence\":0.7},{\"name\":\"sm"}]}}]}"""
        server.enqueue(json(200, answer))
        val result = gemini.recognize(geminiRequest()) as FridgeVisionResult.Items
        assertEquals(listOf("ost"), result.items.map { it.name })
        assertTrue(result.truncated)
    }

    @Test
    fun geminiKeyCheck() = runTest {
        server.enqueue(json(200, """{"name":"models/gemini-2.5-flash-lite"}"""))
        assertEquals(AiKeyCheck.WORKS, gemini.checkKey(AiKeyRequest(FAKE_KEY, "gemini-2.5-flash-lite", server.base("/v1beta"))))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1beta/models/gemini-2.5-flash-lite", request.url.encodedPath)
        assertEquals(FAKE_KEY, request.headers["x-goog-api-key"])

        server.enqueue(json(400, """{"error":{"details":[{"reason":"API_KEY_INVALID"}]}}"""))
        assertEquals(AiKeyCheck.INVALID_KEY, gemini.checkKey(AiKeyRequest(FAKE_KEY, "m", server.base("/v1beta"))))
        server.enqueue(json(404, """{"error":{"code":404}}"""))
        assertEquals(AiKeyCheck.NO_MODEL_ACCESS, gemini.checkKey(AiKeyRequest(FAKE_KEY, "m", server.base("/v1beta"))))
        server.enqueue(json(429, "{}"))
        assertEquals(AiKeyCheck.RATE_LIMITED, gemini.checkKey(AiKeyRequest(FAKE_KEY, "m", server.base("/v1beta"))))
        server.enqueue(slow())
        assertEquals(AiKeyCheck.NO_NETWORK, gemini.checkKey(AiKeyRequest(FAKE_KEY, "m", server.base("/v1beta"))))
    }

    // --- OpenAI ---

    @Test
    fun openAiSendsADataUrlAStrictSchemaAndABearerKey() = runTest {
        val items = """{\"items\":[{\"name\":\"ägg\",\"amount\":6,\"unit\":\"st\",\"confidence\":0.8,\"note\":null}]}"""
        server.enqueue(json(200, openAiAnswer(items)))

        val result = openAi.recognize(openAiRequest())

        assertEquals(listOf("ägg"), items(result))
        val request = server.takeRequest()
        assertEquals("/v1/responses", request.url.encodedPath)
        assertEquals("Bearer $FAKE_KEY", request.headers["Authorization"])
        assertNull(request.headers["Cookie"])
        val body = Json.parseToJsonElement(request.text()).jsonObject
        assertEquals("gpt-5-nano", body["model"]!!.jsonPrimitive.content)
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
        val format = body["text"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        assertEquals("true", format["strict"]!!.jsonPrimitive.content)
        val image = body["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[1].jsonObject
        assertEquals("input_image", image["type"]!!.jsonPrimitive.content)
        assertTrue(image["image_url"]!!.jsonPrimitive.content.startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun openAiWrongKeyRateLimitRefusalMalformedAndTimeout() = runTest {
        server.enqueue(json(401, """{"error":{"message":"Incorrect API key","type":"invalid_request_error","code":"invalid_api_key"}}"""))
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.InvalidKey), openAi.recognize(openAiRequest()))

        server.enqueue(json(429, """{"error":{"code":"insufficient_quota"}}"""))
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.QuotaOrRateLimit()), openAi.recognize(openAiRequest()))

        val refusal = """{"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"No."}]}]}"""
        server.enqueue(json(200, refusal))
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.Refusal), openAi.recognize(openAiRequest()))

        server.enqueue(json(400, """{"error":{"message":"The model `x` does not exist","code":"model_not_found"}}"""))
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.ModelUnavailable), openAi.recognize(openAiRequest()))

        server.enqueue(json(200, openAiAnswer("not json")))
        assertTrue((openAi.recognize(openAiRequest()) as FridgeVisionResult.Failed).error is FridgeVisionError.Unparseable)

        server.enqueue(slow())
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.Network("timeout")), openAi.recognize(openAiRequest()))
    }

    @Test
    fun openAiIncompleteAtTheLimitIsPartial() = runTest {
        val body = """{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[{"type":"reasoning"},""" +
            """{"type":"message","content":[{"type":"output_text","text":"{\"items\":[{\"name\":\"gurka\",\"confidence\":0.5},{\"na"}]}]}"""
        server.enqueue(json(200, body))
        val result = openAi.recognize(openAiRequest()) as FridgeVisionResult.Items
        assertEquals(listOf("gurka"), result.items.map { it.name })
        assertTrue(result.truncated)
    }

    @Test
    fun openAiKeyCheck() = runTest {
        server.enqueue(json(200, """{"id":"gpt-5-nano","object":"model"}"""))
        assertEquals(AiKeyCheck.WORKS, openAi.checkKey(AiKeyRequest(FAKE_KEY, "gpt-5-nano", server.base("/v1"))))
        val request = server.takeRequest()
        assertEquals("/v1/models/gpt-5-nano", request.url.encodedPath)
        assertEquals("Bearer $FAKE_KEY", request.headers["Authorization"])
        server.enqueue(json(401, "{}"))
        assertEquals(AiKeyCheck.INVALID_KEY, openAi.checkKey(AiKeyRequest(FAKE_KEY, "gpt-5-nano", server.base("/v1"))))
        server.enqueue(json(404, """{"error":{"code":"model_not_found"}}"""))
        assertEquals(AiKeyCheck.NO_MODEL_ACCESS, openAi.checkKey(AiKeyRequest(FAKE_KEY, "x", server.base("/v1"))))
        server.enqueue(json(500, "{}"))
        assertEquals(AiKeyCheck.FAILED, openAi.checkKey(AiKeyRequest(FAKE_KEY, "x", server.base("/v1"))))
    }

    private fun geminiAnswer(text: String) =
        """{"candidates":[{"finishReason":"STOP","content":{"role":"model","parts":[{"text":"$text"}]}}]}"""

    private fun openAiAnswer(text: String) =
        """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"$text"}]}]}"""
}
