package casa.moller.tandem.core.timer.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import casa.moller.tandem.core.timer.di.TimerEntryPoint
import casa.moller.tandem.core.timer.engine.SystemEvent
import kotlinx.coroutines.launch

/**
 * System events that clear or move alarms (all protected broadcasts): reboot (alarms are gone),
 * app update, a manual clock or time zone change, and the play build's exact-alarm permission
 * being granted or revoked. Anything else is ignored.
 */
internal class TimerSystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = eventFor(intent.action) ?: return
        val entryPoint = TimerEntryPoint.from(context)
        val pending = goAsync()
        entryPoint.scope().launch {
            try {
                entryPoint.engine().onSystemEvent(event)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun eventFor(action: String?): SystemEvent? = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> SystemEvent.BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> SystemEvent.PACKAGE_REPLACED
            Intent.ACTION_TIME_CHANGED -> SystemEvent.CLOCK_CHANGED
            Intent.ACTION_TIMEZONE_CHANGED -> SystemEvent.TIME_ZONE_CHANGED
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> SystemEvent.EXACT_ALARM_PERMISSION_CHANGED
            else -> null
        }
    }
}
