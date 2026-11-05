package casa.moller.tandem.core.data.shopping

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.shopping.StoreTrip
import kotlinx.coroutines.flow.Flow

/**
 * The store-mode trips of the active account (Fas 4 "Inlärd butiksordning", plan §8.2 and §10
 * Fas 4 "Klart när": after 3 trips in the same store the app proposes an aisle order). A device
 * record: nothing is sent to Tandoor until the user approves the order
 * ([SupermarketRepository.reorderAisles]). Demo mode keeps its trips in the demo database.
 * Implemented in core:sync (`sync_state`, no schema change).
 */
interface StoreTripRepository {
    /** The trips in [supermarketId], newest first (at most [MAX_TRIPS_PER_STORE]). */
    fun observeTrips(supermarketId: Long): Flow<List<StoreTrip>>

    /** Stores [trip], replacing an earlier record of the same trip (same store and start). */
    suspend fun record(trip: StoreTrip): Outcome<Unit>

    companion object {
        /** How many trips per store are kept; older ones are dropped. */
        const val MAX_TRIPS_PER_STORE: Int = 10
    }
}
