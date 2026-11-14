package casa.moller.tandem.core.model.shopping

import kotlin.time.Instant

// Fas 5 (butik lane, plan §7 #34, §8.2): "Påminn mig nära butiken". A store's place is set on this
// phone only (the user's position once, or a pasted geo: link or coordinates), kept per account,
// never sent to the server and never in logs, diagnostics or crash reports.

/**
 * A place on Earth in degrees (WGS 84), as a geo: link or a phone's position gives it.
 *
 * @property latitude -90..90.
 * @property longitude -180..180.
 */
data class GeoPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range" }
        require(longitude in -180.0..180.0) { "longitude out of range" }
    }

    /** Never prints the place (plan §5.5: no position in logs or crash reports). */
    override fun toString(): String = "GeoPoint(…)"
}

/** How a store's place was set. */
enum class StoreSpotSource {
    /** "Jag är i butiken nu – använd min position": the phone's position, once. */
    CURRENT_LOCATION,

    /** A pasted geo: link or coordinates. */
    PASTED,
}

/**
 * A store's place for the reminder (one per Tandoor supermarket, per account on this phone).
 *
 * @property storeId the Tandoor supermarket's id.
 * @property point where the store is.
 * @property radiusMeters how close counts as "near" (100..500 m, 200 m by default).
 * @property enabled the user wants the reminder for this store.
 * @property source how [point] was set.
 * @property savedAt when [point] was set.
 */
data class StoreSpot(
    val storeId: Long,
    val point: GeoPoint,
    val radiusMeters: Int,
    val enabled: Boolean = true,
    val source: StoreSpotSource = StoreSpotSource.PASTED,
    val savedAt: Instant? = null,
)

/** Who may use the reminder near the store right now. */
enum class StoreReminderAvailability {
    /** The session is not known yet. */
    LOADING,

    /** Nobody is logged in. */
    NO_ACCOUNT,

    /** Demo mode never reminds (no real list, no real stores). */
    DEMO,

    /** The phone has no Google Play services (or too old ones): no geofences, the option is hidden. */
    NO_PLAY_SERVICES,

    /** A logged-in account (guests too: the reminder only reads the list). */
    AVAILABLE,
}

/**
 * What the phone allows, read again whenever the page comes back to the screen.
 *
 * @property precise ACCESS_FINE_LOCATION (geofences need the precise position).
 * @property approximateOnly only ACCESS_COARSE_LOCATION was granted ("Ungefärlig").
 * @property background ACCESS_BACKGROUND_LOCATION ("Tillåt alltid").
 * @property notifications POST_NOTIFICATIONS and the app's notifications on.
 * @property locationOn the phone's location setting is on.
 */
data class StoreReminderPermissions(
    val precise: Boolean = false,
    val approximateOnly: Boolean = false,
    val background: Boolean = false,
    val notifications: Boolean = false,
    val locationOn: Boolean = true,
) {
    /** Everything the reminder needs to come in the background. */
    val complete: Boolean
        get() = precise && background && notifications
}

/** Why the geofences could not be put in place the last time. */
enum class StoreReminderProblem {
    /** The phone's location is off (Play services refuses geofences until it is on again). */
    LOCATION_OFF,

    /** A permission is missing (it was taken back in the system settings). */
    PERMISSION,

    /** Play services refused for another reason; tried again at the next start. */
    FAILED,
}

/**
 * Everything the page "Påminnelse nära butiken" shows.
 *
 * @property availability who may use it.
 * @property enabled the reminder is on for this account (off by default; turning it off removes every geofence).
 * @property spots the stores with a place, by store id.
 * @property permissions what the phone allows.
 * @property problem why the last registration failed, null when it worked (or nothing is registered).
 * @property watched how many stores are watched right now.
 */
data class StoreReminderState(
    val availability: StoreReminderAvailability = StoreReminderAvailability.LOADING,
    val enabled: Boolean = false,
    val spots: Map<Long, StoreSpot> = emptyMap(),
    val permissions: StoreReminderPermissions = StoreReminderPermissions(),
    val problem: StoreReminderProblem? = null,
    val watched: Int = 0,
)

/** The outcome of "Jag är i butiken nu – använd min position". */
sealed interface CurrentLocationResult {
    /** Saved: the position and how exact the phone says it is. */
    data class Saved(val accuracyMeters: Int?) : CurrentLocationResult

    /** The location permission is missing. */
    data object NoPermission : CurrentLocationResult

    /** The phone's location is off. */
    data object LocationOff : CurrentLocationResult

    /** No position came in time (indoors, no signal). */
    data object Unavailable : CurrentLocationResult

    /** Not possible here (demo mode, no account, no Play services). */
    data object NotAvailable : CurrentLocationResult
}
