package casa.moller.tandem.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.watchlink.MirroredTimerState
import casa.moller.tandem.core.watchlink.TimerActionKind
import casa.moller.tandem.wear.data.WearState
import casa.moller.tandem.wear.data.WearStateStore
import casa.moller.tandem.wear.shopping.WearList
import casa.moller.tandem.wear.shopping.WearShoppingList
import casa.moller.tandem.wear.sync.SyncScheduler
import casa.moller.tandem.wear.sync.WearGuard
import casa.moller.tandem.wear.sync.WearSync
import casa.moller.tandem.wear.timer.WearTimers
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The watch screen's state (plan §7 #33). A tap only writes the outbox and asks WorkManager to
 * send it ([SyncScheduler.syncNow], network constraint), so a check survives closing the app and
 * reaches the server whenever the watch is online. Opening the app reads the list (at most every
 * [WearGuard.MIN_READ_INTERVAL]) and asks the phone for its timers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearViewModel(
    private val store: WearStateStore,
    private val sync: WearSync,
    private val scheduler: SyncScheduler,
    private val timers: WearTimers,
    private val clock: () -> Long,
    private val notificationsAllowed: () -> Boolean,
    private val locale: () -> Locale = Locale::getDefault,
) : ViewModel() {
    private val notifications = MutableStateFlow(notificationsAllowed())

    /** The wall clock, every second while a timer runs (the countdown), else once. */
    private val ticks = store.state
        .map { state -> state.timers.any { it.state == MirroredTimerState.RUNNING } }
        .distinctUntilChanged()
        .flatMapLatest { running ->
            if (running) {
                flow {
                    while (true) {
                        emit(clock())
                        delay(TICK_MILLIS)
                    }
                }
            } else {
                flowOf(clock())
            }
        }

    val state: StateFlow<WearUiState> = combine(store.state, ticks, notifications) { stored, now, allowed ->
        uiState(stored, now, allowed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), WearUiState())

    /** The screen came to the front: fresh list and timers. */
    fun onStart() {
        notifications.value = notificationsAllowed()
        scheduler.syncNow()
        viewModelScope.launch { timers.refresh() }
    }

    fun onAction(action: WearAction) {
        when (action) {
            is WearAction.Toggle -> viewModelScope.launch {
                sync.setChecked(action.row.ids, !action.row.checked)
                scheduler.syncNow()
            }

            WearAction.Refresh -> scheduler.syncNow(force = true)

            WearAction.NextStore -> viewModelScope.launch {
                val stored = store.load()
                val stores = stored.supermarkets
                if (stores.size > 1) {
                    val index = stores.indexOfFirst { it.id == stored.supermarket?.id }
                    sync.chooseSupermarket(stores[(index + 1).mod(stores.size)].id)
                }
            }

            is WearAction.StopTimer -> viewModelScope.launch { timers.act(action.id, TimerActionKind.STOP_RINGING) }

            WearAction.NotificationsChanged -> {
                notifications.value = notificationsAllowed()
                viewModelScope.launch { timers.restore() }
            }
        }
    }

    private fun uiState(stored: WearState, now: Long, allowed: Boolean): WearUiState {
        val mode = when {
            stored.account == null -> WearMode.NOT_PAIRED
            stored.signedOut -> WearMode.SIGNED_OUT
            else -> WearMode.READY
        }
        return WearUiState(
            mode = mode,
            list = if (mode == WearMode.READY) WearShoppingList.of(stored, now, locale()) else WearList.EMPTY,
            timers = stored.timers,
            nowMillis = now,
            pending = stored.pending.size,
            problem = stored.guard.problem,
            retryAtMillis = stored.guard.pausedUntilMillis.takeIf { it > now },
            lastSyncMillis = stored.lastSyncMillis,
            firstSyncDone = stored.lastSyncMillis != null,
            storeName = stored.supermarket?.name,
            storeCount = stored.supermarkets.size,
            accountLabel = stored.account?.label.orEmpty(),
            askNotifications = stored.timers.isNotEmpty() && !allowed,
        )
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
