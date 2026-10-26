package casa.moller.tandem.core.domain.mealplan

import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.mealplan.MealType
import casa.moller.tandem.core.model.mealplan.WeekShoppingIngredient
import casa.moller.tandem.core.model.mealplan.WeekShoppingMeal
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** "Handla för veckan" merged per food and unit (feature #16, plan 6.2 steps 2 and 4). */
class WeekShoppingMergeTest {
    private val dinner = MealType(1, "Middag")

    private fun meal(id: Long, title: String, onList: Boolean = false, vararg ingredients: WeekShoppingIngredient) = WeekShoppingMeal(
        meal = MealPlanEntry(id = id, title = title, fromDate = LocalDate(2026, 10, 26), mealType = dinner),
        alreadyOnList = onList,
        selected = !onList,
        ingredients = ingredients.toList(),
    )

    private fun ingredient(id: Long, food: String, amount: Double, unit: String? = null, onHand: Boolean = false, category: Long? = null) =
        WeekShoppingIngredient(ingredientId = id, foodName = food, amount = amount, unitName = unit, onHand = onHand, categoryId = category)

    private val pasta = meal(1, "Svamppasta", false, ingredient(11, "Vispgrädde", 3.0, "dl", category = 5), ingredient(12, "Salt", 0.0))
    private val soup = meal(
        2,
        "Fisksoppa",
        false,
        ingredient(21, "vispgrädde ", 2.0, "DL"),
        ingredient(22, "Vispgrädde", 100.0, "g"),
        ingredient(23, "Salt", 0.0, onHand = true),
    )
    private val already = meal(3, "Tacos", true, ingredient(31, "Vispgrädde", 1.0, "dl"))

    @Test
    fun sameFoodAndUnitAreAddedUp() {
        val lines = WeekShoppingMerge.lines(listOf(pasta, soup, already), included = setOf(1, 2, 3))
        val cream = lines.first { it.key == WeekShoppingMerge.key("Vispgrädde", "dl") }
        assertEquals(5.0, cream.amount)
        assertTrue(cream.merged)
        assertEquals(listOf(1L to 11L, 2L to 21L), cream.parts.map { it.mealId to it.ingredientId })
        assertEquals(5L, cream.categoryId)
        // Another unit stays its own line; a meal already on the list adds nothing (never twice).
        assertEquals(listOf("vispgrädde|dl", "salt|", "vispgrädde|g"), lines.map { it.key.replace('\u001F', '|') })
        assertFalse(lines.any { line -> line.parts.any { it.mealId == 3L } })
    }

    @Test
    fun onHandStartsUntickedAndExcludedMealsDropOut() {
        val lines = WeekShoppingMerge.lines(listOf(pasta, soup), included = setOf(1, 2))
        val chosen = WeekShoppingMerge.defaultChosen(lines)
        assertFalse(WeekShoppingMerge.key("Salt", null) in chosen, "salt is on hand in one meal")
        val selection = WeekShoppingMerge.selection(listOf(pasta, soup), included = setOf(1, 2), chosen = chosen)
        assertEquals(mapOf(1L to setOf(11L), 2L to setOf(21L, 22L)), selection.ingredientIdsByMeal)

        val onlySoup = WeekShoppingMerge.selection(listOf(pasta, soup), included = setOf(2), chosen = chosen)
        assertEquals(setOf(2L), onlySoup.ingredientIdsByMeal.keys)
        val nothing = WeekShoppingMerge.selection(listOf(pasta, soup, already), included = setOf(3), chosen = chosen + "x")
        assertTrue(nothing.ingredientIdsByMeal.isEmpty(), "a meal on the list is never selected")
    }
}
