package casa.moller.tandem.core.domain.shopping

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Plan §10 Fas 4 "Klart när": after 3 shopping trips in the same store the app proposes a
// category order where at least 80 % of the categories lie in the order they were checked off.
class StoreOrderLearningTest {

    /** The store's current order: categories 1..8. */
    private val store = (1L..8L).toList()

    /** How the household actually walks the store. */
    private val walked = listOf(5L, 3L, 1L, 8L, 2L, 7L, 4L, 6L)

    /** [walked] with the neighbours at [index] and [index] + 1 swapped (picked up in the other order). */
    private fun swapped(index: Int): List<Long> = walked.toMutableList().also { java.util.Collections.swap(it, index, index + 1) }

    /** The share of [trip]'s categories that lie in [order] (one trip's agreement). */
    private fun share(order: List<Long>, trip: List<Long>): Double = StoreOrderLearning.agreement(order, listOf(trip))

    @Test
    fun afterThreeTripsTheWalkedOrderIsProposedAndAgreesWithEveryTrip() {
        val trips = listOf(swapped(1), walked - 8L, swapped(5)) // Newest first; one skipped the frozen food.
        assertNull(StoreOrderLearning.propose(store, trips.take(1)))
        assertNull(StoreOrderLearning.propose(store, trips.take(2)), "two trips are not enough")

        val proposal = StoreOrderLearning.propose(store, trips)
        assertNotNull(proposal)
        proposal!!
        assertEquals(walked, proposal.order)
        assertEquals(3, proposal.trips)
        assertTrue(proposal.agreement >= StoreOrderLearning.MIN_AGREEMENT, "agreement ${proposal.agreement}")
        trips.forEach { trip -> assertTrue(share(proposal.order, trip) >= 0.8, "trip $trip") }
    }

    @Test
    fun syntheticTripsWithSkipsAndMistakesAlwaysGiveAnOrderThatAgreesAtLeastEightyPercent() {
        repeat(500) { seed ->
            val random = Random(seed)
            val size = random.nextInt(8, 13)
            val truth = (1L..size.toLong()).shuffled(random)
            val storeOrder = truth.reversed()
            // Three trips: up to two categories skipped, and most trips pick two neighbours in the other order.
            val trips = List(3) {
                val skipped = truth.shuffled(random).take(random.nextInt(0, 3)).toSet()
                val trip = (truth - skipped).toMutableList()
                if (random.nextDouble() < 0.7) {
                    val at = random.nextInt(trip.size - 1)
                    java.util.Collections.swap(trip, at, at + 1)
                }
                trip.toList()
            }
            val proposal = StoreOrderLearning.propose(storeOrder, trips)
            assertNotNull(proposal, "seed $seed: $trips")
            val visited = trips.sumOf { it.size }
            val inOrder = trips.sumOf { trip -> share(proposal!!.order, trip) * trip.size }
            assertTrue(inOrder / visited >= 0.8, "seed $seed: ${inOrder / visited}")
            assertEquals(storeOrder.toSet(), proposal!!.order.toSet(), "every category stays")
        }
    }

    @Test
    fun tripsThatDisagreeOrFollowTheStoreProposeNothing() {
        // Any order agrees with a trip and its reverse in at most n + 1 places: at most 18 of 32 here.
        val chaotic = listOf(store, store.reversed(), listOf(2L, 4L, 6L, 8L, 1L, 3L, 5L, 7L), listOf(7L, 5L, 3L, 1L, 8L, 6L, 4L, 2L))
        assertNull(StoreOrderLearning.propose(store, chaotic), "no habit, no proposal")
        assertNull(StoreOrderLearning.propose(store, List(3) { store }), "the store's own order")
        assertNull(StoreOrderLearning.propose(store, List(3) { listOf(5L) }), "one category per trip says nothing")
        assertNull(StoreOrderLearning.propose(store, List(3) { listOf(5L, 99L) }), "unknown categories do not count")
    }

    @Test
    fun onlyTheNewestTripsCountAndUnvisitedAislesKeepTheirPlace() {
        val old = List(5) { store.reversed() }
        val recent = List(5) { listOf(3L, 1L, 2L) }
        val proposal = StoreOrderLearning.propose(store, recent + old)!!
        assertEquals(listOf(3L, 1L, 2L, 4L, 5L, 6L, 7L, 8L), proposal.order)
        assertEquals(StoreOrderLearning.MAX_TRIPS, proposal.trips)
        assertEquals(1.0, proposal.agreement)
    }

    // Fas 4 review (fix-data): only what the trips contradict moves.
    @Test
    fun tripsThatFitTheStoresOrderProposeNothingEvenWhenTheySkipAisles() {
        assertNull(StoreOrderLearning.propose(store, List(3) { listOf(2L, 3L, 5L) }), "the first aisle skipped")
        val disjoint = listOf(listOf(1L, 2L), listOf(3L, 4L), listOf(5L, 6L))
        assertNull(StoreOrderLearning.propose(store, disjoint), "disjoint trips compare nothing")
    }

    @Test
    fun aRealSwapLeavesEveryAisleNobodyVisitedInItsPlace() {
        val proposal = StoreOrderLearning.propose(listOf(1L, 2L, 3L, 4L, 5L), List(3) { listOf(3L, 2L, 5L) })!!
        assertEquals(listOf(1L, 3L, 2L, 4L, 5L), proposal.order)

        val spread = StoreOrderLearning.propose(listOf(1L, 2L, 3L, 4L, 5L), List(3) { listOf(5L, 2L) })!!
        assertEquals(listOf(1L, 5L, 3L, 4L, 2L), spread.order)
    }

    @Test
    fun pairsNoTripComparedKeepTheStoresOrder() {
        val proposal = StoreOrderLearning.propose(listOf(1L, 2L, 3L, 4L, 5L, 6L), listOf(listOf(2L, 1L), listOf(3L, 4L), listOf(5L, 6L)))!!
        assertEquals(listOf(2L, 1L, 3L, 4L, 5L, 6L), proposal.order)
    }

    @Test
    fun agreementCountsTheLongestRunInTheTripsOrder() {
        assertEquals(1.0, share(walked, walked))
        assertEquals(7.0 / 8, share(walked, swapped(0)))
        assertEquals(0.0, StoreOrderLearning.agreement(walked, emptyList()))
        assertEquals(walked, StoreOrderLearning.improved(listOf(3L, 5L, 1L, 8L, 2L, 7L, 4L, 6L), List(3) { walked }))
    }
}
