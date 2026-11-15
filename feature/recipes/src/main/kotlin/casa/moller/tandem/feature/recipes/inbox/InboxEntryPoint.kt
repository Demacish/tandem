package casa.moller.tandem.feature.recipes.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.inbox.RecipeInboxRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Fas 5 (rss lane, plan §7 #35): whether Recept's top bar shows the inbox, and how many posts are new.
 *
 * @property shown the inbox is set up (or demo mode's built-in one): the button is shown, guests too.
 * @property newCount posts not imported yet, for TalkBack ("Inkorg för recept, 3 nya") and the badge.
 */
data class InboxEntryState(val shown: Boolean = false, val newCount: Int = 0)

/** [InboxEntryState] from the repository (reads only Room and the settings, never the network). */
@HiltViewModel
class InboxEntryViewModel @Inject constructor(inbox: RecipeInboxRepository) : ViewModel() {
    val state: StateFlow<InboxEntryState> = combine(inbox.state, inbox.entries) { state, entries ->
        InboxEntryState(shown = state.inUse, newCount = if (state.inUse) entries.count { !it.imported } else 0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), InboxEntryState())

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
