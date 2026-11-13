package casa.moller.tandem.core.watch

import casa.moller.tandem.core.data.timer.TimerController
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.watchlink.MirroredTimer
import casa.moller.tandem.core.watchlink.MirroredTimerState
import casa.moller.tandem.core.watchlink.TimerAction
import casa.moller.tandem.core.watchlink.TimerActionKind
import casa.moller.tandem.core.watchlink.TimerMirror
import casa.moller.tandem.core.watchlink.WatchCodec
import casa.moller.tandem.core.watchlink.WatchLink
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The phone's cook timers on the paired watch (plan §7 #33, §8.1): every change of the timers
 * (started, paused, ringing, stopped, cleared) is sent to the watch as one [TimerMirror] message;
 * the watch shows them and its notifications follow. "Stoppa" or "Avbryt" on the watch comes back
 * as a [TimerAction] ([act]), so the alarm stops on both. Nothing is sent without a pairing.
 */
@Singleton
internal class WatchTimerMirror @Inject constructor(
    private val timers: TimerController,
    private val dataLayer: DataLayer,
    private val store: WatchPairingStore,
    private val clock: Clock,
) {
    /** Follows the timers and the pairing for as long as [scope] lives. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(store.pairing.map { it?.nodeId }.distinctUntilChanged(), timers.timers) { node, list -> node to list }
                .debounce(DEBOUNCE_MILLIS)
                .collect { (node, list) -> if (node != null) send(node, list) }
        }
    }

    /** The timers now (the watch asks when its app opens). */
    fun mirror(): TimerMirror = mirrorOf(timers.timers.value)

    /** A [WatchLink.PATH_TIMER_ACTION] message from the watch. */
    suspend fun act(bytes: ByteArray?) {
        val action = WatchCodec.decode(TimerAction.serializer(), bytes) ?: return
        when (action.kind) {
            TimerActionKind.STOP_RINGING -> timers.stopRinging(action.id)
            TimerActionKind.CANCEL -> timers.cancel(action.id)
        }
    }

    private suspend fun send(nodeId: String, list: List<CookTimer>) {
        try {
            dataLayer.send(nodeId, WatchLink.PATH_TIMERS, WatchCodec.encode(TimerMirror.serializer(), mirrorOf(list)))
        } catch (e: DataLayerException) {
            // The watch is away: it asks for the timers when its app opens.
        }
    }

    private fun mirrorOf(list: List<CookTimer>): TimerMirror =
        TimerMirror(sentAtEpochMillis = clock.now().toEpochMilliseconds(), timers = list.map(::mirrored))

    companion object {
        private const val DEBOUNCE_MILLIS = 250L

        internal fun mirrored(timer: CookTimer): MirroredTimer = MirroredTimer(
            id = timer.id,
            label = timer.label,
            recipeName = timer.recipeName,
            state = when (timer.state) {
                TimerState.RUNNING -> MirroredTimerState.RUNNING
                TimerState.PAUSED -> MirroredTimerState.PAUSED
                TimerState.RINGING -> MirroredTimerState.RINGING
                TimerState.FINISHED -> MirroredTimerState.FINISHED
            },
            endsAtEpochMillis = timer.endsAt?.toEpochMilliseconds(),
            remainingMillis = timer.pausedRemaining?.inWholeMilliseconds,
            durationMillis = timer.duration.inWholeMilliseconds,
        )
    }
}
