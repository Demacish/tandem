package casa.moller.tandem.core.sync.shopping

import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingFilter
import casa.moller.tandem.core.model.shopping.ShoppingFood
import casa.moller.tandem.core.model.shopping.ShoppingUnit
import java.util.Locale
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** The list's order (a store's walking order, aisle names, å/ä/ö) and "Dela som text" (#10, #13). */
class ShoppingOrderingTest {
    private val swedish = Locale.forLanguageTag("sv-SE")
    private val now = Instant.parse("2026-10-25T12:00:00Z")
    private val categories = mapOf(1L to "Mejeri", 2L to "Frukt & grönt", 3L to "Skafferi")

    private fun entry(id: Long, food: String, category: Long?, checked: Boolean = false, amount: Double = 0.0, unit: String? = null) =
        ShoppingEntry(
            id = id,
            food = ShoppingFood(id = id, name = food, categoryId = category),
            unit = unit?.let { ShoppingUnit(id = null, name = it) },
            amount = amount,
            checked = checked,
        )

    private val entries = listOf(
        entry(1, "ägg", 1),
        entry(2, "mjölk", 1),
        entry(3, "äpple", 2),
        entry(4, "banan", 2),
        entry(5, "tandkräm", null),
        entry(6, "kaffe", 3),
        entry(7, "ost", 1, checked = true),
        entry(8, "bröd", 9),
    )

    @Test
    fun aStoresWalkingOrderComesFirstThenAislesItLacksThenNoAisle() {
        val store = ShoppingOrdering(aislePositions = mapOf(2L to 0, 3L to 1, 1L to 2), categoryNames = categories, locale = swedish)
        assertEquals(
            listOf("banan", "äpple", "kaffe", "mjölk", "ägg", "bröd", "tandkräm", "ost"),
            store.sort(entries).map { it.food.name },
        )
    }

    @Test
    fun withoutAStoreAislesAreSortedByNameAndSwedishLettersComeAfterZ() {
        val byName = ShoppingOrdering(aislePositions = emptyMap(), categoryNames = categories, locale = swedish)
        assertEquals(
            listOf("banan", "äpple", "mjölk", "ägg", "kaffe", "bröd", "tandkräm", "ost"),
            byName.sort(entries).map { it.food.name },
        )
    }

    @Test
    fun aNamedListFiltersTheEntries() {
        val onList = entries.first().copy(listIds = setOf(4))
        assertEquals(listOf(onList), ShoppingOrdering.filter(entries.drop(1) + onList, ShoppingFilter(listId = 4)))
        assertEquals(entries.size, ShoppingOrdering.filter(entries, ShoppingFilter()).size)
    }

    @Test
    fun theSharedTextHasWhatIsLeftToBuyInAisleOrder() {
        val store = ShoppingOrdering(aislePositions = mapOf(1L to 0, 2L to 1), categoryNames = categories, locale = swedish)
        val list = listOf(
            entry(1, "mjölk", 1, amount = 2.0, unit = "l"),
            entry(2, "äpple", 2, amount = 1.5, unit = "kg"),
            entry(3, "tandkräm", null),
            entry(4, "ost", 1, checked = true),
            entry(5, "glass", 1).copy(delayUntil = now + 2.hours),
        )
        assertEquals("Mejeri\n2 l mjölk\n\nFrukt & grönt\n1,5 kg äpple\n\ntandkräm", store.shareText(list, now, swedish))
    }
}
