package casa.moller.tandem.feature.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.inbox.RecipeInboxRepository
import casa.moller.tandem.core.model.inbox.InboxAvailability
import casa.moller.tandem.core.model.inbox.InboxEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Fas 5 (rss lane, plan §7 #35): "Nytt i inkorgen" on Idag.
 *
 * @property available the recipe inbox is set up (or demo mode's): "Anpassa Idag" offers the row.
 * @property shown the row is on ("Visa på Idag", on by default once the inbox is set up).
 * @property entries the newest posts not imported yet, at most [TodayInboxViewModel.MAX_POSTS].
 * @property newCount every post not imported yet.
 */
@Immutable
data class TodayInboxState(
    val available: Boolean = false,
    val shown: Boolean = false,
    val entries: List<InboxEntry> = emptyList(),
    val newCount: Int = 0,
) {
    /** The row is on Idag: on, and something new in it. */
    val visible: Boolean
        get() = available && shown && entries.isNotEmpty()
}

/** [TodayInboxState] from the inbox's stored posts (Room); Idag never asks Miniflux itself. */
@HiltViewModel
class TodayInboxViewModel @Inject constructor(private val inbox: RecipeInboxRepository) : ViewModel() {
    val uiState: StateFlow<TodayInboxState> = combine(inbox.state, inbox.entries) { state, entries ->
        val fresh = entries.filter { !it.imported }
        TodayInboxState(
            available = state.inUse,
            // Demo mode has no settings: its built-in inbox is shown.
            shown = state.availability == InboxAvailability.DEMO || state.settings.showOnToday,
            entries = fresh.take(MAX_POSTS),
            newCount = fresh.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), TodayInboxState())

    /** "Anpassa Idag": the row on or off (the inbox's own setting "Visa på Idag"). */
    fun setShown(shown: Boolean) {
        viewModelScope.launch { inbox.setShowOnToday(shown) }
    }

    companion object {
        /** The posts on Idag. */
        const val MAX_POSTS: Int = 3
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
