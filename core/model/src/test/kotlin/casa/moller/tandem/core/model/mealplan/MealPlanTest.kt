package casa.moller.tandem.core.model.mealplan

import casa.moller.tandem.core.model.recipe.RecipeOverview
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MealPlanTest {
    private val dinner = MealType(id = 1, name = "Middag")

    @Test
    fun titleFallsBackToTheRecipeName() {
        val day = LocalDate(2026, 10, 6)
        val planned = MealPlanEntry(id = 1, recipe = RecipeOverview(id = 7, name = "Ärtsoppa"), fromDate = day, mealType = dinner)
        assertEquals("Ärtsoppa", planned.displayTitle)
        assertEquals("Pizza", planned.copy(title = "Pizza").displayTitle)
    }

    @Test
    fun entriesCoverTheirWholeDateRange() {
        val entry = MealPlanEntry(
            id = 1,
            title = "Gryta",
            fromDate = LocalDate(2026, 10, 6),
            toDate = LocalDate(2026, 10, 8),
            mealType = dinner,
        )
        assertTrue(entry.isOn(LocalDate(2026, 10, 7)))
        assertFalse(entry.isOn(LocalDate(2026, 10, 9)))
        assertTrue(UpcomingMeals(today = LocalDate(2026, 10, 6)).isEmpty)
    }
}
