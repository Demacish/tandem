package casa.moller.tandem.feature.recipes.inbox

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.data.inbox.InboxFailure
import casa.moller.tandem.core.data.inbox.RecipeInboxRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.inbox.InboxAvailability
import casa.moller.tandem.core.model.inbox.InboxCategory
import casa.moller.tandem.core.model.inbox.InboxProblem
import casa.moller.tandem.core.model.inbox.InboxSettings
import casa.moller.tandem.feature.recipes.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the settings page is waiting for. */
enum class InboxSettingsBusy { CONNECT, CATEGORIES, SAVE, DISCONNECT }

/**
 * What [InboxSettingsScreen] shows.
 *
 * @property address the Miniflux address being typed (kept across process death).
 * @property apiKey the API key being typed: in memory only, never in the saved state (a Bundle the
 *   system writes to disk), cleared once the server took it.
 * @property connectProblem why the last "Anslut" failed.
 * @property serverCategories the server's categories with unread counts, null until loaded.
 * @property categoriesProblem why loading them failed.
 * @property selected the categories ticked on the page (saved with "Spara kategorier").
 */
data class InboxSettingsUiState(
    val loading: Boolean = true,
    val availability: InboxAvailability = InboxAvailability.LOADING,
    val settings: InboxSettings = InboxSettings(),
    val address: String = "",
    val apiKey: String = "",
    val busy: InboxSettingsBusy? = null,
    val connectProblem: InboxProblem? = null,
    val secureStorageFailed: Boolean = false,
    val serverCategories: List<InboxCategory>? = null,
    val categoriesProblem: InboxProblem? = null,
    val selected: Set<Long> = emptySet(),
    val confirmDisconnect: Boolean = false,
) {
    /** The categories to tick: the server's, or (before they load or when they cannot) the chosen ones. */
    val categoryChoices: List<InboxCategory>
        get() = serverCategories ?: settings.categories

    /** "Spara kategorier" has something to save. */
    val selectionChanged: Boolean
        get() = selected != settings.categories.mapTo(HashSet()) { it.id }

    val canConnect: Boolean
        get() = busy == null && address.isNotBlank() && apiKey.isNotBlank()
}

sealed interface InboxSettingsAction {
    data class SetAddress(val text: String) : InboxSettingsAction

    data class SetApiKey(val text: String) : InboxSettingsAction

    data object Connect : InboxSettingsAction

    data class ToggleCategory(val id: Long, val chosen: Boolean) : InboxSettingsAction

    data object SaveCategories : InboxSettingsAction

    data object RetryCategories : InboxSettingsAction

    data class SetMarkRead(val enabled: Boolean) : InboxSettingsAction

    data class SetShowOnToday(val enabled: Boolean) : InboxSettingsAction

    data object AskDisconnect : InboxSettingsAction

    data object ConfirmDisconnect : InboxSettingsAction

    data object DismissDialog : InboxSettingsAction

    data object OpenInbox : InboxSettingsAction
}

sealed interface InboxSettingsEffect {
    data class Message(@StringRes val text: Int) : InboxSettingsEffect

    data object OpenInbox : InboxSettingsEffect
}

/**
 * Mer → "Inkorg för recept" (Fas 5, rss lane, plan §7 #35, §4.5, §5.5): connect a Miniflux server
 * (address + API key, checked against the server before anything is stored), choose its categories,
 * the two switches, and "Koppla från". Off until the user sets it up; nothing server-specific is built
 * in. The key never goes into the saved state, a log or the screen after it is stored.
 */
