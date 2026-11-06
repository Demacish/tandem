package casa.moller.tandem.feature.shopping

import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingFood
import casa.moller.tandem.core.model.shopping.ShoppingHistoryItem
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun aChangedRhythmTakesOverAfterAFewPurchases() {
        // Weekly for a long time, then every third week: the last purchases 6, 27, 48 and 69 days ago.
        val weekly = (0 until 20).map { 70 + it * 7 }.toIntArray()
        val history = bought("mjölk", 6 + 0, 6 + 21, 6 + 42, 6 + 63, *weekly.map { it + 6 }.toIntArray())
        assertEquals(21.0, RecurringItems.rhythms(history, now).single().intervalDays, 0.0)
        assertEquals("day 6 of 21 is not due", emptyList<String>(), RecurringItems.due(history, emptyList(), now, limit = 6))
        val later = now + 12.days
        assertEquals("day 18 of 21 is", listOf("mjölk"), RecurringItems.due(history, emptyList(), later, limit = 6))
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

    // Plan §10 Fas 4 "Klart när": bought at least 3 times in 6 weeks → proposed with the right
    // interval ±3 days, also when every purchase is older than the server's 14-day window.

    private fun intervalOf(name: String, history: List<ShoppingHistoryItem>): Double =
        RecurringItems.rhythms(history, now).single { it.name == name }.intervalDays

    @Test
    fun threePurchasesInSixWeeksAreProposedWithTheRightIntervalAfterTheServerWindow() {
        // Every other week, all three purchases aged out of Tandoor's window (older than 14 days).
        val history = bought("kattmat", 44, 30, 16)
        assertTrue(history.all { now - it.completedAt!! > 14.days })
        assertEquals(listOf("kattmat"), RecurringItems.due(history, emptyList<String>(), now, limit = 6))
        assertEquals(14.0, intervalOf("kattmat", history), 3.0)
    }

    @Test
    fun syntheticRhythmsWithJitterAreFoundWithinThreeDays() {
        // (true interval, purchases as days ago): irregular by a day or two, as real shopping is.
        val cases = listOf(
            7 to intArrayOf(40, 34, 26, 19, 13, 5),
            10 to intArrayOf(41, 30, 21, 12),
            14 to intArrayOf(42, 27, 14),
            21 to intArrayOf(64, 41, 22),
            3 to intArrayOf(20, 17, 13, 11, 8, 5, 2),
        )
        for ((interval, daysAgo) in cases) {
            val name = "vara $interval"
            val found = intervalOf(name, bought(name, *daysAgo))
            assertEquals("interval of $name", interval.toDouble(), found, 3.0)
        }
    }

    @Test
    fun checkedEntriesStillOnTheListCountAsPurchasesAndUncheckedOnesAreLeftOut() {
        // Two archived purchases and the newest one still in Tandoor's window (checked, not archived).
        val archived = bought("yoghurt", 21, 14)
        val checked = ShoppingEntry(id = 5, food = ShoppingFood(9, "yoghurt"), checked = true, completedAt = now - 7.days)
        assertEquals(3, RecurringItems.purchases(archived, listOf(checked)).size)
        assertEquals(listOf("yoghurt"), RecurringItems.dueFor(archived, listOf(checked), now, limit = 6))
        assertEquals(7.0, intervalOf("yoghurt", RecurringItems.purchases(archived, listOf(checked))), 0.0)
        // Bought again today: not due; on the list unchecked: not proposed either.
        val today = checked.copy(id = 6, completedAt = now - 1.hours)
        assertEquals(emptyList<String>(), RecurringItems.dueFor(archived, listOf(checked, today), now, limit = 6))
        val unchecked = ShoppingEntry(id = 7, food = ShoppingFood(9, "Yoghurt"))
        assertEquals(emptyList<String>(), RecurringItems.dueFor(archived, listOf(checked, unchecked), now, limit = 6))
        // An archived entry that is also still listed is counted once.
        val alsoArchived = ShoppingHistoryItem(entryId = 5, foodName = "yoghurt", completedAt = now - 7.days)
        assertEquals(3, RecurringItems.purchases(archived + alsoArchived, listOf(checked)).size)
    }

    @Test
    fun aRhythmThatStoppedLongAgoIsNotProposed() {
        assertEquals(emptyList<String>(), RecurringItems.due(bought("glögg", 340, 333, 326), emptyList<String>(), now, limit = 6))
    }
}
