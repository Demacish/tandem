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

    // --- 0.7.0 review: the page's two fields merged without losing what the user wrote ---

    @Test
    fun aReminderTypedIntoTheNoteStaysNextToTheFieldsLine() {
        // The field still says "Tina ut"; the user typed another reminder line into the note.
        assertEquals(
            "Middag\nPåminn: Tina ut\nPåminn: köp bröd",
            MealReminderNote.merge("Middag\nPåminn: Tina ut", "Middag\nPåminn: köp bröd", "Tina ut"),
        )
        // A typed line above the field's place: the field's line goes before it, so it still counts.
        val typedFirst = MealReminderNote.merge("Middag\nPåminn: Tina ut", "Påminn: köp bröd\nMiddag", "Tina ut")
        assertEquals("Påminn: Tina ut\nPåminn: köp bröd\nMiddag", typedFirst)
        assertEquals("Tina ut", MealReminderNote.reminderOf(typedFirst))
        // A new meal: the field's line before the typed one, both kept.
        assertEquals("Gryta\nPåminn: Tina ut\nPåminn: köp bröd", MealReminderNote.merge(null, "Gryta\nPåminn: köp bröd", "Tina ut"))
    }

    @Test
    fun theMealsReminderLineKeepsItsPlaceWhenTheNoteIsEdited() {
        val original = "Middag\nRemind: Tina ut\nEfterrätt glass"
        // The text before it is unchanged: the line stays after it.
        assertEquals(
            "Middag\nRemind: Tina ut\nEfterrätt glass och bär",
            MealReminderNote.merge(original, "Middag\nEfterrätt glass och bär", "Tina ut", "Påminn"),
        )
        // The text after it is unchanged: the line stays before it.
        assertEquals(
            "Middag med sallad\nRemind: Tina ut\nEfterrätt glass",
            MealReminderNote.merge(original, "Middag med sallad\nEfterrätt glass", "Tina ut", "Påminn"),
        )
        // First line: stays first, with a new text.
        assertEquals(
            "Remind: Tina ut kycklingen\nDubbel sats",
            MealReminderNote.merge("Remind: Thaw\nDubbel sats", "Dubbel sats", "Tina ut kycklingen"),
        )
        // Everything rewritten: at the end.
        assertEquals("Annat\nRemind: Tina ut", MealReminderNote.merge(original, "Annat", "Tina ut"))
        // Other reminder lines of the meal (hidden by both fields) stay right after it.
        assertEquals("A\nPåminn: x\nPåminn: y\nB!", MealReminderNote.merge("A\nPåminn: x\nB\nPåminn: y", "A\nB!", "x"))
    }

    @Test
    fun aBlankFieldIsTheNoteAsTyped() {
        assertEquals("Middag\nPåminn: köp bröd", MealReminderNote.merge("Middag\nPåminn: Tina ut", " Middag\nPåminn: köp bröd ", "  "))
        assertEquals("Påminn: Tina ut", MealReminderNote.merge(null, "", "Tina\nut"))
    }
}
