package casa.moller.tandem.core.domain.pantry

import casa.moller.tandem.core.model.makenow.ExpiringFood
import casa.moller.tandem.core.model.makenow.MakeNowGroup
import casa.moller.tandem.core.model.makenow.MakeNowRecipe
import casa.moller.tandem.core.model.pantry.InventoryEntry
import casa.moller.tandem.core.model.pantry.PantryFood
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.model.recipe.RecipeOverview
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Fas 4 (skafferinotis lane): the rules of "Grädde går ut i morgon – 3 recept" (plan §8.8, §8.7). */
class ExpiryReminderTest {
    private val stockholm = TimeZone.of("Europe/Stockholm")
    private val today = LocalDate(2026, 11, 5)
    private val tomorrow = LocalDate(2026, 11, 6)

    private fun at(text: String, zone: TimeZone = stockholm): Instant = LocalDateTime.parse(text).toInstant(zone)

    private fun entry(id: Long, name: String, expires: LocalDate?, amount: Double = 1.0, foodId: Long? = id) =
        InventoryEntry(id = id, locationId = 1, food = PantryFood(foodId, name), amount = amount, expires = expires)

    @Test
    fun onlyEntriesWithStockExpiringTodayOrTomorrowAreDue() {
        val due = ExpiryReminder.due(
            listOf(
                entry(1, "mjölk", LocalDate(2026, 11, 7)),
                entry(2, "grädde", tomorrow),
                entry(3, "yoghurt", today),
                entry(4, "ost", LocalDate(2026, 11, 4)),
                entry(5, "smör", tomorrow, amount = 0.0),
                entry(6, "salt", null),
                entry(7, "  ", tomorrow),
                // The same food twice on the same day is one item.
                entry(8, "Grädde", tomorrow),
            ),
            today,
        )
        assertEquals(listOf("yoghurt", "grädde"), due.map { it.name })
        assertEquals("grädde@2026-11-06", due[1].key)
    }

    @Test
    fun theSummaryNamesTheDayAndCountsTheOthers() {
        val cream = ExpiryReminder.due(listOf(entry(2, "grädde", tomorrow)), today)
        val one = ExpiryReminder.summary(cream, emptySet(), today, recipeCount = 3)!!
        assertEquals(ExpiryDay.TOMORROW, one.day)
        assertEquals("grädde", one.first.name)
        assertEquals(0, one.others)
        assertEquals(3, one.recipeCount)

        val several = ExpiryReminder.due(listOf(entry(2, "grädde", today), entry(3, "mjölk", today), entry(4, "fil", today)), today)
        val all = ExpiryReminder.summary(several, emptySet(), today, recipeCount = 5)!!
        assertEquals(ExpiryDay.TODAY, all.day)
        assertEquals(2, all.others)

        val mixed = ExpiryReminder.due(listOf(entry(2, "grädde", today), entry(3, "mjölk", tomorrow)), today)
        assertEquals(ExpiryDay.MIXED, ExpiryReminder.summary(mixed, emptySet(), today, 0)!!.day)
    }

    @Test
    fun anItemIsNeverNotifiedTwiceTheSameDay() {
        val due = ExpiryReminder.due(listOf(entry(2, "grädde", tomorrow)), today)
        assertNull(ExpiryReminder.summary(due, setOf("grädde@2026-11-06"), today, 3))
        assertNull(ExpiryReminder.summary(emptyList(), emptySet(), today, 0))
        // A new item brings the whole list again (one grouped notification).
        val more = ExpiryReminder.due(listOf(entry(2, "grädde", tomorrow), entry(3, "mjölk", tomorrow)), today)
        assertEquals(2, ExpiryReminder.summary(more, setOf("grädde@2026-11-06"), today, 3)!!.items.size)
        // The next day the same food (now "today") is new: the key holds the date, the day is checked by the caller.
        assertEquals("grädde@2026-11-06", ExpiryReminder.keyOf(" Grädde ", tomorrow))
    }

