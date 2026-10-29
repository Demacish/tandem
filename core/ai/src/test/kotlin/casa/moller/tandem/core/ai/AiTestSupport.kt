package casa.moller.tandem.core.ai

import casa.moller.tandem.core.datastore.AiKeyStore
import casa.moller.tandem.core.datastore.AiPreferences
import casa.moller.tandem.core.datastore.AiPreferencesStore
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.core.model.settings.AppSettings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

/** A fake key: never a real one (tests never call a real AI API). */
internal const val FAKE_KEY = "sk-test-not-a-real-key"

/** A tiny "photo": the clients only base64 it. */
internal val PHOTO = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

internal fun json(code: Int, body: String, vararg headers: Pair<String, String>): MockResponse = MockResponse.Builder()
    .code(code)
    .addHeader("Content-Type", "application/json")
    .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
    .body(body)
    .build()

/** An answer that never comes in time (the client's timeout is shorter). */
internal fun slow(): MockResponse = MockResponse.Builder().code(200).headersDelay(SLOW_SECONDS, TimeUnit.SECONDS).body("{}").build()

internal const val SLOW_SECONDS = 4L

internal fun MockWebServer.base(path: String = ""): String = url(path).toString().trimEnd('/')

internal fun RecordedRequest.text(): String = body?.utf8().orEmpty()

/** In-memory [AiKeyStore]. */
internal class MemoryKeys(vararg stored: Pair<AiProvider, String>) : AiKeyStore {
    val keys = mutableMapOf(*stored)
    private val flow = MutableStateFlow(keys.keys.toSet())
    override val providersWithKey: Flow<Set<AiProvider>> = flow

    override suspend fun store(provider: AiProvider, key: String) {
        keys[provider] = key
        flow.value = keys.keys.toSet()
    }

    override suspend fun remove(provider: AiProvider) {
        keys.remove(provider)
        flow.value = keys.keys.toSet()
    }

    override suspend fun <T> useKey(provider: AiProvider, block: suspend (key: String) -> T): T? = keys[provider]?.let { block(it) }

    override suspend fun clearAll() {
        keys.clear()
        flow.value = emptySet()
    }
}

internal class MemoryAiPreferences(initial: AiPreferences = AiPreferences()) : AiPreferencesStore {
    val state = MutableStateFlow(initial)
    override val preferences: Flow<AiPreferences> = state

    override suspend fun update(transform: (AiPreferences) -> AiPreferences) = state.update(transform)

    override suspend fun clear() {
        state.value = AiPreferences()
    }
}

internal class MemorySettings(demo: Boolean = false) : SettingsStore {
    val state = MutableStateFlow(AppSettings.Default.copy(demoMode = demo))
    override val settings: Flow<AppSettings> = state

    override suspend fun update(transform: (AppSettings) -> AppSettings) = state.update(transform)
}
