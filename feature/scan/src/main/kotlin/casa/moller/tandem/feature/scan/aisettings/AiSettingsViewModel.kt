package casa.moller.tandem.feature.scan.aisettings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.ai.AiSettingsRepository
import casa.moller.tandem.core.data.ai.FridgeVision
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.model.ai.AiKeyCheck
import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.core.model.ai.AiSettings
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.feature.scan.R
import casa.moller.tandem.feature.scan.fridge.customHostOf
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why a key was not stored. */
enum class AiKeyError {
    EMPTY,

    /** Not one line of visible characters (a key never has spaces or line breaks). */
    INVALID,

    /** The phone's secure storage could not save it. */
    STORAGE,
}

/**
 * Settings → AI (B22, EXPERIMENTAL). The key is never part of this state (only [hasKey]): the
 * field's text stays in the screen and reaches the ViewModel once, in [AiSettingsAction.SaveKey].
 *
 * @property model the model field as typed; empty = the provider's default ([defaultModel]).
 * @property baseUrl the own base URL as typed; empty = the provider's official API ([officialBaseUrl]).
 * @property check the answer of "Testa nyckeln" (null = not tested since the last change).
 * @property customHost the host of the SAVED own base URL (the privacy text names it), null for the official API.
 */
data class AiSettingsUiState(
    val loading: Boolean = true,
    val provider: AiProvider? = null,
    val hasKey: Boolean = false,
    val model: String = "",
    val baseUrl: String = "",
    val modelError: Boolean = false,
    val baseUrlError: Boolean = false,
    val advancedChanged: Boolean = false,
    val keyError: AiKeyError? = null,
    val savingKey: Boolean = false,
    val checking: Boolean = false,
    val check: AiKeyCheck? = null,
    val confirmRemove: Boolean = false,
    val warningAcknowledged: Boolean = false,
    val demo: Boolean = false,
    val customHost: String? = null,
) {
    val defaultModel: String?
        get() = provider?.defaultModel

    val officialBaseUrl: String?
        get() = provider?.officialBaseUrl

    val canCheck: Boolean
        get() = provider != null && hasKey && !demo && !checking
}

sealed interface AiSettingsAction {
    data class SelectProvider(val provider: AiProvider?) : AiSettingsAction

    /** "Spara nyckeln": the only time the key reaches the ViewModel; [toString] never shows it. */
    class SaveKey(val key: String) : AiSettingsAction {
        override fun toString(): String = "SaveKey(***)"
    }

    /** The key field was edited (clears an old error). */
    data object KeyEdited : AiSettingsAction

    data object RemoveKey : AiSettingsAction

    data object ConfirmRemoveKey : AiSettingsAction

    data object CancelRemoveKey : AiSettingsAction

    data object CheckKey : AiSettingsAction

    data class EditModel(val text: String) : AiSettingsAction

    data class EditBaseUrl(val text: String) : AiSettingsAction

    /** "Spara" for the model and the base URL. */
    data object SaveAdvanced : AiSettingsAction

    /** "Återställ": the provider's default model and its official API. */
    data object ResetAdvanced : AiSettingsAction
}

sealed interface AiSettingsEffect {
    /** A short confirmation (snackbar). */
    data class Message(@StringRes val text: Int) : AiSettingsEffect

    /** The key was stored: the screen clears its field. */
    data object KeyStored : AiSettingsEffect
}

/**
 * Settings → AI (decision B22): provider (Claude, Gemini, OpenAI or off), the user's own key
 * (stored encrypted on this phone only, write-only), the model (each provider's default, editable),
 * an optional own base URL, and "Testa nyckeln" (a free model lookup, never a photo). Per device,
 * not per Tandoor account. Nothing here is logged.
 */
