package casa.moller.tandem.core.data.shopping

import casa.moller.tandem.core.model.shopping.CurrentLocationResult
import casa.moller.tandem.core.model.shopping.StoreReminderPermissions
import casa.moller.tandem.core.model.shopping.StoreReminderState
import kotlinx.coroutines.flow.Flow

/**
 * Fas 5 (butik lane, plan §7 #34, §8.2): "Påminn mig nära butiken". Implemented in core:sync
 * (`DefaultStoreReminderRepository`) with Google Play services' geofences; main-safe, flows never
 * throw.
 *
 * - **Places** are set per store (a Tandoor supermarket) with the phone's position once
 *   ([useCurrentLocation]) or a pasted geo: link or coordinates ([setPlaceFromText]); they stay on
 *   this phone, per account, and go with the account. Never in logs or diagnostics.
 * - **On/off** ([setEnabled]) for the account, off by default. On: one geofence per enabled store
 *   with a place (ENTER and EXIT, no polling, no foreground service); off removes every geofence.
 *   The screen asks for the permissions (precise location, "Tillåt alltid", notifications) in
 *   context before turning it on.
 * - **The reminder** comes on ENTER at most once per visit (core:domain `StoreVisits`) and only when
 *   the shopping list has something to buy; never in demo mode (guests may get it: it only reads).
 */
interface StoreReminderRepository {
    /** What the page shows. */
    val state: Flow<StoreReminderState>

    /** Turns the reminder on (registers the geofences) or off (removes them). */
    suspend fun setEnabled(enabled: Boolean)

    /** Saves the phone's current position as [storeId]'s place (needs the precise location permission). */
    suspend fun useCurrentLocation(storeId: Long): CurrentLocationResult

    /** Saves the place in [text] (a geo: link, a map link or coordinates); false when there is none. */
    suspend fun setPlaceFromText(storeId: Long, text: String): Boolean

    /** Reminds for [storeId] or not (its place stays). */
    suspend fun setStoreEnabled(storeId: Long, enabled: Boolean)

    /** How close counts as near [storeId] (snapped to 100..500 m in 50 m steps). */
    suspend fun setRadius(storeId: Long, meters: Int)

    /** Forgets [storeId]'s place (and its visit). */
    suspend fun forgetPlace(storeId: Long)

    /** Reads the permissions and the location setting again (the page came back from the system settings). */
    fun refreshPermissions()

    /** The permissions and the location setting right now (the screen's next step after a system dialog). */
    fun currentPermissions(): StoreReminderPermissions
}
