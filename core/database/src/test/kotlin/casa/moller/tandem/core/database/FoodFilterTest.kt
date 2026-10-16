package casa.moller.tandem.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.dao.RecipeIndexEntry
import casa.moller.tandem.core.database.entity.FoodEntity
import casa.moller.tandem.core.database.relation.FoodNameRow
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.recipe.Ingredient
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.recipe.Step
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The recipe list's food filter (plan 6.2 "livsmedel", Tandoor `foods_and`) on the stored ingredients. */
@RunWith(AndroidJUnit4::class)
class FoodFilterTest {
    private val database = newDatabase()
    private val dao = database.recipeDao()

    @After
    fun close() {
        database.close()
    }

    private suspend fun names(query: RecipeQuery, accountId: String = ACCOUNT_A): List<String> =
        dao.observeRecipeList(accountId, query, SWEDISH).first().map { it.overview.name }

    private suspend fun seed(accountId: String = ACCOUNT_A) {
        val recipes = listOf(Meatballs, PeaSoup, MushroomPasta)
        recipes.forEach { database.store(accountId, it) }
        // A recipe known only from the index has no ingredients: it never matches a food offline.
        dao.applyIndex(accountId, recipes.map { RecipeIndexEntry(it.id, it.name) } + RecipeIndexEntry(9, "Pytt i panna"))
    }

    @Test
    fun recipesMustContainEveryPickedFood() = runTest {
        seed()

        assertEquals(listOf("Krämig svamppasta", "Köttbullar med potatismos", "Ärtsoppa"), names(RecipeQuery(foodIds = setOf(ONION.id))))
        assertEquals(listOf("Köttbullar med potatismos"), names(RecipeQuery(foodIds = setOf(ONION.id, POTATO.id))))
        assertEquals(emptyList<String>(), names(RecipeQuery(foodIds = setOf(POTATO.id, MUSHROOM.id))))
        // Together with the other filters, and a food used twice in one recipe counts once.
        assertEquals(listOf("Köttbullar med potatismos"), names(RecipeQuery(text = "bullar", foodIds = setOf(ONION.id))))
        assertEquals(listOf("Köttbullar med potatismos"), names(RecipeQuery(foodIds = setOf(SALT.id))))
    }

    @Test
    fun anotherAccountsIngredientsDoNotMatch() = runTest {
        seed(ACCOUNT_B)
        database.store(ACCOUNT_A, PeaSoup)

        assertEquals(listOf("Ärtsoppa"), names(RecipeQuery(foodIds = setOf(ONION.id))))
        assertEquals(emptyList<String>(), names(RecipeQuery(foodIds = setOf(POTATO.id))))
    }

    @Test
    fun theFilterFoodsAreTheFoodListPlusFoodsOfStoredRecipes() = runTest {
        val reference = database.referenceDao()
        // The demo database (and an account before its first full sync) has no food list.
        seed()
        assertEquals(
            listOf(MUSHROOM, ONION, POTATO, SALT, MINCE, PEAS).map { FoodNameRow(it.id, it.name, it.pluralName) }.sortedBy { it.id },
            reference.observeFilterFoods(ACCOUNT_A).first().sortedBy { it.id },
        )

        // The food list wins for foods it has (here renamed on the server); the rest come from ingredients.
        reference.replaceFoods(
            ACCOUNT_A,
            listOf(
                FoodEntity(ACCOUNT_A, id = ONION.id, name = "lök", pluralName = "lökar"),
                FoodEntity(ACCOUNT_A, id = 99, name = "saffran"),
            ),
        )
        val foods = reference.observeFilterFoods(ACCOUNT_A).first().sortedBy { it.id }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 99L), foods.map { it.id })
        assertEquals("lök", foods.single { it.id == ONION.id }.name)
        assertEquals("saffran", foods.single { it.id == 99L }.name)

        assertEquals(emptyList<FoodNameRow>(), reference.observeFilterFoods(ACCOUNT_B).first())
    }

    private companion object {
        val MUSHROOM = Food(id = 1, name = "champinjon", pluralName = "champinjoner")
        val ONION = Food(id = 2, name = "gul lök", pluralName = "gula lökar")
        val POTATO = Food(id = 3, name = "potatis")
        val SALT = Food(id = 4, name = "salt")
        val MINCE = Food(id = 5, name = "köttfärs")
        val PEAS = Food(id = 6, name = "gul ärta", pluralName = "gula ärtor")

        val MushroomPasta = recipe(1, "Krämig svamppasta", MUSHROOM, ONION)
        val Meatballs = recipe(2, "Köttbullar med potatismos", MINCE, ONION, SALT, POTATO, SALT)
        val PeaSoup = recipe(3, "Ärtsoppa", PEAS, ONION)

        fun recipe(id: Long, name: String, vararg foods: Food): Recipe = Recipe(
            id = id,
            name = name,
            servings = 4,
            steps = listOf(
                Step(
                    id = id * 100,
                    instruction = "Gör så här.",
                    ingredients = foods.mapIndexed { index, food ->
                        Ingredient(id = id * 100 + index, food = food, amount = 1.0, order = index)
                    },
                ),
            ),
        )
    }
}
