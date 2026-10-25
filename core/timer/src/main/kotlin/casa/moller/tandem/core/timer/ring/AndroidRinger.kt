package casa.moller.tandem.core.timer.ring

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import casa.moller.tandem.core.model.timer.CookTimer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * [Ringer] that starts [TimerRingingService] and lets it stop itself.
 *
 * Both directions are start commands: the service answers every command with `startForeground`
 * first and then looks at the engine, stopping itself when nothing rings. A `stopService` from
 * outside could reach the system after `startForegroundService` but before the service's
 * `startForeground` (a timer reached zero and was cancelled or got "+1 min" a moment later),
 * which crashes the app ("did not then call Service.startForeground()").
 */
internal class AndroidRinger @Inject constructor(@ApplicationContext private val context: Context) : Ringer {
    override fun ring(ringing: List<CookTimer>) {
        startService(TimerRingingService.ACTION_RING)
    }

    override fun stop() {
        // Not running in this process (or already gone): nothing to stop, and no service to start for it.
        if (!TimerRingingService.running) return
        if (!startService(TimerRingingService.ACTION_STOP)) {
            // The system refused a new start (background limits); the running service is already in
            // the foreground then, so stopping it from here is safe.
            context.stopService(Intent(context, TimerRingingService::class.java))
        }
    }

    /** True when the command was handed to the service. */
    private fun startService(action: String): Boolean = try {
        ContextCompat.startForegroundService(context, Intent(context, TimerRingingService::class.java).setAction(action))
        true
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException: not from the background after an inexact
        // alarm (play build without exact alarms). The insistent alarm notification still rings.
        false
    } catch (e: SecurityException) {
        // Same outcome: the alarm notification rings on its own.
        false
    }
}
