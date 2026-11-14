package casa.moller.tandem.feature.shell.watch

import androidx.compose.runtime.Composable
import casa.moller.tandem.core.data.watch.WatchRepository
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.watch.PairedWatch
import casa.moller.tandem.core.model.watch.WatchAvailability
import casa.moller.tandem.core.model.watch.WatchBlock
import casa.moller.tandem.core.model.watch.WatchOutcome
import casa.moller.tandem.core.model.watch.WatchResult
import casa.moller.tandem.core.model.watch.WatchState
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

// Fas 5 (wear lane): the watch page's states for the shell's screenshot, ATF and UI tests.

private val PAIRED = PairedWatch(
    watchName = "Galaxy Watch6",
    accountId = "account-1",
    accountLabel = "Anna",
    tokenExpires = Instant.parse("2027-02-12T09:30:00Z"),
    pairedAt = Instant.parse("2026-11-14T09:30:00Z"),
)

internal val WATCH_READY = WatchUiState(WatchState(WatchAvailability.READY, watchName = "Galaxy Watch6"))
internal val WATCH_PAIRED = WatchUiState(WatchState(WatchAvailability.READY, watchName = "Galaxy Watch6", paired = PAIRED))
internal val WATCH_PAIRED_AWAY = WatchUiState(WatchState(WatchAvailability.NO_WATCH, paired = PAIRED, pairedForAnotherAccount = true))
internal val WATCH_NO_APP = WatchUiState(WatchState(WatchAvailability.NO_APP, watchName = "Galaxy Watch6"))
internal val WATCH_NO_WATCH = WatchUiState(WatchState(WatchAvailability.NO_WATCH))
internal val WATCH_UNAVAILABLE = WatchUiState(WatchState(WatchAvailability.UNAVAILABLE))
internal val WATCH_BUSY = WatchUiState(WatchState(WatchAvailability.READY, watchName = "Galaxy Watch6", busy = true))
internal val WATCH_DEMO = WatchUiState(WatchState(WatchAvailability.READY, watchName = "Galaxy Watch6", block = WatchBlock.DEMO))
internal val WATCH_GUEST = WatchUiState(WatchState(WatchAvailability.READY, watchName = "Galaxy Watch6", block = WatchBlock.GUEST))
internal val WATCH_SERVER_ERROR = WATCH_READY.copy(error = TandemError.Timeout)

/** [WatchPage] with a fixed state: the row and the stateless screen, no Hilt. */
internal class FixedWatchPage(private val state: WatchUiState = WATCH_PAIRED, private val onAction: (WatchAction) -> Unit = {}) :
    WatchPage {
    @Composable
    override fun MoreRow(selected: Boolean, onOpen: () -> Unit) = WatchMoreRow(state.watch, selected, onOpen)

    @Composable
    override fun Watch(onNavigateBack: () -> Unit) = WatchScreen(state, onAction, onNavigateBack)
}

/** [WatchRepository] for the ViewModel: a fixed state and an answer per action. */
internal class FakeWatchRepository(initial: WatchState = WATCH_READY.watch) : WatchRepository {
    val current = MutableStateFlow(initial)
    var pairResult = WatchResult(WatchOutcome.PAIRED)
    var unpairResult = WatchResult(WatchOutcome.UNPAIRED)
    var refreshes = 0

    override val state: Flow<WatchState> = current

    override suspend fun refresh() {
        refreshes++
    }

    override suspend fun pair(): WatchResult = pairResult

    override suspend fun unpair(): WatchResult = unpairResult
}
