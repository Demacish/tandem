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

    @Test
    fun visitedAislesComeFirstInTheOrderTheyWereVisitedThenTheRest() {
        val order = LearnedAisleOrder.of(store, listOf(dairy.id, fruit.id))!!
        assertEquals(store.id, order.supermarketId)
        assertEquals(listOf(13L, 11L, 12L), order.relationIds)
        assertEquals(listOf("Mejeri", "Frukt & grönt", "Skafferi"), order.names)
    }

    @Test
    fun nothingIsOfferedWithoutAStoreTooFewAislesOrTheStoresOwnOrder() {
        assertNull(LearnedAisleOrder.of(null, listOf(dairy.id, fruit.id)))
        assertNull(LearnedAisleOrder.of(store, listOf(dairy.id)))
        assertNull("a category the store lacks is not an aisle", LearnedAisleOrder.of(store, listOf(dairy.id, hygiene.id)))
        assertNull(LearnedAisleOrder.of(store, listOf(fruit.id, pantry.id)))
        assertNull("repeats do not count twice", LearnedAisleOrder.of(store, listOf(fruit.id, fruit.id, pantry.id, dairy.id)))
    }
}
