package casa.moller.tandem.core.domain.mealplan

import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.mealplan.MealType
import casa.moller.tandem.core.model.recipe.RecipeOverview
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fas 5 (kalender lane, plan §7 #36): the stored meal plan as events of the local calendar, in the
 * phone's zone (Europe/Stockholm with its changes on 29 March and 25 October 2026), and the changes
 * that keep the calendar exactly in step (no duplicates, removed and moved meals disappear).
 */
class MealCalendarEventsTest {
    private val stockholm = TimeZone.of("Europe/Stockholm")
    private val dinner = MealType(id = 1, name = "Middag", order = 2, time = LocalTime(18, 0))
    private val lunchbox = MealType(id = 2, name = "Matlåda", order = 1)
    private val account = "acc-1"

    private fun meal(
        id: Long,
        day: LocalDate,
        type: MealType = dinner,
        title: String = "",
        recipe: String? = "Kycklinggryta",
        note: String = "",
        to: LocalDate = day,
    ) = MealPlanEntry(
        id = id,
        title = title,
        recipe = recipe?.let { RecipeOverview(id = id * 10, name = it) },
        note = note,
        fromDate = day,
        toDate = to,
        mealType = type,
    )

    private fun local(millis: Long, zone: TimeZone = stockholm): LocalDateTime = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)

    @Test
    fun aMealWithATimedTypeStartsThenInThePhonesZoneAndLastsAnHour() {
        val event = MealCalendarEvents.eventOf(account, meal(7, LocalDate(2026, 11, 13), note = "Dubbel sats.\n"), stockholm)
        assertEquals("acc-1/7", event.key)
        assertEquals("Middag: Kycklinggryta", event.title)
        assertEquals("Dubbel sats.", event.description)
        assertFalse(event.allDay)
        assertEquals("Europe/Stockholm", event.timeZone)
        assertEquals(LocalDateTime(2026, 11, 13, 18, 0), local(event.startMillis))
        assertEquals(LocalDateTime(2026, 11, 13, 19, 0), local(event.endMillis))
    }

    @Test
    fun aMealWithoutATimeIsAnAllDayEventInUtc() {
        val event = MealCalendarEvents.eventOf(account, meal(8, LocalDate(2026, 11, 14), type = lunchbox, title = "Rester"), stockholm)
        assertTrue(event.allDay)
        assertEquals("UTC", event.timeZone)
        assertEquals("Matlåda: Rester", event.title)
        assertEquals(LocalDate(2026, 11, 14).atMidnightUtc(), event.startMillis)
        assertEquals(LocalDate(2026, 11, 15).atMidnightUtc(), event.endMillis)
    }

    @Test
    fun aMealOverSeveralDaysCoversThemAll() {
        val allDay = MealCalendarEvents.eventOf(
            account,
            meal(9, LocalDate(2026, 11, 14), type = lunchbox, to = LocalDate(2026, 11, 16)),
            stockholm,
        )
        assertEquals(LocalDate(2026, 11, 17).atMidnightUtc(), allDay.endMillis)
        val timed = MealCalendarEvents.eventOf(account, meal(10, LocalDate(2026, 11, 14), to = LocalDate(2026, 11, 15)), stockholm)
        assertEquals(LocalDateTime(2026, 11, 14, 18, 0), local(timed.startMillis))
        assertEquals(LocalDateTime(2026, 11, 15, 19, 0), local(timed.endMillis))
        // A to_date before from_date (a PATCH that moved only from_date, FACTS "Fas 2") still gives one day.
        val backwards = MealCalendarEvents.eventOf(account, meal(11, LocalDate(2026, 11, 14), to = LocalDate(2026, 11, 12)), stockholm)
        assertEquals(LocalDateTime(2026, 11, 14, 19, 0), local(backwards.endMillis))
    }

    @Test
    fun theDaylightSavingChangesKeepTheWallClockTime() {
        // Sunday 29 March 2026: 02:00 → 03:00. Sunday 25 October: 03:00 → 02:00.
        val march = MealCalendarEvents.eventOf(account, meal(1, LocalDate(2026, 3, 29)), stockholm)
        val october = MealCalendarEvents.eventOf(account, meal(2, LocalDate(2026, 10, 25)), stockholm)
        assertEquals(LocalDateTime(2026, 3, 29, 18, 0), local(march.startMillis))
        assertEquals(LocalDateTime(2026, 10, 25, 18, 0), local(october.startMillis))
        assertEquals(LocalDateTime(2026, 3, 29, 16, 0).toInstant(TimeZone.UTC).toEpochMilliseconds(), march.startMillis)
        assertEquals(LocalDateTime(2026, 10, 25, 17, 0).toInstant(TimeZone.UTC).toEpochMilliseconds(), october.startMillis)
        // A time inside the spring gap becomes the first moment after it.
        val gap = MealCalendarEvents.eventOf(
            account,
            meal(3, LocalDate(2026, 3, 29), type = dinner.copy(time = LocalTime(2, 30))),
            stockholm,
        )
        assertEquals(LocalDateTime(2026, 3, 29, 3, 30), local(gap.startMillis))
        // An all-day meal is the same whatever the zone.
        val allDay = MealCalendarEvents.eventOf(account, meal(4, LocalDate(2026, 3, 29), type = lunchbox), stockholm)
        assertEquals(LocalDate(2026, 3, 29).atMidnightUtc(), allDay.startMillis)
    }

    @Test
    fun anotherZoneMovesTimedMealsAndRewritesThem() {
        val newYork = TimeZone.of("America/New_York")
        val here = MealCalendarEvents.eventOf(account, meal(5, LocalDate(2026, 11, 13)), stockholm)
        val there = MealCalendarEvents.eventOf(account, meal(5, LocalDate(2026, 11, 13)), newYork)
        assertEquals(LocalDateTime(2026, 11, 13, 18, 0), local(there.startMillis, newYork))
        assertNotEquals(here.fingerprint, there.fingerprint)
        val allDayHere = MealCalendarEvents.eventOf(account, meal(6, LocalDate(2026, 11, 13), type = lunchbox), stockholm)
        val allDayThere = MealCalendarEvents.eventOf(account, meal(6, LocalDate(2026, 11, 13), type = lunchbox), newYork)
        assertEquals(allDayHere, allDayThere)
    }

    @Test
    fun titlesFallBackToTheMealTypeOrTheLabel() {
        assertEquals("Middag", MealCalendarEvents.eventOf(account, meal(1, LocalDate(2026, 1, 1), recipe = null), stockholm).title)
        val untitledType = meal(2, LocalDate(2026, 1, 1), type = dinner.copy(name = " "), title = "Pizza")
        assertEquals("Pizza", MealCalendarEvents.eventOf(account, untitledType, stockholm).title)
    }

    @Test
    fun onlyMealsInTheWindowOncePerIdInDayOrder() {
        val today = LocalDate(2026, 11, 13)
        val window = MealCalendarEvents.window(today)
        assertEquals(LocalDate(2026, 10, 30), window.start)
        assertEquals(LocalDate(2026, 12, 25), window.endInclusive)
        val meals = listOf(
            meal(1, LocalDate(2026, 11, 20)),
            meal(2, LocalDate(2026, 11, 14), type = lunchbox),
            meal(3, LocalDate(2026, 11, 14)),
            meal(3, LocalDate(2026, 11, 14)),
            meal(4, LocalDate(2026, 10, 1)),
            meal(5, LocalDate(2027, 1, 30)),
            // Starts before the window, ends inside it.
            meal(6, LocalDate(2026, 10, 28), to = LocalDate(2026, 10, 31)),
        )
        val events = MealCalendarEvents.of(account, meals, window, stockholm)
        assertEquals(listOf("acc-1/6", "acc-1/2", "acc-1/3", "acc-1/1"), events.map { it.key })
    }

    @Test
    fun theFirstWriteInsertsEverythingAndTheSecondChangesNothing() {
        val wanted = MealCalendarEvents.of(
            account,
            listOf(meal(1, LocalDate(2026, 11, 14)), meal(2, LocalDate(2026, 11, 15), type = lunchbox)),
            MealCalendarEvents.window(LocalDate(2026, 11, 13)),
            stockholm,
        )
        val first = MealCalendarEvents.changes(wanted, emptyList())
        assertEquals(wanted, first.insert)
        assertTrue(first.update.isEmpty() && first.delete.isEmpty())
        val stored = wanted.mapIndexed { index, event -> StoredCalendarEvent(100L + index, event.key, event.fingerprint) }
        assertTrue(MealCalendarEvents.changes(wanted, stored).isEmpty)
    }

    @Test
    fun movedRemovedDuplicatedAndForeignRowsAreFixed() {
        val window = MealCalendarEvents.window(LocalDate(2026, 11, 13))
        val beforeMeals = listOf(meal(1, LocalDate(2026, 11, 14)), meal(2, LocalDate(2026, 11, 15)))
        val before = MealCalendarEvents.of(account, beforeMeals, window, stockholm)
        val stored = listOf(
            StoredCalendarEvent(10, before[0].key, before[0].fingerprint),
            StoredCalendarEvent(11, before[1].key, before[1].fingerprint),
            // The same meal twice (an interrupted earlier write): the later row goes.
            StoredCalendarEvent(12, before[0].key, before[0].fingerprint),
            // A row without Tandem's key.
            StoredCalendarEvent(13, null, null),
        )
        // Meal 1 moved to Monday, meal 2 removed, meal 3 is new.
        val afterMeals = listOf(meal(1, LocalDate(2026, 11, 16)), meal(3, LocalDate(2026, 11, 17)))
        val after = MealCalendarEvents.of(account, afterMeals, window, stockholm)
        val changes = MealCalendarEvents.changes(after, stored)
        assertEquals(listOf(10L to after[0]), changes.update)
        assertEquals(listOf(after[1]), changes.insert)
        assertEquals(listOf(11L, 12L, 13L), changes.delete)
    }

    @Test
    fun anotherAccountsEventsAreReplaced() {
        val window = MealCalendarEvents.window(LocalDate(2026, 11, 13))
        val mine = MealCalendarEvents.of("acc-1", listOf(meal(1, LocalDate(2026, 11, 14))), window, stockholm)
        val theirs = MealCalendarEvents.of("acc-2", listOf(meal(1, LocalDate(2026, 11, 14))), window, stockholm)
        val changes = MealCalendarEvents.changes(theirs, mine.map { StoredCalendarEvent(1, it.key, it.fingerprint) })
        assertEquals(listOf(1L), changes.delete)
        assertEquals(theirs, changes.insert)
        // Nothing wanted (turned off, logged out): every row goes.
        val stored = mine.map { StoredCalendarEvent(1, it.key, it.fingerprint) }
        assertEquals(listOf(1L), MealCalendarEvents.changes(emptyList(), stored).delete)
    }

    @Test
    fun theFingerprintIsStable() {
        val event = MealCalendarEvents.eventOf(account, meal(1, LocalDate(2026, 11, 14)), stockholm)
        assertEquals(event.fingerprint, event.copy().fingerprint)
        assertNotEquals(event.fingerprint, event.copy(title = "Middag: Lax").fingerprint)
        assertTrue(event.fingerprint.matches(Regex("[0-9a-f]{1,16}")))
    }

    private fun LocalDate.atMidnightUtc(): Long = LocalDateTime(this, LocalTime(0, 0)).toInstant(TimeZone.UTC).toEpochMilliseconds()
}