@HiltViewModel
class InboxSettingsViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val inbox: RecipeInboxRepository,
) : ViewModel() {
    private data class Local(
        val apiKey: String = "",
        val busy: InboxSettingsBusy? = null,
        val connectProblem: InboxProblem? = null,
        val secureStorageFailed: Boolean = false,
        val serverCategories: List<InboxCategory>? = null,
        val categoriesProblem: InboxProblem? = null,
        val selected: Set<Long>? = null,
        val confirmDisconnect: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private val address: StateFlow<String> = handle.getStateFlow(KEY_ADDRESS, "")
    private val effectChannel = Channel<InboxSettingsEffect>(Channel.BUFFERED)
    val effects: Flow<InboxSettingsEffect> = effectChannel.receiveAsFlow()

    val uiState: StateFlow<InboxSettingsUiState> = combine(inbox.state, local, address) { state, local, address ->
        InboxSettingsUiState(
            loading = state.availability == InboxAvailability.LOADING,
            availability = state.availability,
            settings = state.settings,
            address = address,
            apiKey = local.apiKey,
            busy = local.busy,
            connectProblem = local.connectProblem,
            secureStorageFailed = local.secureStorageFailed,
            serverCategories = local.serverCategories,
            categoriesProblem = local.categoriesProblem,
            selected = local.selected ?: state.settings.categories.mapTo(HashSet()) { it.id },
            confirmDisconnect = local.confirmDisconnect,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), InboxSettingsUiState())

    init {
        // Connected already: the server's categories with their counts, once per page.
        viewModelScope.launch {
            inbox.state.map { it.availability == InboxAvailability.AVAILABLE && it.settings.connected }
                .distinctUntilChanged()
                .collect { connected -> if (connected && local.value.serverCategories == null) loadCategories() }
        }
    }

    fun onAction(action: InboxSettingsAction) {
        when (action) {
            is InboxSettingsAction.SetAddress -> handle[KEY_ADDRESS] = action.text

            is InboxSettingsAction.SetApiKey -> local.update { it.copy(apiKey = action.text, connectProblem = null) }

            InboxSettingsAction.Connect -> connect()

            is InboxSettingsAction.ToggleCategory -> local.update { current ->
                val selected = current.selected ?: uiState.value.selected
                current.copy(selected = if (action.chosen) selected + action.id else selected - action.id)
            }

            InboxSettingsAction.SaveCategories -> saveCategories()

            InboxSettingsAction.RetryCategories -> viewModelScope.launch { loadCategories() }

            is InboxSettingsAction.SetMarkRead -> viewModelScope.launch { inbox.setMarkReadOnImport(action.enabled) }

            is InboxSettingsAction.SetShowOnToday -> viewModelScope.launch { inbox.setShowOnToday(action.enabled) }

            InboxSettingsAction.AskDisconnect -> local.update { it.copy(confirmDisconnect = true) }

            InboxSettingsAction.DismissDialog -> local.update { it.copy(confirmDisconnect = false) }

            InboxSettingsAction.ConfirmDisconnect -> disconnect()

            InboxSettingsAction.OpenInbox -> effectChannel.trySend(InboxSettingsEffect.OpenInbox)
        }
    }

    private fun connect() {
        val state = uiState.value
        if (!state.canConnect) return
        local.update { it.copy(busy = InboxSettingsBusy.CONNECT, connectProblem = null, secureStorageFailed = false) }
        viewModelScope.launch {
            when (val answer = inbox.connect(state.address, state.apiKey)) {
                is Outcome.Success -> {
                    val chosen = inbox.state.first().settings.categories
                    local.update {
                        it.copy(
                            apiKey = "",
                            busy = null,
                            serverCategories = answer.value,
                            categoriesProblem = null,
                            selected = chosen.mapTo(HashSet()) { c -> c.id },
                        )
                    }
                }

                is Outcome.Failure -> local.update {
                    it.copy(
                        busy = null,
                        connectProblem = InboxFailure.problemOf(answer.error),
                        secureStorageFailed = answer.error.dataProblem() == DataProblem.SECURE_STORAGE,
                    )
                }
            }
        }
    }

    private suspend fun loadCategories() {
        local.update { it.copy(busy = it.busy ?: InboxSettingsBusy.CATEGORIES, categoriesProblem = null) }
        val answer = inbox.serverCategories()
        local.update { current ->
            val busy = current.busy.takeUnless { it == InboxSettingsBusy.CATEGORIES }
            when (answer) {
                is Outcome.Success -> current.copy(busy = busy, serverCategories = answer.value)
                is Outcome.Failure -> current.copy(busy = busy, categoriesProblem = InboxFailure.problemOf(answer.error))
            }
        }
    }

    private fun saveCategories() {
        val state = uiState.value
        if (state.busy != null) return
        val chosen = state.categoryChoices.filter { it.id in state.selected }
        local.update { it.copy(busy = InboxSettingsBusy.SAVE) }
        viewModelScope.launch {
            inbox.chooseCategories(chosen)
            local.update { it.copy(busy = null, selected = null) }
            effectChannel.send(InboxSettingsEffect.Message(R.string.inbox_settings_categories_saved))
        }
    }

    private fun disconnect() {
        local.update { it.copy(confirmDisconnect = false, busy = InboxSettingsBusy.DISCONNECT) }
        viewModelScope.launch {
            inbox.disconnect()
            local.value = Local()
        }
    }

    private companion object {
        const val KEY_ADDRESS = "inbox_settings_address"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
