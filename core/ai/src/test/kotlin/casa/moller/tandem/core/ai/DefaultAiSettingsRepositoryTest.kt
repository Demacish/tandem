package casa.moller.tandem.core.ai

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.datastore.AiPreferences
import casa.moller.tandem.core.datastore.SecureStorageException
import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.core.model.ai.AiSettings
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings → AI: per device and per provider, the key write-only and validated. */
class DefaultAiSettingsRepositoryTest {
    private val preferences = MemoryAiPreferences()
    private val keys = MemoryKeys()
    private val repository = DefaultAiSettingsRepository(preferences, keys)

    @Test
    fun aKeyIsStoredTrimmedButNeverPartOfTheSettings() = runTest {
        repository.setProvider(AiProvider.CLAUDE)
        assertEquals(Outcome.Success(Unit), repository.storeKey(AiProvider.CLAUDE, "  $FAKE_KEY\n"))

        val settings = repository.settings.first()
        assertEquals(AiSettings(provider = AiProvider.CLAUDE, hasKey = true), settings)
        assertTrue(settings.fridgePhotoAvailable)
        assertFalse(settings.toString().contains(FAKE_KEY))
        assertEquals(FAKE_KEY, keys.keys[AiProvider.CLAUDE])

        repository.removeKey(AiProvider.CLAUDE)
        assertFalse(repository.settings.first().hasKey)
        assertTrue(keys.keys.isEmpty())
    }

    @Test
    fun emptyOrBrokenKeysAreRefused() = runTest {
        val empty = Outcome.Failure(TandemError.Unknown(DefaultAiSettingsRepository.KEY_EMPTY))
        assertEquals(empty, repository.storeKey(AiProvider.GEMINI, "   "))
        val inner = Outcome.Failure(TandemError.Unknown(DefaultAiSettingsRepository.KEY_INVALID))
        assertEquals(inner, repository.storeKey(AiProvider.GEMINI, "sk-test with space"))
        assertEquals(inner, repository.storeKey(AiProvider.GEMINI, "sk-test\nline"))
        assertEquals(inner, repository.storeKey(AiProvider.GEMINI, "sk-test-å"))
        assertTrue(keys.keys.isEmpty())
    }

    @Test
    fun theKeyHasItsOwnFlagPerProvider() = runTest {
        repository.setProvider(AiProvider.OPENAI)
        repository.storeKey(AiProvider.CLAUDE, FAKE_KEY)
        assertFalse(repository.settings.first().hasKey)
        repository.setProvider(AiProvider.CLAUDE)
        assertTrue(repository.settings.first().hasKey)
    }

    @Test
    fun modelAndBaseUrlArePerProviderAndTheDefaultIsNotStored() = runTest {
        assertEquals(Outcome.Failure(TandemError.Unknown(DefaultAiSettingsRepository.NO_PROVIDER)), repository.setModel("x"))

        repository.setProvider(AiProvider.GEMINI)
        repository.setModel(" gemini-custom ")
        repository.setCustomBaseUrl("https://proxy.example.com/v1beta/")
        assertEquals("gemini-custom", repository.settings.first().effectiveModel)
        assertEquals("https://proxy.example.com/v1beta", repository.settings.first().customBaseUrl)

        repository.setProvider(AiProvider.OPENAI)
        assertEquals("gpt-5-nano", repository.settings.first().effectiveModel)
        assertEquals(null, repository.settings.first().customBaseUrl)

        repository.setProvider(AiProvider.GEMINI)
        repository.setModel("gemini-2.5-flash-lite")
        repository.setCustomBaseUrl("")
        assertEquals(AiPreferences(provider = AiProvider.GEMINI), preferences.state.value)
    }

    @Test
    fun onlyHttpsBaseUrlsWithAHost() = runTest {
        repository.setProvider(AiProvider.CLAUDE)
        val invalid = Outcome.Failure(TandemError.Unknown(DefaultAiSettingsRepository.BASE_URL_INVALID))
        assertEquals(invalid, repository.setCustomBaseUrl("http://proxy.example.com"))
        assertEquals(invalid, repository.setCustomBaseUrl("https://"))
        assertEquals(invalid, repository.setCustomBaseUrl("https://user:secret@proxy.example.com"))
        assertEquals(invalid, repository.setCustomBaseUrl("https://proxy.example.com/?key=1"))
        assertEquals(invalid, repository.setCustomBaseUrl("proxy.example.com"))
        assertEquals(Outcome.Success(Unit), repository.setCustomBaseUrl("https://localhost:8443"))
    }

    @Test
    fun theWarningIsAcknowledgedOnce() = runTest {
        assertFalse(repository.settings.first().warningAcknowledged)
        repository.acknowledgeWarning()
        assertTrue(repository.settings.first().warningAcknowledged)
    }

    @Test
    fun aStoreThatCannotWriteGivesAFailureWithoutTheKey() = runTest {
        val broken = DefaultAiSettingsRepository(
            preferences,
            object : casa.moller.tandem.core.datastore.AiKeyStore by keys {
                override suspend fun store(provider: AiProvider, key: String) = throw SecureStorageException("not available")
            },
        )
        val result = broken.storeKey(AiProvider.CLAUDE, FAKE_KEY)
        assertEquals(Outcome.Failure(DataProblem.SECURE_STORAGE.toError()), result)
        assertFalse(result.toString().contains(FAKE_KEY))
    }
}
