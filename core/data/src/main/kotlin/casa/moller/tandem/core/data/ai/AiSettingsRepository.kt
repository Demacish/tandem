package casa.moller.tandem.core.data.ai

import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.core.model.ai.AiSettings
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import kotlinx.coroutines.flow.Flow

/**
 * Settings → AI (decision B22, EXPERIMENTAL): the provider (Claude, Gemini or OpenAI), the model,
 * an optional own base URL, whether a key is stored, and whether the experimental warning was
 * acknowledged ("AI:n kan missa varor eller hitta på sådana som inte finns").
 *
 * The key is WRITE-ONLY here: [storeKey] encrypts it (core:datastore `AiKeyStore`, Keystore AEAD);
 * nothing in this interface returns it. Only core:ai reads it, for the request to the provider.
 * The key is never logged, never in diagnostics, crash logs, exports, backups or screenshots.
 * Implemented by the ai-foto lane (settings in DataStore, the key in `AiKeyStore`).
 */
interface AiSettingsRepository {
    val settings: Flow<AiSettings>

    suspend fun setProvider(provider: AiProvider?): Outcome<Unit>

    /** Null or blank = the provider's default model. */
    suspend fun setModel(model: String?): Outcome<Unit>

    /** Null = the provider's official endpoint; otherwise an https URL (validated). */
    suspend fun setCustomBaseUrl(url: String?): Outcome<Unit>

    /** Stores the key for [provider] (trimmed; an empty key fails without storing). */
    suspend fun storeKey(provider: AiProvider, key: String): Outcome<Unit>

    suspend fun removeKey(provider: AiProvider): Outcome<Unit>

    /** The user read the experimental warning (shown before the first fridge photo). */
    suspend fun acknowledgeWarning(): Outcome<Unit>
}

/**
 * Fas S komplettering: why an [AiSettingsRepository] write was refused (`TandemError.Unknown(detail)`),
 * shared by core:ai's implementation, core:testing's fake and the settings screen, so a test on the
 * fake sees the same codes as the app.
 */
enum class AiSettingsProblem(val detail: String) {
    /** [AiSettingsRepository.storeKey] with an empty key. */
    KEY_EMPTY("tandem.ai.key-empty"),

    /** A key that is not one line of visible ASCII. */
    KEY_INVALID("tandem.ai.key-invalid"),

    /** A model name with spaces or control characters. */
    MODEL_INVALID("tandem.ai.model-invalid"),

    /** An own base URL that is not `https://` with a host (or has a user, query or fragment). */
    BASE_URL_INVALID("tandem.ai.base-url-invalid"),

    /** A model or base URL without a chosen provider. */
    NO_PROVIDER("tandem.ai.no-provider"),

    /** The (non-secret) settings could not be written. */
    SAVE_FAILED("tandem.ai.save-failed"),
    ;

    fun toError(): TandemError = TandemError.Unknown(detail)

    companion object {
        /** The [AiSettingsProblem] this error stands for, or null. */
        fun of(error: TandemError): AiSettingsProblem? = entries.firstOrNull { (error as? TandemError.Unknown)?.detail == it.detail }
    }
}
