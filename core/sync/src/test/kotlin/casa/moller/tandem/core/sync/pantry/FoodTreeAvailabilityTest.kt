package casa.moller.tandem.core.sync.pantry

import casa.moller.tandem.core.domain.pantry.MissingIngredients
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.recipe.Ingredient
import casa.moller.tandem.core.network.dto.FoodDto
import casa.moller.tandem.core.sync.remote.toEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fas S komplettering: Tandoor's food tree reaches "Kan laga nu" on the device. `parent`,
 * `substitute_children` and `substitute_siblings` of the food list are stored (schema 6) and become
 * [casa.moller.tandem.core.domain.pantry.FoodAvailability], so a child or sibling that is only in
 * stock resolves the food as the server's makenow does (FACTS "Fas S a", `substituteTree`).
 */
class FoodTreeAvailabilityTest {
    private val foods = listOf(
        FoodDto(id = 10, name = "bönor", substituteChildren = true),
        FoodDto(id = 11, name = "kidneybönor", parent = 10),
        FoodDto(id = 20, name = "röda linser", parent = 21, substituteSiblings = true),
        FoodDto(id = 21, name = "linser"),
        FoodDto(id = 22, name = "gröna linser", parent = 21),
    ).mapNotNull { it.toEntity(ACCOUNT) }

    @Test
    fun theTreeIsStoredAndCounted() {
        val beans = foods.first { it.id == 10L }
        assertTrue(beans.substituteChildren)
        assertEquals(21L, foods.first { it.id == 20L }.parentId)

        val recipe = listOf(Ingredient(id = 1, food = Food(10, "bönor")), Ingredient(id = 2, food = Food(20, "röda linser")))
        val nothing = DefaultMakeNowRepository.availabilityOf(foods, stocked = emptySet())
        assertEquals(listOf("bönor", "röda linser"), MissingIngredients.missing(recipe, nothing).map { it.name })

        // The kidney beans and the green lentils are in the fridge (amount > 0), neither is "har hemma".
        val inStock = DefaultMakeNowRepository.availabilityOf(foods, stocked = setOf(11L, 22L))
        assertEquals(emptyList<String>(), MissingIngredients.missing(recipe, inStock).map { it.name })
    }

    private companion object {
        const val ACCOUNT = "account-a"
    }
}
