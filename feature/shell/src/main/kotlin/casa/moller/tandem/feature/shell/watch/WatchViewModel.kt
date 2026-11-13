package casa.moller.tandem.feature.shell.watch

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.watch.WatchRepository
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.watch.WatchAvailability
import casa.moller.tandem.core.model.watch.WatchOutcome
import casa.moller.tandem.core.model.watch.WatchResult
import casa.moller.tandem.core.model.watch.WatchState
import casa.moller.tandem.feature.shell.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Mer → Klockan as the screen shows it. [error]: the server's answer to the last action. */
data class WatchUiState(val watch: WatchState = WatchState(), val error: TandemError? = null) {
    val loading: Boolean
        get() = watch.availability == WatchAvailability.CHECKING && watch.paired == null && watch.block == null
}

/** What the user does on Mer → Klockan. */
sealed interface WatchAction {
    data object Pair : WatchAction

    data object Unpair : WatchAction

    /** Look for watches again (the page came back, "Leta igen"). */
    data object Refresh : WatchAction
}

/** One-off results. */
sealed interface WatchEffect {
    data class Message(@StringRes val text: Int) : WatchEffect
}

/**
 * Mer → Klockan (Fas 5, wear lane; plan §7 #33). Pairing and unpairing go through
 * [WatchRepository]; the result is a snackbar, a server error also stays on the page.
 */
@HiltViewModel
class WatchViewModel @Inject constructor(private val watches: WatchRepository) : ViewModel() {
    private val effects = Channel<WatchEffect>(Channel.BUFFERED)
    private val error = MutableStateFlow<TandemError?>(null)

    val effect: Flow<WatchEffect> = effects.receiveAsFlow()

    val uiState: StateFlow<WatchUiState> = combine(watches.state, error) { watch, failure -> WatchUiState(watch, failure) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), WatchUiState())

    init {
        onAction(WatchAction.Refresh)
    }

    fun onAction(action: WatchAction) {
        viewModelScope.launch {
            when (action) {
                WatchAction.Pair -> report(watches.pair())
                WatchAction.Unpair -> report(watches.unpair())
                WatchAction.Refresh -> watches.refresh()
            }
        }
    }

    private suspend fun report(result: WatchResult) {
        error.value = result.error.takeIf { result.outcome == WatchOutcome.SERVER }
        effects.send(WatchEffect.Message(messageOf(result.outcome)))
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** The snackbar after an action. */
@StringRes
internal fun messageOf(outcome: WatchOutcome): Int = when (outcome) {
    WatchOutcome.PAIRED -> R.string.watch_message_paired
    WatchOutcome.UNPAIRED -> R.string.watch_message_unpaired
    WatchOutcome.UNPAIRED_WATCH_AWAY -> R.string.watch_message_unpaired_away
    WatchOutcome.NO_WATCH -> R.string.watch_message_no_watch
    WatchOutcome.WATCH_REFUSED -> R.string.watch_message_refused
    WatchOutcome.SERVER -> R.string.watch_message_server
    WatchOutcome.NOT_ALLOWED -> R.string.watch_message_not_allowed
}