@HiltViewModel
class AiSettingsViewModel @Inject internal constructor(
    private val settings: AiSettingsRepository,
    private val vision: FridgeVision,
    permissions: SpacePermissionsRepository,
) : ViewModel() {

    private data class Local(
        val model: String = "",
        val baseUrl: String = "",
        val draftsFor: AiProvider? = null,
        val modelError: Boolean = false,
        val baseUrlError: Boolean = false,
        val keyError: AiKeyError? = null,
        val savingKey: Boolean = false,
        val checking: Boolean = false,
        val check: AiKeyCheck? = null,
        val confirmRemove: Boolean = false,
    )

    private val effectChannel = Channel<AiSettingsEffect>(Channel.BUFFERED)
    val effects: Flow<AiSettingsEffect> = effectChannel.receiveAsFlow()

    private val local = MutableStateFlow(Local())
    private var current: AiSettings = AiSettings()

    val uiState: StateFlow<AiSettingsUiState> = combine(settings.settings, permissions.permissions, local) { ai, rights, ui ->
        current = ai
        AiSettingsUiState(
            loading = false,
            provider = ai.provider,
            hasKey = ai.hasKey,
            model = ui.model,
            baseUrl = ui.baseUrl,
            modelError = ui.modelError,
            baseUrlError = ui.baseUrlError,
            advancedChanged = ui.model.trim() != ai.model.orEmpty() || ui.baseUrl.trim().trimEnd('/') != ai.customBaseUrl.orEmpty(),
            keyError = ui.keyError,
            savingKey = ui.savingKey,
            checking = ui.checking,
            check = ui.check,
            confirmRemove = ui.confirmRemove,
            warningAcknowledged = ai.warningAcknowledged,
            demo = rights.isDemo,
            customHost = customHostOf(ai.customBaseUrl),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AiSettingsUiState())

    init {
        // The model and base URL fields follow the chosen provider (each has its own).
        viewModelScope.launch {
            settings.settings.distinctUntilChangedBy { it.provider to (it.model to it.customBaseUrl) }.collect { ai ->
                local.update { ui ->
                    if (ui.draftsFor == ai.provider && (ui.model.isNotEmpty() || ui.baseUrl.isNotEmpty())) {
                        ui
                    } else {
                        ui.copy(
                            draftsFor = ai.provider,
                            model = ai.model.orEmpty(),
                            baseUrl = ai.customBaseUrl.orEmpty(),
                            modelError = false,
                            baseUrlError = false,
                        )
                    }
                }
            }
        }
    }

    fun onAction(action: AiSettingsAction) {
        when (action) {
            is AiSettingsAction.SelectProvider -> launch {
                local.update { it.copy(check = null, keyError = null, draftsFor = null, model = "", baseUrl = "") }
                settings.setProvider(action.provider)
            }

            is AiSettingsAction.SaveKey -> saveKey(action.key)

            AiSettingsAction.KeyEdited -> local.update { it.copy(keyError = null) }

            AiSettingsAction.RemoveKey -> local.update { it.copy(confirmRemove = true) }

            AiSettingsAction.CancelRemoveKey -> local.update { it.copy(confirmRemove = false) }

            AiSettingsAction.ConfirmRemoveKey -> launch {
                local.update { it.copy(confirmRemove = false, check = null) }
                val provider = current.provider ?: return@launch
                if (settings.removeKey(provider) is Outcome.Success) effectChannel.send(AiSettingsEffect.Message(R.string.ai_key_removed))
            }

            AiSettingsAction.CheckKey -> checkKey()

            is AiSettingsAction.EditModel -> local.update {
                it.copy(model = action.text, modelError = false, draftsFor = current.provider)
            }

            is AiSettingsAction.EditBaseUrl -> local.update {
                it.copy(baseUrl = action.text, baseUrlError = false, draftsFor = current.provider)
            }

            AiSettingsAction.SaveAdvanced -> saveAdvanced(local.value.model, local.value.baseUrl)

            AiSettingsAction.ResetAdvanced -> saveAdvanced("", "")
        }
    }

    private fun saveKey(key: String) {
        val provider = current.provider ?: return
        if (local.value.savingKey) return
        local.update { it.copy(savingKey = true, keyError = null, check = null) }
        launch {
            val result = settings.storeKey(provider, key)
            val error = (result as? Outcome.Failure)?.error?.keyError()
            local.update { it.copy(savingKey = false, keyError = error) }
            if (error == null) {
                effectChannel.send(AiSettingsEffect.KeyStored)
                effectChannel.send(AiSettingsEffect.Message(R.string.ai_key_saved))
            }
        }
    }

    private fun saveAdvanced(model: String, baseUrl: String) {
        launch {
            val modelResult = settings.setModel(model.trim().ifEmpty { null })
            val urlResult = settings.setCustomBaseUrl(baseUrl.trim().ifEmpty { null })
            val modelError = modelResult is Outcome.Failure
            val urlError = urlResult is Outcome.Failure
            local.update {
                it.copy(
                    model = if (modelError) model else model.trim(),
                    baseUrl = if (urlError) baseUrl else baseUrl.trim().trimEnd('/'),
                    draftsFor = current.provider,
                    modelError = modelError,
                    baseUrlError = urlError,
                    check = null,
                )
            }
            if (!modelError && !urlError) effectChannel.send(AiSettingsEffect.Message(R.string.ai_advanced_saved))
        }
    }

    private fun checkKey() {
        if (!uiState.value.canCheck) return
        local.update { it.copy(checking = true, check = null) }
        launch {
            val result = vision.checkKey()
            local.update { it.copy(checking = false, check = result) }
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** core:ai's error codes (DefaultAiSettingsRepository) as the field's error. */
private fun TandemError.keyError(): AiKeyError = when ((this as? TandemError.Unknown)?.detail) {
    "tandem.ai.key-empty" -> AiKeyError.EMPTY
    "tandem.ai.key-invalid" -> AiKeyError.INVALID
    else -> AiKeyError.STORAGE
}
