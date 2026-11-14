package casa.moller.tandem.core.sync.storereminder

import casa.moller.tandem.core.domain.storereminder.StoreFence
import casa.moller.tandem.core.domain.storereminder.StoreTransition
import casa.moller.tandem.core.model.shopping.GeoPoint
import casa.moller.tandem.core.model.shopping.StoreReminderPermissions

// Fas 5 (butik lane, plan §7 #34): what the reminder near the store needs from the phone, behind
// small interfaces so the logic is tested without Play services (PlayStoreReminderPlatform.kt has
// the real ones). Nothing here ever logs a place.

/** Whether Google Play services can give geofences and positions on this phone. */
internal interface PlayServicesCheck {
    fun isAvailable(): Boolean
}

/** The outcome of putting geofences in place or taking them away. */
internal enum class GeofenceResult {
    OK,

    /** The phone's location is off (GEOFENCE_NOT_AVAILABLE). */
    LOCATION_OFF,

    /** The precise or the background location permission is missing. */
    PERMISSION,

    /** Anything else (too many geofences, Play services not answering). */
    FAILED,
}

/** Play services' geofences (one PendingIntent for all of them). */
internal interface GeofenceGateway {
    /** Replaces every geofence of the app with [fences] (ENTER and EXIT, no initial trigger). */
    suspend fun replaceAll(fences: List<StoreFence>): GeofenceResult

    /** Removes every geofence of the app. */
    suspend fun removeAll(): GeofenceResult
}

/** One position from the phone, once ("Jag är i butiken nu"). */
internal interface CurrentLocationGateway {
    /**
     * The phone's position now, or null when none came within the gateway's time limit.
     *
     * @return the position and its accuracy in metres (null when the phone gives none).
     */
    suspend fun current(): Pair<GeoPoint, Int?>?
}

/** The permissions and the location setting, read when asked. */
internal interface StoreReminderPermissionReader {
    fun read(): StoreReminderPermissions
}

/** What tells a registration apart from the next boot or app version. */
internal interface DeviceRegistrationKey {
    /** The phone's boot count (`Settings.Global.BOOT_COUNT`), -1 when unknown. */
    fun bootCount(): Int

    /** The app's version code. */
    fun versionCode(): Long
}

/** Turns the receiver for reboot, app update and location toggles on only while a geofence is wanted. */
internal interface SystemEventSwitch {
    fun setEnabled(enabled: Boolean)
}

/** A geofence broadcast as the receiver reads it. */
internal sealed interface GeofenceEvent {
    /** [storeIds] were entered or left. */
    data class Transition(val storeIds: List<Long>, val transition: StoreTransition) : GeofenceEvent

    /** Play services dropped the geofences (the location was turned off). */
    data object NotAvailable : GeofenceEvent

    /** Some other error or nothing of ours: ignored. */
    data object Ignored : GeofenceEvent
}

/** Reads a geofence broadcast (Play services' `GeofencingEvent`; tests pass their own). */
internal fun interface GeofenceEventReader {
    fun read(intent: android.content.Intent): GeofenceEvent
}
