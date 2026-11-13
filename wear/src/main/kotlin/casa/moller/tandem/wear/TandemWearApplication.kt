package casa.moller.tandem.wear

import android.app.Application
import androidx.wear.phone.interactions.notifications.BridgingConfig
import androidx.wear.phone.interactions.notifications.BridgingManager
import casa.moller.tandem.core.model.notification.TandemNotificationIds
import kotlinx.coroutines.launch

/**
 * Tandem on the watch (Fas 5, wear lane, plan §7 #33). Owns the [WearGraph]; at start it keeps
 * the phone's timer notifications off the watch (this app shows the timers itself, every other
 * notification of the phone is still bridged) and puts the stored timers back on the watch face.
 */
class TandemWearApplication : Application() {
    lateinit var graph: WearGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = WearGraph(this)
        // Throws on a device that is not a watch, or when the system's bridging service is missing
        // (then the phone's timer notifications are bridged as before, nothing else changes).
        runCatching {
            BridgingManager.fromContext(this).setConfig(
                BridgingConfig.Builder(this, true).addExcludedTag(TandemNotificationIds.TIMER_BRIDGE_TAG).build(),
            )
        }
        graph.scope.launch { graph.timers.restore() }
    }
}
