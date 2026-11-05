package casa.moller.tandem.feature.shopping

import casa.moller.tandem.feature.shopping.ShoppingSamples.dairy
import casa.moller.tandem.feature.shopping.ShoppingSamples.fruit
import casa.moller.tandem.feature.shopping.ShoppingSamples.hygiene
import casa.moller.tandem.feature.shopping.ShoppingSamples.pantry
import casa.moller.tandem.feature.shopping.ShoppingSamples.store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LearnedAisleOrderTest {

    private fun thrice(vararg categories: Long): List<List<Long>> = List(3) { categories.toList() }

    @Test
    fun afterThreeTripsTheWalkedAislesComeFirstInTheLearnedOrderThenTheRest() {
        val order = LearnedAisleOrder.of(store, thrice(dairy.id, fruit.id))!!
        assertEquals(store.id, order.supermarketId)
        assertEquals(listOf(13L, 11L, 12L), order.relationIds)
        assertEquals(listOf("Mejeri", "Frukt & grönt", "Skafferi"), order.names)
        assertEquals(3, order.trips)
    }

    @Test
    fun nothingIsOfferedWithoutAStoreTooFewTripsOrAislesOrTheStoresOwnOrder() {
        assertNull(LearnedAisleOrder.of(null, thrice(dairy.id, fruit.id)))
        assertNull("two trips are not enough", LearnedAisleOrder.of(store, thrice(dairy.id, fruit.id).take(2)))
        assertNull(LearnedAisleOrder.of(store, thrice(dairy.id)))
        assertNull("a category the store lacks is not an aisle", LearnedAisleOrder.of(store, thrice(dairy.id, hygiene.id)))
        assertNull(LearnedAisleOrder.of(store, thrice(fruit.id, pantry.id)))
        assertNull("repeats do not count twice", LearnedAisleOrder.of(store, thrice(fruit.id, fruit.id, pantry.id, dairy.id)))
    }
}
