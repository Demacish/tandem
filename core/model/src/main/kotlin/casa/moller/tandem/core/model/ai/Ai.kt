package casa.moller.tandem.core.model.ai

// Fas S (grund lane): the fridge photo (#45) through the user's OWN AI key (decision B22). The
// feature is EXPERIMENTAL: the AI can miss items or invent ones that are not there, so its answer
// always becomes a review list and is never saved without the user. The key is stored encrypted
// (Android Keystore AEAD) and leaves the phone only in the request to the chosen provider.

/**
 * The AI provider the user picked (Settings → AI).
 *
 * @property defaultModel the model used until the user changes it (Settings → AI, editable).
 * @property officialBaseUrl the provider's own API; an own base URL (a proxy or a compatible server)
 *   replaces exactly this part of every request URL.
 */
enum class AiProvider(val defaultModel: String, val officialBaseUrl: String) {
    CLAUDE("claude-opus-5-5", "https://api.anthropic.com"),
    GEMINI("gemini-2.5-flash-lite", "https://generativelanguage.googleapis.com/v1beta"),
    OPENAI("gpt-5-nano", "https://api.openai.com/v1"),
}

/**
 * The AI settings as screens see them. The key itself is never part of this (only [hasKey]).
 *
 * @property model the model id, [AiProvider.defaultModel] when the user did not change it.
 * @property customBaseUrl an optional own endpoint (a proxy); null = the provider's official API.
 * @property hasKey a key is stored for [provider].
 * @property warningAcknowledged the user has read the experimental warning (shown before first use).
 */
data class AiSettings(
    val provider: AiProvider? = null,
    val model: String? = null,
    val customBaseUrl: String? = null,
    val hasKey: Boolean = false,
    val warningAcknowledged: Boolean = false,
) {
    /** The model that is used: [model] or the provider's default. */
    val effectiveModel: String?
        get() = model?.takeIf { it.isNotBlank() } ?: provider?.defaultModel

    /** The fridge photo is offered: a provider and a key (it stays hidden without, plan §8.8). */
    val fridgePhotoAvailable: Boolean
        get() = provider != null && hasKey
}

/**
 * One item the AI recognised on the photo.
 *
 * @property confidence 0..1 as the model reported it (clamped), null when it gave none.
 */
data class RecognizedItem(
    val name: String,
    val amount: Double? = null,
    val unit: String? = null,
    val confidence: Double? = null,
    val note: String? = null,
)

/** Why the fridge photo gave no list. No automatic retries: a call costs the user credits (plan 5.4). */
sealed interface FridgeVisionError {
    /** No key stored (the feature should have been hidden). */
    data object NoKey : FridgeVisionError

    /** The provider refused the key (401/403). */
    data object InvalidKey : FridgeVisionError

    /** Quota used up or rate-limited (429); [retryAfterSeconds] when the provider said. */
    data class QuotaOrRateLimit(val retryAfterSeconds: Long? = null) : FridgeVisionError

    /** The model declined to answer (safety refusal). */
    data object Refusal : FridgeVisionError

    /** No connection, timeout or a server error of the provider. */
    data class Network(val detail: String? = null) : FridgeVisionError

    /** The answer was not the expected list. */
    data class Unparseable(val detail: String? = null) : FridgeVisionError

    /** The key works, but the chosen model does not exist or the key has no access to it (404). */
    data object ModelUnavailable : FridgeVisionError

    /** Demo mode: never a network call. */
    data object DemoMode : FridgeVisionError
}

/** The fridge photo's result. */
sealed interface FridgeVisionResult {
    /**
     * @property truncated the answer was cut off at the output limit: [items] is what came before
     *   (the review says the list may be incomplete).
     */
    data class Items(val items: List<RecognizedItem>, val truncated: Boolean = false) : FridgeVisionResult

    data class Failed(val error: FridgeVisionError) : FridgeVisionResult
}

/**
 * "Testa nyckeln" in Settings → AI: a free call (the provider's model lookup) with the stored key,
 * model and base URL. Nothing is generated and no photo is sent.
 */
enum class AiKeyCheck {
    /** The key is accepted and the model is available. */
    WORKS,

    /** The provider refused the key (wrong, revoked or expired). */
    INVALID_KEY,

    /** The key is accepted, but the model does not exist or the key may not use it. */
    NO_MODEL_ACCESS,

    /** Rate-limited or out of credits right now. */
    RATE_LIMITED,

    /** No connection, a timeout or the provider (or the own base URL) did not answer. */
    NO_NETWORK,

    /** No provider chosen or no key stored. */
    NO_KEY,

    /** Demo mode: the app never calls a service. */
    DEMO_MODE,

    /** Any other answer (a server error, an unexpected reply). */
    FAILED,
}
