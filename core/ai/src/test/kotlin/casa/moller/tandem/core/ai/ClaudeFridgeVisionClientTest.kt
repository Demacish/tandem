package casa.moller.tandem.core.ai

import casa.moller.tandem.core.model.ai.AiKeyCheck
import casa.moller.tandem.core.model.ai.FridgeVisionError
import casa.moller.tandem.core.model.ai.FridgeVisionResult
import java.time.Duration
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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

/**
 * Claude through the official Java SDK against an in-process fake Anthropic endpoint (the custom
 * base URL): the request the SDK sends (model, effort, schema, fallbacks, no thinking parameter)
 * and every answer the app must understand.
 */
class ClaudeFridgeVisionClientTest {
    private val server = MockWebServer()
    private val claude = ClaudeFridgeVisionClient(Duration.ofSeconds(1))

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun request() = FridgeVisionRequest(FAKE_KEY, "claude-opus-5-5", server.base(), PHOTO, "sv")

    @Test
    fun theSdkSendsTheImageSchemaEffortAndFallbacks() = runTest {
        val items = """{\"items\":[{\"name\":\"mjölk\",\"amount\":1,\"unit\":\"l\",\"confidence\":0.9,\"note\":null}]}"""
        server.enqueue(json(200, message(text = items)))

        val result = claude.recognize(request()) as FridgeVisionResult.Items

        assertEquals(listOf("mjölk"), result.items.map { it.name })
        assertFalse(result.truncated)
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/v1/messages", sent.url.encodedPath)
        assertEquals(FAKE_KEY, sent.headers["x-api-key"])
        assertNull(sent.headers["Authorization"])
        assertNull(sent.headers["Cookie"])
        assertTrue(sent.headers.values("anthropic-beta").joinToString(",").contains("server-side-fallback-2026-07-01"))
        val body = Json.parseToJsonElement(sent.text()).jsonObject
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("16000", body["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        // Adaptive thinking is the model's default: never disabled, never a budget.
        assertNull(body["thinking"])
        val output = body["output_config"]!!.jsonObject
        assertEquals("medium", output["effort"]!!.jsonPrimitive.content)
        assertEquals("json_schema", output["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        val image = content[0].jsonObject
        assertEquals("image", image["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", image["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content)
        assertTrue(body["system"].toString().contains("\\\"sv\\\""))
    }

    @Test
    fun wrongKeyRateLimitOverloadedAndModel() = runTest {
        server.enqueue(json(401, error("authentication_error")))
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.InvalidKey), claude.recognize(request()))

        server.enqueue(json(429, error("rate_limit_error"), "retry-after" to "20"))
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.QuotaOrRateLimit(20)), claude.recognize(request()))

        server.enqueue(json(529, error("overloaded_error")))
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.QuotaOrRateLimit()), claude.recognize(request()))

        server.enqueue(json(404, error("not_found_error")))
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.ModelUnavailable), claude.recognize(request()))
    }

    @Test
    fun aRefusalIsFriendlyAndACutOffAnswerIsPartial() = runTest {
        server.enqueue(json(200, message(text = null, stopReason = "refusal")))
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.Refusal), claude.recognize(request()))

        val cut = """{\"items\":[{\"name\":\"ost\",\"confidence\":0.6},{\"name\":\"gr"""
        server.enqueue(json(200, message(text = cut, stopReason = "max_tokens")))
        val partial = claude.recognize(request()) as FridgeVisionResult.Items
        assertEquals(listOf("ost"), partial.items.map { it.name })
        assertTrue(partial.truncated)
    }

    @Test
    fun malformedAnswersAndTimeout() = runTest {
        server.enqueue(json(200, message(text = "Jag ser en kyl.")))
        assertTrue((claude.recognize(request()) as FridgeVisionResult.Failed).error is FridgeVisionError.Unparseable)

        server.enqueue(slow())
        assertTrue((claude.recognize(request()) as FridgeVisionResult.Failed).error is FridgeVisionError.Network)
    }

    @Test
    fun theKeyCheckRetrievesTheModel() = runTest {
        val model = """{"type":"model","id":"claude-opus-5-5","display_name":"Claude Opus 5.5","created_at":"2026-01-01T00:00:00Z"}"""
        server.enqueue(json(200, model))
        assertEquals(AiKeyCheck.WORKS, claude.checkKey(AiKeyRequest(FAKE_KEY, "claude-opus-5-5", server.base())))
        val sent = server.takeRequest()
        assertEquals("GET", sent.method)
        assertEquals("/v1/models/claude-opus-5-5", sent.url.encodedPath)
        assertEquals(FAKE_KEY, sent.headers["x-api-key"])

        server.enqueue(json(401, error("authentication_error")))
        assertEquals(AiKeyCheck.INVALID_KEY, claude.checkKey(AiKeyRequest(FAKE_KEY, "claude-opus-5-5", server.base())))
        server.enqueue(json(404, error("not_found_error")))
        assertEquals(AiKeyCheck.NO_MODEL_ACCESS, claude.checkKey(AiKeyRequest(FAKE_KEY, "x", server.base())))
        server.enqueue(json(429, error("rate_limit_error")))
        assertEquals(AiKeyCheck.RATE_LIMITED, claude.checkKey(AiKeyRequest(FAKE_KEY, "x", server.base())))
        server.enqueue(slow())
        assertEquals(AiKeyCheck.NO_NETWORK, claude.checkKey(AiKeyRequest(FAKE_KEY, "x", server.base())))
    }

    private fun error(type: String) = """{"type":"error","error":{"type":"$type","message":"fake"}}"""

    /** A Messages API answer: a thinking block (skipped) and the text, or no content (a refusal). */
    private fun message(text: String?, stopReason: String = "end_turn"): String {
        val content = if (text == null) {
            "[]"
        } else {
            """[{"type":"thinking","thinking":"","signature":"fake-signature"},{"type":"text","text":"$text"}]"""
        }
        return """{"id":"msg_fake","type":"message","role":"assistant","model":"claude-opus-5-5","content":$content,""" +
            """"stop_reason":"$stopReason","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}"""
    }
}