    @Test
    fun recipesAreCountedOnceAndDrinksDoNot() {
        fun recipe(id: Long, vararg foods: Long, drink: Boolean = false) = MakeNowRecipe(
            recipe = RecipeOverview(id = id, name = "r$id", keywords = if (drink) listOf(Keyword(id = 9, name = "Dryck")) else emptyList()),
            group = MakeNowGroup.HAS_ALL,
            expiring = foods.map { ExpiringFood(it, "f$it", tomorrow) },
        )
        val recipes = listOf(recipe(1, 2), recipe(2, 2, 3), recipe(3, 4), recipe(4, 2, drink = true), recipe(1, 2))
        assertEquals(2, ExpiryReminder.recipeCount(recipes, setOf(2L, 3L)))
        assertEquals(0, ExpiryReminder.recipeCount(recipes, emptySet()))
    }

    @Test
    fun theDayFollowsTheZoneAndChangesAtLocalMidnight() {
        // 23:30 in Stockholm is still the 5th there, but already the 6th in Helsinki.
        val lateEvening = at("2026-11-05T23:30")
        assertEquals(today, ExpiryReminder.today(lateEvening, stockholm))
        assertEquals(tomorrow, ExpiryReminder.today(lateEvening, TimeZone.of("Europe/Helsinki")))
        assertEquals(tomorrow, ExpiryReminder.today(at("2026-11-06T00:00"), stockholm))
        assertEquals(today, ExpiryReminder.today(at("2026-11-05T23:59:59"), stockholm))
    }

    @Test
    fun nothingIsPostedAtNight() {
        assertTrue(ExpiryReminder.isQuiet(at("2026-11-05T21:00"), stockholm))
        assertTrue(ExpiryReminder.isQuiet(at("2026-11-05T03:15"), stockholm))
        assertTrue(ExpiryReminder.isQuiet(at("2026-11-05T06:59"), stockholm))
        assertFalse(ExpiryReminder.isQuiet(at("2026-11-05T07:00"), stockholm))
        assertFalse(ExpiryReminder.isQuiet(at("2026-11-05T08:00"), stockholm))
        assertFalse(ExpiryReminder.isQuiet(at("2026-11-05T20:59"), stockholm))
    }

    @Test
    fun theNextRunIsTheNextEightOClockInTheZone() {
        assertEquals(1.hours, ExpiryReminder.delayUntilNext(at("2026-11-05T07:00"), stockholm))
        // At or after eight: tomorrow's.
        assertEquals(24.hours, ExpiryReminder.delayUntilNext(at("2026-11-05T08:00"), stockholm))
        assertEquals(8.hours + 30.minutes, ExpiryReminder.delayUntilNext(at("2026-11-05T23:30"), stockholm))
        // Another zone: its own eight o'clock.
        val utc = TimeZone.UTC
        assertEquals(2.hours, ExpiryReminder.delayUntilNext(at("2026-11-05T06:00", utc), utc))
    }

    @Test
    fun aDaylightSavingChangeKeepsEightOClockLocalTime() {
        // Summer time ends in Sweden on 2026-10-25 at 03:00 (clocks back to 02:00): the night has 25 hours.
        assertEquals(25.hours, ExpiryReminder.delayUntilNext(at("2026-10-24T08:00"), stockholm))
        // It starts on 2026-03-29 at 02:00 (clocks forward to 03:00): the night has 23 hours.
        assertEquals(23.hours, ExpiryReminder.delayUntilNext(at("2026-03-28T08:00"), stockholm))
        // From just after midnight on the change day.
        assertEquals(9.hours, ExpiryReminder.delayUntilNext(at("2026-10-25T00:00"), stockholm))
        assertEquals(7.hours, ExpiryReminder.delayUntilNext(at("2026-03-29T00:00"), stockholm))
    }
}
