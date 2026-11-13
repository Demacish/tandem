package casa.moller.tandem.wear.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import casa.moller.tandem.core.watchlink.TimerActionKind
import casa.moller.tandem.wear.TandemWearApplication
import kotlinx.coroutines.launch

/** "Stoppa" / "Avbryt" on a timer notification, or the alarm swiped away: tell the phone. */
class TimerActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_TIMER, -1L).takeIf { it >= 0L } ?: return
        val kind = TimerActionKind.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_KIND) } ?: return
        val graph = (context.applicationContext as? TandemWearApplication)?.graph ?: return
        val pending = goAsync()
        graph.scope.launch {
            try {
                graph.timers.act(id, kind)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val EXTRA_TIMER = "casa.moller.tandem.wear.TIMER"
        private const val EXTRA_KIND = "casa.moller.tandem.wear.KIND"

        fun intent(context: Context, timerId: Long, kind: TimerActionKind): Intent = Intent(context, TimerActionReceiver::class.java)
            .putExtra(EXTRA_TIMER, timerId)
            .putExtra(EXTRA_KIND, kind.name)
    }
}
