package casa.moller.tandem.core.ai

import casa.moller.tandem.core.datastore.AiKeyStore
import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.core.model.ai.AiSettings
import casa.moller.tandem.core.model.ai.FridgeVisionError
import casa.moller.tandem.core.model.ai.FridgeVisionResult
import casa.moller.tandem.core.testing.data.FakeAiSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fridge photo's dispatch (settings, key, provider) and the parsing of the AI's answer. */
class FridgeVisionTest {
    private class MemoryKeys(vararg stored: Pair<AiProvider, String>) : AiKeyStore {
        val keys = mutableMapOf(*stored)
        override val providersWithKey: Flow<Set<AiProvider>> = MutableStateFlow(keys.keys.toSet())

        override suspend fun store(provider: AiProvider, key: String) {
            keys[provider] = key
        }

        override suspend fun remove(provider: AiProvider) {
            keys.remove(provider)
        }

        override suspend fun <T> useKey(provider: AiProvider, block: suspend (key: String) -> T): T? = keys[provider]?.let { block(it) }

        override suspend fun clearAll() = keys.clear()
    }

    private class RecordingClient(override val provider: AiProvider) : FridgeVisionClient {
        val requests = mutableListOf<FridgeVisionRequest>()

        override suspend fun recognize(request: FridgeVisionRequest): FridgeVisionResult {
            requests += request
            return FridgePrompt.parse("""[{"name":"mjölk","amount":1,"unit":"l","confidence":0.9}]""")
        }
    }

    @Test
    fun theChosenProvidersClientGetsTheKeyModelAndPhoto() = runTest {
        val claude = RecordingClient(AiProvider.CLAUDE)
        val vision = DefaultFridgeVision(
            FakeAiSettingsRepository(AiSettings(provider = AiProvider.CLAUDE, hasKey = true)),
            MemoryKeys(AiProvider.CLAUDE to "sk-test-not-a-real-key"),
            setOf(claude, RecordingClient(AiProvider.OPENAI)),
        )

        val result = vision.recognize(ByteArray(10), "sv")

        assertEquals(listOf("mjölk"), (result as FridgeVisionResult.Items).items.map { it.name })
        val request = claude.requests.single()
        assertEquals("claude-opus-5-5", request.model)
        assertFalse(request.toString().contains("sk-test"))
    }

    @Test
    fun withoutAProviderOrKeyNothingIsSent() = runTest {
        val claude = RecordingClient(AiProvider.CLAUDE)
        val noProvider = DefaultFridgeVision(FakeAiSettingsRepository(), MemoryKeys(), setOf(claude))
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.NoKey), noProvider.recognize(ByteArray(1), "sv"))
        val noKey = DefaultFridgeVision(FakeAiSettingsRepository(AiSettings(provider = AiProvider.CLAUDE)), MemoryKeys(), setOf(claude))
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.NoKey), noKey.recognize(ByteArray(1), "sv"))
        assertTrue(claude.requests.isEmpty())
    }

    @Test
    fun theAnswerIsParsedLeniently() {
        val fenced = "```json\n[{\"name\":\" ägg \",\"amount\":6,\"confidence\":1.7},{\"name\":\"\"},{\"amount\":2}]\n```"
        val items = (FridgePrompt.parse(fenced) as FridgeVisionResult.Items).items
        assertEquals(listOf("ägg"), items.map { it.name })
        assertEquals(1.0, items.single().confidence ?: 0.0, 0.0)
        assertTrue(FridgePrompt.parse("Jag ser mjölk.") is FridgeVisionResult.Failed)
        assertEquals(FridgeVisionResult.Items(emptyList()), FridgePrompt.parse("[]"))
    }
}
