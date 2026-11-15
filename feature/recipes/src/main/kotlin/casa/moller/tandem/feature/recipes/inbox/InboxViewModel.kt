package casa.moller.tandem.feature.recipes.inbox

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.inbox.RecipeInboxRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.model.inbox.InboxAvailability
import casa.moller.tandem.core.model.inbox.InboxState
import casa.moller.tandem.feature.recipes.R
import casa.moller.tandem.feature.recipes.navigation.RecipeImportKey
import casa.moller.tandem.feature.recipes.navigation.RecipeInboxKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * "Inkorg för recept" (Fas 5, rss lane, plan §7 #35, §10 "två tryck"): the cached posts of the chosen
 * Miniflux categories from [RecipeInboxRepository] (offline first), refreshed when the screen opens
 * and on pull to refresh. Tap 1 ([InboxAction.Open]) opens a post's preview; tap 2
 * ([InboxAction.Import]) opens the import review (#18) with its address and `autoImport`, so the
 * review is what comes next. The open preview lives in [SavedStateHandle] (it survives a rotation).
 */
@HiltViewModel(assistedFactory = InboxViewModel.Factory::class)
class InboxViewModel @AssistedInject constructor(
    @Assisted key: RecipeInboxKey,
    private val handle: SavedStateHandle,
    private val inbox: RecipeInboxRepository,
    permissions: SpacePermissionsRepository,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(key: RecipeInboxKey): InboxViewModel
    }

    private val effectChannel = Channel<InboxEffect>(Channel.BUFFERED)
    val effects: Flow<InboxEffect> = effectChannel.receiveAsFlow()

    private val previewId: StateFlow<Long?> = handle.getStateFlow(KEY_PREVIEW, key.entryId)
    private val refreshing = MutableStateFlow(false)

    val uiState: StateFlow<InboxUiState> = combine(
        inbox.state,
        inbox.entries,
        permissions.permissions,
        previewId,
        refreshing,
    ) { state, entries, permissions, preview, refreshing ->
        InboxUiState(
            loading = false,
            availability = state.availability,
            inUse = state.inUse,
            entries = entries,
            refreshing = refreshing,
            problem = state.settings.problem.takeIf { state.inUse && !state.isDemoState() },
            lastRefresh = state.settings.lastRefresh,
            readOnly = permissions.readOnly,
            preview = preview?.let { id -> entries.firstOrNull { it.id == id } },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), InboxUiState())

    init {
        // The screen opened: read what is new (offline, the stored posts and the problem stay).
        viewModelScope.launch {
            val state = inbox.state.first { it.availability != InboxAvailability.LOADING }
            if (state.inUse && !state.isDemoState()) refresh()
        }
    }

    fun onAction(action: InboxAction) {
        when (action) {
            InboxAction.Refresh -> viewModelScope.launch { refresh() }

            is InboxAction.Open -> handle[KEY_PREVIEW] = action.id

            InboxAction.ClosePreview -> handle[KEY_PREVIEW] = null

            is InboxAction.Import -> {
                val state = uiState.value
                val entry = state.entries.firstOrNull { it.id == action.id } ?: return
                if (state.readOnly) return
                handle[KEY_PREVIEW] = null
                effectChannel.trySend(
                    InboxEffect.OpenImport(RecipeImportKey(sharedText = entry.url, inboxEntryId = entry.id, autoImport = true)),
                )
            }

            is InboxAction.Dismiss -> {
                handle[KEY_PREVIEW] = null
                viewModelScope.launch {
                    inbox.dismiss(action.id)
                    effectChannel.send(InboxEffect.Message(R.string.inbox_marked_read))
                }
            }

            is InboxAction.OpenInBrowser -> uiState.value.entries.firstOrNull { it.id == action.id }?.let { entry ->
                effectChannel.trySend(InboxEffect.OpenUrl(entry.url))
            }
        }
    }

    private suspend fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        try {
            inbox.refresh(background = false)
        } finally {
            refreshing.value = false
        }
    }

    private companion object {
        const val KEY_PREVIEW = "inbox_preview"
        const val STOP_TIMEOUT_MILLIS = 5_000L

        fun InboxState.isDemoState(): Boolean =
            availability == InboxAvailability.DEMO
    }
}
