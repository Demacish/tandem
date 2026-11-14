package casa.moller.tandem.core.sync.storereminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import androidx.annotation.VisibleForTesting
import casa.moller.tandem.core.sync.work.StoreReminderWork
import casa.moller.tandem.core.sync.work.WorkManagerStoreReminderWork

/**
 * Fas 5 (butik lane, plan §7 #34): Play services' geofence broadcasts (not exported: only our own
 * PendingIntent reaches it). It reads the event and hands it to `StoreReminderWorker` at once, with
 * the time it came; the work decides (once per visit) and posts.
 */
class StoreGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val work = workFactory(context.applicationContext)
        when (val event = reader.read(intent)) {
            is GeofenceEvent.Transition -> work.transition(event.storeIds, event.transition, System.currentTimeMillis())
            GeofenceEvent.NotAvailable -> work.lost()
            GeofenceEvent.Ignored -> Unit
        }
    }

    internal companion object {
        /** Play services' reader; tests put a fake here. */
        @VisibleForTesting
        @Volatile
        var reader: GeofenceEventReader = PlayGeofenceEventReader

        @VisibleForTesting
        @Volatile
        var workFactory: (Context) -> StoreReminderWork = { WorkManagerStoreReminderWork(it) }
    }
}

/**
 * Fas 5 (butik lane): puts the geofences back after a reboot, an app update and the location
 * being turned back on (Play services drops them in all three cases). Off in the manifest and
 * switched on only while a geofence is wanted (`SystemEventSwitch`), so it never runs for someone
 * who does not use the reminder. Every action is a system broadcast; anything else is ignored.
 */
class StoreReminderSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        StoreGeofenceReceiver.workFactory(context.applicationContext).register()
    }

    internal companion object {
        val ACTIONS: Set<String> = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            LocationManager.PROVIDERS_CHANGED_ACTION,
            LocationManager.MODE_CHANGED_ACTION,
        )
    }
}
