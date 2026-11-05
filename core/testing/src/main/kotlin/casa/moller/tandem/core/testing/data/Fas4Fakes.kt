package casa.moller.tandem.core.testing.data

import casa.moller.tandem.core.data.shopping.StoreTripRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.shopping.StoreTrip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

// Fas 4 (fas4-kvar lane): fakes of the Phase 4 data interfaces.

/** [StoreTripRepository] in memory: [trips] newest first, a few per store like the real one. */
class FakeStoreTripRepository(initial: List<StoreTrip> = emptyList()) : StoreTripRepository {
    val trips = MutableStateFlow(initial.sortedByDescending { it.startedAt })

    override fun observeTrips(supermarketId: Long): Flow<List<StoreTrip>> =
        trips.map { all -> all.filter { it.supermarketId == supermarketId } }

    override suspend fun record(trip: StoreTrip): Outcome<Unit> {
        trips.update { all ->
            val others = all.filterNot { it.supermarketId == trip.supermarketId && it.startedAt == trip.startedAt }
            val kept = (others + trip.copy(categoryIds = trip.categoryIds.distinct())).sortedByDescending { it.startedAt }
            kept.groupBy { it.supermarketId }.values.flatMap { it.take(StoreTripRepository.MAX_TRIPS_PER_STORE) }
                .sortedByDescending { it.startedAt }
        }
        return Outcome.Success(Unit)
    }
}
