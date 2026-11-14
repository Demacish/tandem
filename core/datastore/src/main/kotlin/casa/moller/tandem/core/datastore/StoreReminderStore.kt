package casa.moller.tandem.core.datastore

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Fas 5 (butik lane, plan §7 #34): "Påminn mig nära butiken" on this phone, in its own small
 * Preferences DataStore file ([DatastoreFiles.STORE_REMINDERS]): per account whether the reminder is
 * on, each store's place and radius and its visit (the once-per-visit rule), plus one device-wide
 * note of what was last registered with Play services.
 *
 * The places are the user's: they never leave the phone (no server, no backup, no diagnostics, no
 * log; the records' `toString` hides them), and [retainOnly] drops the accounts that left. A failed
 * read is "off", a failed write is dropped.
 */
interface StoreReminderStore {
    /** [accountId]'s record (an empty, switched-off one when there is none). */
    fun observe(accountId: String): Flow<StoreReminderRecord>

    /** Changes [accountId]'s record in one write. */
    suspend fun update(accountId: String, transform: (StoreReminderRecord) -> StoreReminderRecord)

    /** What was last registered with Play services on this phone, null when nothing (or unknown). */
    val registration: Flow<StoreFenceRegistration?>

    suspend fun setRegistration(registration: StoreFenceRegistration?)

    /** Forgets every account not in [accountIds] (accounts that left this phone), places and visits included. */
    suspend fun retainOnly(accountIds: Set<String>)

    /** The accounts with a record. */
    suspend fun accounts(): Set<String>
}

/**
 * One account's reminder near the store.
 *
 * @property enabled the user turned it on (off by default).
 * @property spots each store's place.
 * @property visits each store's visit (once per visit).
 * @property problem the name of a `StoreReminderProblem` when the last registration failed.
 */
@Serializable
data class StoreReminderRecord(
    val enabled: Boolean = false,
    val spots: List<StoreSpotRecord> = emptyList(),
    val visits: List<StoreVisitRecord> = emptyList(),
    val problem: String? = null,
)

/**
 * A store's place (degrees, WGS 84).
 *
 * @property source the name of a `StoreSpotSource`.
 */
@Serializable
data class StoreSpotRecord(
    val storeId: Long,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val enabled: Boolean = true,
    val source: String = "PASTED",
    val savedAtMillis: Long? = null,
) {
    /** Never prints the place. */
    override fun toString(): String = "StoreSpotRecord(storeId=$storeId, radiusMeters=$radiusMeters, enabled=$enabled)"
}

/** A store's visit as the geofence events told it (see core:domain `StoreVisits`). */
@Serializable
data class StoreVisitRecord(
    val storeId: Long,
    val inside: Boolean = false,
    val lastTransitionMillis: Long? = null,
    val lastRemindedMillis: Long? = null,
)

/**
 * What was registered with Play services, so an unchanged set is not registered again at every
 * process start (geofences live until a reboot, an app update or the location being turned off).
 *
 * @property accountId whose stores.
 * @property fingerprint core:domain `StoreFences.fingerprint` of the set.
 * @property bootCount the phone's boot count then (a reboot removes every geofence).
 * @property versionCode the app's version then (an update removes them too).
 */
@Serializable
data class StoreFenceRegistration(val accountId: String, val fingerprint: String, val bootCount: Int, val versionCode: Long)
