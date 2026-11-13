package casa.moller.tandem.wear.timer

import casa.moller.tandem.core.watchlink.MirroredTimer
import casa.moller.tandem.core.watchlink.MirroredTimerState
import casa.moller.tandem.core.watchlink.TimerAction
import casa.moller.tandem.core.watchlink.TimerActionKind
import casa.moller.tandem.core.watchlink.TimerMirror
import casa.moller.tandem.core.watchlink.WatchCodec
import casa.moller.tandem.wear.data.WearStateStore
import casa.moller.tandem.wear.link.PhoneLink

/**
 * The phone's cook timers on the watch (plan §7 #33, §8.1): mirrored over the Data Layer, shown in
 * the app, on the tile and as notifications (an ongoing activity with the time left, an alarm when
 * it rings). Only the phone runs timers; the watch never starts one. "Stoppa" or "Avbryt" on the
 * watch is sent to the phone, which stops the alarm on both (its next mirror confirms it); the
 * watch shows the change at once.
 */
class WearTimers(
    private val store: WearStateStore,
    private val phone: PhoneLink,
    private val notifier: TimerNotifier,
    private val onChanged: () -> Unit,
) {
    /** A [casa.moller.tandem.core.watchlink.WatchLink.PATH_TIMERS] message from the phone. */
    suspend fun onMirror(bytes: ByteArray?) {
        WatchCodec.decode(TimerMirror.serializer(), bytes)?.let { apply(it) }
    }

    /** Asks the phone for its timers (the app opened); false when no phone answered. */
    suspend fun refresh(): Boolean {
        val mirror = phone.requestTimers() ?: return false
        apply(mirror)
        return true
    }

    /** "Stoppa" (a ringing timer) or "Avbryt" (any other) on the watch. */
    suspend fun act(id: Long, kind: TimerActionKind): Boolean {
        val sent = phone.sendTimerAction(TimerAction(id, kind))
        val after = store.update { state ->
            val timers = when (kind) {
                TimerActionKind.STOP_RINGING -> state.timers.map {
                    if (it.id == id && it.state == MirroredTimerState.RINGING) it.copy(state = MirroredTimerState.FINISHED) else it
                }

                TimerActionKind.CANCEL -> state.timers.filterNot { it.id == id }
            }
            state.copy(timers = timers)
        }
        notifier.show(after.timers)
        onChanged()
        return sent
    }

    /** Shows the stored timers again (process start: the notifications follow the file). */
    suspend fun restore() {
        notifier.show(store.load().timers)
    }

    private suspend fun apply(mirror: TimerMirror) {
        var applied = false
        val after = store.update { state ->
            // Messages can overtake each other: an older picture never replaces a newer one.
            val newer = state.timersReceivedMillis?.let { mirror.sentAtEpochMillis >= it } ?: true
            if (!newer) return@update state
            applied = true
            state.copy(timers = mirror.timers.filter(::isShown), timersReceivedMillis = mirror.sentAtEpochMillis)
        }
        if (applied) {
            notifier.show(after.timers)
            onChanged()
        }
    }

    private fun isShown(timer: MirroredTimer): Boolean = timer.label.isNotBlank()
}

/** Posts and removes the watch's timer notifications. */
interface TimerNotifier {
    /** Makes the notifications match [timers]: ongoing for running/paused, an alarm for ringing, nothing else. */
    fun show(timers: List<MirroredTimer>)
}
