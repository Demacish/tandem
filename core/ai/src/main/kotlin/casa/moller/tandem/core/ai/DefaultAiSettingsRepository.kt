package casa.moller.tandem.core.ai

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.ai.AiSettingsRepository
import casa.moller.tandem.core.datastore.AiKeyStore
import casa.moller.tandem.core.datastore.AiPreferences
import casa.moller.tandem.core.datastore.AiPreferencesStore
import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.core.model.ai.AiSettings
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Settings → AI (B22) on this device: the provider, the model and the own base URL per provider
 * ([AiPreferencesStore]) and the key per provider ([AiKeyStore], encrypted). Per device, not per
 * Tandoor account; "Rensa appdata" clears both. The key is WRITE-ONLY: nothing here returns it.
 *
 * A key must be one line of visible ASCII (as every provider's keys are): anything else is refused
 * before it is stored, because an HTTP header with a control character would fail later with an
 * error message that quotes the header value.
 */
@Singleton
internal class DefaultAiSettingsRepository @Inject constructor(
    private val preferences: AiPreferencesStore,
    private val keys: AiKeyStore,
) : AiSettingsRepository {

    override val settings: Flow<AiSettings> = combine(preferences.preferences, keys.providersWithKey) { prefs, withKey ->
        val provider = prefs.provider
        AiSettings(
            provider = provider,
            model = provider?.let { prefs.models[it] },
            customBaseUrl = provider?.let { prefs.baseUrls[it] },
            hasKey = provider != null && provider in withKey,
            warningAcknowledged = prefs.warningAcknowledged,
        )
    }.distinctUntilChanged()

    override suspend fun setProvider(provider: AiProvider?): Outcome<Unit> = update { it.copy(provider = provider) }

    override suspend fun setModel(model: String?): Outcome<Unit> {
        val value = model?.trim().orEmpty()
        if (value.any { it.isWhitespace() || it.isISOControl() }) return Outcome.Failure(TandemError.Unknown(MODEL_INVALID))
        return updateCurrent { prefs, provider ->
            val models = if (value.isEmpty() || value == provider.defaultModel) prefs.models - provider else prefs.models + (provider to value)
            prefs.copy(models = models)
        }
    }

    override suspend fun setCustomBaseUrl(url: String?): Outcome<Unit> {
        val value = url?.trim().orEmpty().trimEnd('/')
        if (value.isNotEmpty() && !isValidBaseUrl(value)) return Outcome.Failure(TandemError.Unknown(BASE_URL_INVALID))
        return updateCurrent { prefs, provider ->
            val urls = if (value.isEmpty() || value == provider.officialBaseUrl) prefs.baseUrls - provider else prefs.baseUrls + (provider to value)
            prefs.copy(baseUrls = urls)
        }
    }

    override suspend fun storeKey(provider: AiProvider, key: String): Outcome<Unit> {
        val value = key.trim()
        if (value.isEmpty()) return Outcome.Failure(TandemError.Unknown(KEY_EMPTY))
        if (!isValidKey(value)) return Outcome.Failure(TandemError.Unknown(KEY_INVALID))
        return secure { keys.store(provider, value) }
    }

    override suspend fun removeKey(provider: AiProvider): Outcome<Unit> = secure { keys.remove(provider) }

    override suspend fun acknowledgeWarning(): Outcome<Unit> = update { it.copy(warningAcknowledged = true) }

    private suspend fun update(transform: (AiPreferences) -> AiPreferences): Outcome<Unit> = try {
        preferences.update(transform)
        Outcome.Success(Unit)
    } catch (ignored: IOException) {
        Outcome.Failure(TandemError.Unknown(SAVE_FAILED))
    }

    private suspend fun updateCurrent(transform: (AiPreferences, AiProvider) -> AiPreferences): Outcome<Unit> {
        var missing = false
        val result = update { prefs ->
            val provider = prefs.provider
            if (provider == null) {
                missing = true
                prefs
            } else {
                transform(prefs, provider)
            }
        }
        return if (missing) Outcome.Failure(TandemError.Unknown(NO_PROVIDER)) else result
    }

    private suspend fun secure(block: suspend () -> Unit): Outcome<Unit> = try {
        block()
        Outcome.Success(Unit)
    } catch (ignored: IOException) { // SecureStorageException; its message never carries the key
        Outcome.Failure(DataProblem.SECURE_STORAGE.toError())
    }

    companion object {
        const val KEY_EMPTY = "tandem.ai.key-empty"
        const val KEY_INVALID = "tandem.ai.key-invalid"
        const val MODEL_INVALID = "tandem.ai.model-invalid"
        const val BASE_URL_INVALID = "tandem.ai.base-url-invalid"
        const val NO_PROVIDER = "tandem.ai.no-provider"
        const val SAVE_FAILED = "tandem.ai.save-failed"

        private const val MAX_KEY_LENGTH = 512
        private const val FIRST_VISIBLE_ASCII = '!'
        private const val LAST_VISIBLE_ASCII = '~'

        /** One line of visible ASCII, no spaces (every provider's key looks like this). */
        fun isValidKey(key: String): Boolean = key.length <= MAX_KEY_LENGTH && key.all { it in FIRST_VISIBLE_ASCII..LAST_VISIBLE_ASCII }

        /** An `https://` URL with a host, no user info, query or fragment (the key must never go over plain HTTP). */
        fun isValidBaseUrl(url: String): Boolean = try {
            val uri = URI(url)
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null
        } catch (ignored: URISyntaxException) {
            false
        }
    }
}
