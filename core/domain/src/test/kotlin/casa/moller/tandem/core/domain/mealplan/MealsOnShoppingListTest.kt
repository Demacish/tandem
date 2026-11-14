package casa.moller.tandem.core.domain.mealplan

import casa.moller.tandem.core.domain.mealplan.MealsOnShoppingList.Meal
import casa.moller.tandem.core.domain.mealplan.MealsOnShoppingList.Row
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 0.8.0 (enhetstestet, B7): "redan på listan" in "Handla för veckan". */
class MealsOnShoppingListTest {
    private val tonight = Meal(id = 1, recipeId = 10)
    private val tomorrow = Meal(id = 2, recipeId = 20)
    private val friday = Meal(id = 3, recipeId = 10)
    private val note = Meal(id = 4, recipeId = null)
    private val week = listOf(tonight, tomorrow, friday, note)

    @Test
    fun aMealWithALinkedListRecipeIsOnTheList() {
        assertEquals(setOf(2L), MealsOnShoppingList.of(week, linkedMealIds = setOf(2L), rows = emptyList()))
    }

    @Test
    fun theSameRecipeOnTheListWithoutAMealCoversItsFirstMealOnly() {
        val rows = listOf(
            Row(listRecipeId = -7, recipeId = 10, mealPlanId = null, checked = false),
            Row(listRecipeId = -7, recipeId = 10, mealPlanId = null, checked = true),
        )
        assertEquals(setOf(1L), MealsOnShoppingList.of(week, emptySet(), rows))
    }

    @Test
    fun twoListRecipesWithoutAMealCoverTwoMeals() {
        val rows = listOf(
            Row(listRecipeId = -7, recipeId = 10, mealPlanId = null, checked = false),
            Row(listRecipeId = 8, recipeId = 10, mealPlanId = null, checked = false),
        )
        assertEquals(setOf(1L, 3L), MealsOnShoppingList.of(week, emptySet(), rows))
    }

    @Test
    fun aLinkedMealDoesNotUseUpTheUnlinkedListRecipe() {
        val rows = listOf(Row(listRecipeId = 8, recipeId = 10, mealPlanId = null, checked = false))
        assertEquals(setOf(1L, 3L), MealsOnShoppingList.of(week, linkedMealIds = setOf(1L), rows = rows))
    }

    @Test
    fun boughtRowsAnotherMealsRowsAndLooseRowsDoNotCount() {
        val rows = listOf(
            Row(listRecipeId = 8, recipeId = 10, mealPlanId = null, checked = true),
            Row(listRecipeId = 9, recipeId = 10, mealPlanId = 99, checked = false),
            Row(listRecipeId = null, recipeId = null, mealPlanId = null, checked = false),
        )
        assertEquals(emptySet<Long>(), MealsOnShoppingList.of(week, emptySet(), rows))
    }
}
