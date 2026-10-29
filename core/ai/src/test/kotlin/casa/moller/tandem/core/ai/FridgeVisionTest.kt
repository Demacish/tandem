package casa.moller.tandem.core.ai

import casa.moller.tandem.core.model.ai.AiKeyCheck
import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.core.model.ai.AiSettings
import casa.moller.tandem.core.model.ai.FridgeVisionError
import casa.moller.tandem.core.model.ai.FridgeVisionResult
import casa.moller.tandem.core.testing.data.FakeAiSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fridge photo's dispatch: settings, key, provider, demo mode and the size check. */
class FridgeVisionTest {
    private class RecordingClient(override val provider: AiProvider) : FridgeVisionClient {
        val requests = mutableListOf<FridgeVisionRequest>()
        val checks = mutableListOf<AiKeyRequest>()

        override suspend fun recognize(request: FridgeVisionRequest): FridgeVisionResult {
            requests += request
            return FridgePrompt.parse("""{"items":[{"name":"mjölk","amount":1,"unit":"l","confidence":0.9}]}""")
        }

        override suspend fun checkKey(request: AiKeyRequest): AiKeyCheck {
            checks += request
            return AiKeyCheck.WORKS
        }
    }

    private val claude = RecordingClient(AiProvider.CLAUDE)
    private val openAi = RecordingClient(AiProvider.OPENAI)

    private fun vision(settings: AiSettings, keys: MemoryKeys = MemoryKeys(AiProvider.CLAUDE to FAKE_KEY), demo: Boolean = false) =
        DefaultFridgeVision(FakeAiSettingsRepository(settings), keys, MemorySettings(demo), setOf(claude, openAi))

    @Test
    fun theChosenProvidersClientGetsTheKeyModelBaseUrlAndPhoto() = runTest {
        val settings = AiSettings(provider = AiProvider.CLAUDE, customBaseUrl = "https://proxy.example.com", hasKey = true)

        val result = vision(settings).recognize(ByteArray(10), "sv")

        assertEquals(listOf("mjölk"), (result as FridgeVisionResult.Items).items.map { it.name })
        val request = claude.requests.single()
        assertEquals("claude-opus-5-5", request.model)
        assertEquals(FAKE_KEY, request.key)
        assertEquals("https://proxy.example.com", request.baseUrl)
        assertFalse(request.toString().contains("sk-test"))
        assertTrue(openAi.requests.isEmpty())
    }

    @Test
    fun withoutAProviderOrKeyNothingIsSent() = runTest {
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.NoKey), vision(AiSettings()).recognize(ByteArray(1), "sv"))
        assertEquals(
            FridgeVisionResult.Failed(FridgeVisionError.NoKey),
            vision(AiSettings(provider = AiProvider.CLAUDE), MemoryKeys()).recognize(ByteArray(1), "sv"),
        )
        assertEquals(AiKeyCheck.NO_KEY, vision(AiSettings(provider = AiProvider.CLAUDE), MemoryKeys()).checkKey())
        assertTrue(claude.requests.isEmpty())
        assertTrue(claude.checks.isEmpty())
    }

    @Test
    fun demoModeNeverCallsAnyone() = runTest {
        val demo = vision(AiSettings(provider = AiProvider.CLAUDE, hasKey = true), demo = true)
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.DemoMode), demo.recognize(ByteArray(10), "sv"))
        assertEquals(AiKeyCheck.DEMO_MODE, demo.checkKey())
        assertTrue(claude.requests.isEmpty())
        assertTrue(claude.checks.isEmpty())
    }

    @Test
    fun anUnscaledOrEmptyPhotoIsRefusedWithoutARequest() = runTest {
        val vision = vision(AiSettings(provider = AiProvider.CLAUDE, hasKey = true))
        assertTrue((vision.recognize(ByteArray(0), "sv") as FridgeVisionResult.Failed).error is FridgeVisionError.Unparseable)
        val tooBig = vision.recognize(ByteArray(FridgeVisionRequest.MAX_BYTES + 1), "sv") as FridgeVisionResult.Failed
        assertTrue(tooBig.error is FridgeVisionError.Unparseable)
        assertTrue(claude.requests.isEmpty())
    }

    @Test
    fun theKeyCheckUsesTheChosenModel() = runTest {
        val settings = AiSettings(provider = AiProvider.OPENAI, model = "gpt-custom", hasKey = true)
        val vision = vision(settings, MemoryKeys(AiProvider.OPENAI to FAKE_KEY))
        assertEquals(AiKeyCheck.WORKS, vision.checkKey())
        assertEquals("gpt-custom", openAi.checks.single().model)
        assertFalse(openAi.checks.single().toString().contains(FAKE_KEY))
    }
}
