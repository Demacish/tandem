package casa.moller.tandem.core.domain.mealplan

import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.mealplan.MealType
import casa.moller.tandem.core.model.recipe.RecipeOverview
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Fas 5 (planera lane): the "Påminn:" / "Remind:" line in a meal's note (plan §8.3). */
class MealReminderNoteTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "Påminn: Tina ut kycklingen",
            "påminn:Tina ut kycklingen",
            "PÅMINN :  Tina ut kycklingen  ",
            "Remind: Tina ut kycklingen",
            "remind: Tina ut kycklingen",
            "  - Påminn: Tina ut kycklingen",
            "* Remind: Tina ut kycklingen",
            "Dubbel sats.\nPåminn: Tina ut kycklingen\nFrys hälften.",
            "Påminn:\nPåminn: Tina ut kycklingen",
        ],
    )
    fun aReminderLineIsFoundInSwedishAndEnglish(note: String) {
        assertEquals("Tina ut kycklingen", MealReminderNote.reminderOf(note))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "Tina ut kycklingen",
            "Påminn:",
            "Påminn:    ",
            "Kom ihåg att påminna: Tina ut",
            "Påminnelse: Tina ut",
            "Reminder: Tina ut",
            "Påminn Tina ut",
        ],
    )
    fun otherLinesAreNoReminder(note: String) {
        assertNull(MealReminderNote.reminderOf(note))
    }

    @Test
    fun theFirstReminderWithTextCounts() {
        assertEquals("Tina ut", MealReminderNote.reminderOf("Påminn: Tina ut\nRemind: Marinate"))
    }

    @Test
    fun aReminderIsAddedAtTheEndWithTheChosenWord() {
        assertEquals("Påminn: Tina ut kycklingen", MealReminderNote.withReminder("", "Tina ut kycklingen"))
        assertEquals("Dubbel sats.\nPåminn: Tina ut", MealReminderNote.withReminder("Dubbel sats.\n", " Tina ut "))
        assertEquals(
            "Double batch.\nRemind: Thaw the chicken",
            MealReminderNote.withReminder("Double batch.", "Thaw the chicken", "Remind"),
        )
        // A line break typed into the reminder stays on its one line.
        assertEquals("Påminn: Tina ut kycklingen", MealReminderNote.withReminder("", "Tina ut\nkycklingen"))
    }

    @Test
    fun anExistingReminderKeepsItsPlaceAndWord() {
        val note = "Dubbel sats.\nremind: Thaw\nFrys hälften.\nPåminn: annat"
        assertEquals("Dubbel sats.\nremind: Tina ut\nFrys hälften.", MealReminderNote.withReminder(note, "Tina ut"))
    }

    @Test
    fun aBlankReminderRemovesTheLine() {
        assertEquals("Dubbel sats.\nFrys hälften.", MealReminderNote.withReminder("Dubbel sats.\nPåminn: Tina ut\nFrys hälften.", " "))
        assertEquals("", MealReminderNote.withReminder("Påminn: Tina ut", ""))
        assertEquals("Dubbel sats.", MealReminderNote.withoutReminder("Påminn: Tina ut\nDubbel sats."))
    }

    @Test
    fun theRoundTripOfTheTwoFields() {
        val note = "Dubbel sats.\nPåminn: Tina ut"
        val rest = MealReminderNote.withoutReminder(note)
        val reminder = MealReminderNote.reminderOf(note).orEmpty()
        assertEquals("Dubbel sats.\nPåminn: Tina ut", MealReminderNote.withReminder(rest, reminder))
    }

    private val dinner = MealType(id = 2, name = "Middag", order = 2)
    private val lunch = MealType(id = 1, name = "Lunch", order = 1)
    private val saturday = LocalDate(2026, 11, 14)

    private fun meal(
        id: Long,
        note: String,
        from: LocalDate = saturday,
        to: LocalDate = from,
        type: MealType = dinner,
        title: String = "",
    ) = MealPlanEntry(
        id = id,
        title = title,
        recipe = RecipeOverview(id = id * 10, name = "Kycklinggryta $id"),
        note = note,
        fromDate = from,
        toDate = to,
        mealType = type,
    )

    @Test
    fun theMealsOfTheDayWithAReminderAreDueInMealTypeOrder() {
        val meals = listOf(
            meal(1, "Påminn: Tina ut kycklingen"),
            meal(2, "Bara en anteckning"),
            meal(3, "Remind: Soak the beans", type = lunch),
            meal(4, "Påminn: Tina ut fisken", from = saturday.plusDays(1)),
            // A meal over several days is reminded of before its first day only.
            meal(5, "Påminn: Marinera", from = saturday.plusDays(-1), to = saturday),
        )
        val due = MealReminders.due(meals, saturday)
        assertEquals(listOf("Soak the beans", "Tina ut kycklingen"), due.map { it.text })
        assertEquals(listOf(3L, 1L), due.map { it.mealId })
        assertEquals("Kycklinggryta 1", due.last().mealTitle)
        assertEquals("Middag", due.last().mealTypeName)
    }

    @Test
    fun theKeyDoesNotDependOnTheMealsId() {
        val offline = MealReminders.due(listOf(meal(-3, "Påminn: Tina ut", title = "Gryta")), saturday).single()
        val sent = MealReminders.due(listOf(meal(42, "påminn: tina ut", title = "gryta ")), saturday).single()
        assertEquals(offline.key, sent.key)
    }

    private fun LocalDate.plusDays(days: Int): LocalDate = plus(days, DateTimeUnit.DAY)
}
