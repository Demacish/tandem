package casa.moller.tandem.feature.shopping

import casa.moller.tandem.core.model.shopping.ShoppingHistoryItem
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class RecurringItemsTest {

    private val now = Instant.parse("2026-11-04T10:00:00Z")

    private fun bought(name: String, vararg daysAgo: Int): List<ShoppingHistoryItem> = daysAgo.mapIndexed { index, ago ->
        ShoppingHistoryItem(entryId = (name.hashCode() * 100 + index).toLong(), foodName = name, completedAt = now - ago.days)
    }

    @Test
    fun aFoodBoughtEveryWeekIsDueAfterMostOfAWeekTheMostOverdueFirst() {
        val history = bought("mjölk", 6, 13, 20, 27) + bought("Kaffe", 14, 28, 42) + bought("bananer", 2, 9, 16)
        assertEquals(listOf("Kaffe", "mjölk"), RecurringItems.due(history, emptyList(), now, limit = 6))
        assertEquals(listOf("Kaffe"), RecurringItems.due(history, emptyList(), now, limit = 1))
    }

    @Test
    fun tooFewPurchasesIrregularRhythmsAndFoodsOnTheListAreNotDue() {
        val history = bought("mjölk", 6, 13) + // Twice: no rhythm yet.
            bought("tandkräm", 70, 140, 210) + // Every ten weeks: too vague.
            bought("bröd", 1, 2, 3, 4) + // Daily: staple shopping, not a reminder.
            bought("ägg", 8, 15, 22)
        assertEquals(listOf("ägg"), RecurringItems.due(history, emptyList(), now, limit = 6))
        assertEquals(emptyList<String>(), RecurringItems.due(history, listOf("Ägg "), now, limit = 6))
    }

    @Test
    fun severalChecksOnOneDayCountOnceAndTheLatestSpellingIsUsed() {
        val sameDay = bought("gul lök", 7, 14, 21) + listOf(ShoppingHistoryItem(999, "gul lök", completedAt = now - 7.days - 2.hours))
        assertEquals(emptyList<String>(), RecurringItems.due(bought("gul lök", 7, 7, 7), emptyList(), now, limit = 6))
        val latest = sameDay.map { if (it.completedAt == now - 7.days) it.copy(foodName = "Gul lök") else it }
        assertEquals(listOf("Gul lök"), RecurringItems.due(latest, emptyList(), now, limit = 6))
    }
}
