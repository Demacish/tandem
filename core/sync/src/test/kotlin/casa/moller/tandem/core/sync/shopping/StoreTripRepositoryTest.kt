package casa.moller.tandem.core.sync.shopping

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.shopping.StoreTripRepository
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.shopping.StoreTrip
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.DEMO_ACCOUNT_ID
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Fas 4 (fas4-kvar lane): the store-mode trips behind "Inlärd butiksordning" are kept in
 * `sync_state` (no schema change), per store and account, newest first, a few per store.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class StoreTripRepositoryTest {
    private val graph = SyncTestGraph()
    private val trips = SyncStateStoreTripRepository(graph.sources, graph.databases)

    @After
    fun tearDown() {
        graph.close()
    }

    private fun ok(outcome: Outcome<*>) = assertTrue("$outcome", outcome is Outcome.Success)

    @Test
    fun tripsAreKeptPerStoreNewestFirstAndATripIsRecordedOnce() = runTest {
        ok(trips.record(StoreTrip(supermarketId = 1, startedAt = 100, categoryIds = listOf(3, 1))))
        ok(trips.record(StoreTrip(supermarketId = 2, startedAt = 150, categoryIds = listOf(9, 8))))
        ok(trips.record(StoreTrip(supermarketId = 1, startedAt = 200, categoryIds = listOf(1, 2))))
        // The same trip again, one category further (and a repeated one, kept once).
        ok(trips.record(StoreTrip(supermarketId = 1, startedAt = 200, categoryIds = listOf(1, 2, 2, 3))))

        assertEquals(
            listOf(StoreTrip(1, 200, listOf(1, 2, 3)), StoreTrip(1, 100, listOf(3, 1))),
            trips.observeTrips(1).first(),
        )
        assertEquals(listOf(StoreTrip(2, 150, listOf(9, 8))), trips.observeTrips(2).first())
    }

    @Test
    fun onlyTheNewestTripsOfAStoreAreKept() = runTest {
        repeat(StoreTripRepository.MAX_TRIPS_PER_STORE + 3) { ok(trips.record(StoreTrip(1, it.toLong(), listOf(1, 2)))) }
        val kept = trips.observeTrips(1).first()
        assertEquals(StoreTripRepository.MAX_TRIPS_PER_STORE, kept.size)
        assertEquals(12L, kept.first().startedAt)
        assertEquals(3L, kept.last().startedAt)
    }

    @Test
    fun demoModeHasItsOwnTripsAndABrokenRowReadsAsNone() = runTest {
        ok(trips.record(StoreTrip(1, 100, listOf(3, 1))))
        graph.sources.source.value = ActiveSource.Demo
        assertEquals(emptyList<StoreTrip>(), trips.observeTrips(1).first())
        ok(trips.record(StoreTrip(1, 300, listOf(5, 6))))
        assertEquals(listOf(300L), trips.observeTrips(1).first().map { it.startedAt })
        assertTrue(graph.demoDatabase.syncStateDao().get(DEMO_ACCOUNT_ID, SyncStateStoreTripRepository.KEY) != null)

        graph.sources.source.value = graph.remote
        assertEquals(listOf(100L), trips.observeTrips(1).first().map { it.startedAt })
        graph.real.syncStateDao().upsert(SyncStateEntity(ACCOUNT_ID, SyncStateStoreTripRepository.KEY, hash = "{inte json"))
        assertEquals(emptyList<StoreTrip>(), trips.observeTrips(1).first())
        ok(trips.record(StoreTrip(1, 400, listOf(2, 1))))
        assertEquals(listOf(400L), trips.observeTrips(1).first().map { it.startedAt })
    }

    @Test
    fun theRowRoundTrips() {
        val list = listOf(StoreTrip(4, 20, listOf(7, 5, 6)), StoreTrip(3, 10, emptyList()))
        assertEquals(list, SyncStateStoreTripRepository.decode(SyncStateStoreTripRepository.encode(list)))
    }
}
