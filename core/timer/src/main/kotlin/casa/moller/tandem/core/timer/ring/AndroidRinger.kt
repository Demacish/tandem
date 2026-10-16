package casa.moller.tandem.core.timer.ring

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import casa.moller.tandem.core.model.timer.CookTimer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** [Ringer] that starts and stops [TimerRingingService]. */
internal class AndroidRinger @Inject constructor(@ApplicationContext private val context: Context) : Ringer {
    override fun ring(ringing: List<CookTimer>) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, TimerRingingService::class.java))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: not from the background after an inexact
            // alarm (play build without exact alarms). The insistent alarm notification still rings.
        } catch (e: SecurityException) {
            // Same outcome: the alarm notification rings on its own.
        }
    }

    override fun stop() {
        context.stopService(Intent(context, TimerRingingService::class.java))
    }
}
