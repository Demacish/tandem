package casa.moller.tandem.core.timer.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.timer.TimerIntents
import casa.moller.tandem.core.timer.di.TimerEntryPoint
import casa.moller.tandem.core.timer.engine.DefaultTimerController
import casa.moller.tandem.core.timer.store.TimerSnapshot
import kotlinx.coroutines.launch

/**
 * Our own alarms and notification actions (explicit PendingIntents only; not exported): a timer's
 * alarm fired, its auto-silence alarm fired, or the user tapped "+1 min", "Stop", "Stop all",
 * "Pause", "Resume" or "Cancel" in a notification.
 */
internal class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val entryPoint = TimerEntryPoint.from(context)
        val pending = goAsync()
        entryPoint.scope().launch {
            try {
                handle(
                    engine = entryPoint.engine(),
                    action = intent.action,
                    timerId = intent.getLongExtra(TimerIntents.EXTRA_TIMER_ID, NO_ID).takeIf { it != NO_ID },
                    snapshot = TimerSnapshot.decode(intent.getStringExtra(TimerIntents.EXTRA_TIMER_SNAPSHOT)),
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val NO_ID = -1L

        /** What a broadcast does; also called directly by the tests. [snapshot]: the timer a ring alarm was set for. */
        suspend fun handle(engine: DefaultTimerController, action: String?, timerId: Long?, snapshot: CookTimer? = null) {
            when (action) {
                TimerIntents.ACTION_RING -> engine.onAlarm(snapshot?.takeIf { it.id == timerId })
                TimerIntents.ACTION_AUTO_SILENCE -> engine.onAutoSilence()
                TimerIntents.ACTION_STOP_ALL -> engine.stopAllRinging()
                else -> handleTimerAction(engine, action, timerId ?: return)
            }
        }

        private suspend fun handleTimerAction(engine: DefaultTimerController, action: String?, timerId: Long) {
            when (action) {
                TimerIntents.ACTION_PLUS_ONE_MINUTE -> engine.addOneMinute(timerId)
                TimerIntents.ACTION_STOP -> engine.stopRinging(timerId)
                TimerIntents.ACTION_PAUSE -> engine.pause(timerId)
                TimerIntents.ACTION_RESUME -> engine.resume(timerId)
                TimerIntents.ACTION_CANCEL -> engine.cancel(timerId)
            }
        }
    }
}
