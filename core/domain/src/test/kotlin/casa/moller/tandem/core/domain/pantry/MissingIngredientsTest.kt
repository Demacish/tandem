package casa.moller.tandem.core.domain.pantry

import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.recipe.Ingredient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The missing foods of a recipe, by the rules of Tandoor's makenow (FACTS "Fas S a"). */
class MissingIngredientsTest {
    private fun ingredient(id: Long, food: Food?, header: Boolean = false) = Ingredient(id = id, food = food, isHeader = header)

    private val chickpeas = Food(1, "kikärtor")
    private val tahini = Food(2, "tahini")
    private val lemon = Food(3, "citronsaft")
    private val oil = Food(4, "olivolja")
    private val oatDrink = Food(5, "havredryck")
    private val milk = Food(6, "mellanmjölk")

    @Test
    fun theHummusOfTheContractMissesOnlyTheLemonJuice() {
        val foods = mapOf(
            1L to FoodAvailability(1, inStock = true),
            2L to FoodAvailability(2, onHand = true),
            // Amount 0 in the fridge: not in stock.
            3L to FoodAvailability(3, inStock = false),
            4L to FoodAvailability(4, ignoreShopping = true),
        )
        val recipe = listOf(ingredient(1, chickpeas), ingredient(2, tahini), ingredient(3, lemon), ingredient(4, oil))

        assertEquals(listOf("citronsaft"), MissingIngredients.missing(recipe, foods).map { it.name })
    }

    @Test
    fun substitutesResolveByTheServersFlagOrTheirOwnStock() {
        val byFlag = mapOf(5L to FoodAvailability(5, substituteOnHand = true))
        assertEquals(emptyList<String>(), MissingIngredients.missing(listOf(ingredient(1, oatDrink)), byFlag).map { it.name })

        val byStock = mapOf(5L to FoodAvailability(5, substituteIds = setOf(6)), 6L to FoodAvailability(6, inStock = true))
        assertEquals(emptyList<String>(), MissingIngredients.missing(listOf(ingredient(1, oatDrink)), byStock).map { it.name })

        val neither = mapOf(5L to FoodAvailability(5, substituteIds = setOf(6)), 6L to FoodAvailability(6))
        assertEquals(listOf("havredryck"), MissingIngredients.missing(listOf(ingredient(1, oatDrink)), neither).map { it.name })
    }

    @Test
    fun anIgnoredFoodThatIsARecipeStillCounts() {
        val foods = mapOf(4L to FoodAvailability(4, ignoreShopping = true, isRecipe = true))
        assertEquals(listOf("olivolja"), MissingIngredients.missing(listOf(ingredient(1, oil)), foods).map { it.name })
    }

    @Test
    fun headersFreeTextAndRepeatedFoodsCountOnce() {
        val recipe = listOf(
            ingredient(1, null, header = true),
            ingredient(2, null),
            ingredient(3, milk),
            ingredient(4, milk),
            ingredient(5, Food(7, "salt", onHand = true)),
        )
        assertEquals(listOf("mellanmjölk"), MissingIngredients.missing(recipe, emptyMap()).map { it.name })
    }
}
