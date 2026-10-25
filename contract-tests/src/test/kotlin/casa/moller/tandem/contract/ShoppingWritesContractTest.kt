package casa.moller.tandem.contract

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Phase 2 (inköp-data lane): the shopping list's writes exactly as the app's outbox sends them
 * (`core:sync` `ShoppingPayloads`, docs/BYGGE.md "Inköpslistan – data och synk"), against the same
 * temporary Tandoor, AFTER Phase2ContractTest (its own step in contract.yml, so nothing here can
 * change that test's facts). Nothing is recorded; a failure fails the run, because the app relies
 * on every assertion:
 *
 * - an entry created with `list_recipe` and `ingredient` belongs to that list recipe (the offline
 *   "Lägg på listan" creates the list recipe first and then each entry, plan 5.7);
 * - a list recipe created with `mealplan` belongs to the meal (`?mealplan=` lists its entries);
 * - an entry's food and unit can be changed by name with PATCH, and the unit removed with null;
 * - `updated_after` in the `Z` form the app sends (milliseconds, after the 2 s overlap) filters.
 */
@EnabledIfEnvironmentVariable(named = "TANDOOR_GUEST_TOKEN", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShoppingWritesContractTest {
    private val env = ContractEnv
    private val today: LocalDate = LocalDate.now(ZoneId.of("Europe/Stockholm"))
    private val sanitizer = Sanitizer(listOf(env.password, env.noSpacePassword), runToday = today)
    private val http = TandoorHttp(
        env.baseUrl,
        sanitizer,
        File(env.recordDir, "fas2-shopping-writes"),
        numbered = false,
        defaultHeaders = mapOf("Host" to ContractEnv.PUBLIC_HOST, "X-Forwarded-Proto" to "https"),
    )

    @Test
    fun theOfflineWritesOfTheAppAreUnderstood() {
        val recipe = recipeWithIngredients()
        val recipeId = recipe.longAt("id") ?: error("recipe without id")
        val ingredients = recipe.arrAt("steps").objects().flatMap { it.arrAt("ingredients").objects() }
            .filter { it.boolAt("is_header") != true && it.objAt("food") != null }
        assertTrue(ingredients.size >= 2, "the seeded recipe has ingredients")

        // "Lägg på listan" offline: the list recipe, then one entry per ingredient with `list_recipe`.
        val listRecipe = api("POST", "/api/shopping-list-recipe/", obj("recipe" to recipeId, "servings" to 2.0, "name" to ""))
        assertEquals(201, listRecipe.status, "$listRecipe")
        val listRecipeId = listRecipe.jsonObject.longAt("id") ?: error("list recipe without id")
        val ingredient = ingredients.first()
        val food = ingredient.objAt("food") ?: error("no food")
        val foodRef = obj("name" to food.stringAt("name"), "id" to food.longAt("id"))
        val unitRef = ingredient.objAt("unit")?.let { obj("name" to it.stringAt("name")) }
        val body = obj(
            "food" to foodRef,
            "unit" to unitRef,
            "amount" to 1.5,
            "shopping_lists" to emptyList<Any>(),
            "list_recipe" to listRecipeId,
            "ingredient" to ingredient.longAt("id"),
        )
        val entry = api("POST", "/api/shopping-list-entry/", body)
        assertEquals(201, entry.status, "$entry")
        val entryId = entry.jsonObject.longAt("id") ?: error("entry without id")
        assertEquals(listRecipeId, entry.jsonObject.longAt("list_recipe"), "the entry belongs to the list recipe")
        assertEquals(ingredient.longAt("id"), entry.jsonObject.longAt("ingredient"), "the entry keeps its ingredient")
        assertEquals(recipeId, entry.jsonObject.objAt("list_recipe_data")?.longAt("recipe"))

        // Long press → edit: food and unit by name, the unit removed with null.
        val renamed = api("PATCH", "/api/shopping-list-entry/$entryId/", obj("food" to mapOf("name" to "kontraktsgrädde")))
        assertEquals(200, renamed.status, "$renamed")
        assertEquals("kontraktsgrädde", renamed.jsonObject.objAt("food")?.stringAt("name"))
        val unit = api("PATCH", "/api/shopping-list-entry/$entryId/", obj("unit" to mapOf("name" to "dl"), "amount" to 3.0))
        assertEquals(200, unit.status, "$unit")
        assertEquals("dl", unit.jsonObject.objAt("unit")?.stringAt("name"))
        assertEquals(3.0, unit.jsonObject.doubleAt("amount"))
        val noUnit = api("PATCH", "/api/shopping-list-entry/$entryId/", obj("unit" to null))
        assertEquals(200, noUnit.status, "$noUnit")
        assertTrue(noUnit.jsonObject["unit"] is JsonNull, "the unit is gone: ${noUnit.jsonObject["unit"]}")

        // updated_after as the app sends it after the overlap: Z form with milliseconds.
        val future = zForm(Instant.now().plus(1, ChronoUnit.HOURS))
        val none = api("GET", "/api/shopping-list-entry/?updated_after=$future&page_size=200")
        assertEquals(200, none.status)
        assertEquals(0, none.jsonObject.arrAt("results").size, "a time in the future returns nothing: $none")
        val past = zForm(Instant.now().minus(1, ChronoUnit.HOURS))
        val changed = api("GET", "/api/shopping-list-entry/?updated_after=$past&page_size=200")
        assertTrue(changed.jsonObject.arrAt("results").objects().any { it.longAt("id") == entryId }, "the new entry is in the delta")

        assertEquals(204, api("DELETE", "/api/shopping-list-recipe/$listRecipeId/").status)
        assertEquals(404, api("GET", "/api/shopping-list-entry/$entryId/").status, "the entries went with their list recipe")
    }

    @Test
    fun aListRecipeCreatedForAMealBelongsToTheMeal() {
        val recipe = recipeWithIngredients()
        val recipeId = recipe.longAt("id") ?: error("recipe without id")
        val mealType = api("GET", "/api/meal-type/?page_size=200").jsonObject.arrAt("results").objects().firstOrNull()
            ?: error("no meal type")
        val day = today.plusDays(DAYS_AHEAD).toString()
        val meal = api(
            "POST",
            "/api/meal-plan/",
            obj(
                "title" to "",
                "recipe" to mapOf("id" to recipeId, "name" to recipe.stringAt("name")),
                "servings" to 4.0,
                "note" to "",
                "from_date" to day,
                "to_date" to day,
                "meal_type" to mapOf("id" to mealType.longAt("id"), "name" to mealType.stringAt("name")),
            ),
        )
        assertEquals(201, meal.status, "$meal")
        val mealId = meal.jsonObject.longAt("id") ?: error("meal without id")
        val forMealBody = obj("recipe" to recipeId, "servings" to 4.0, "name" to "", "mealplan" to mealId)
        val listRecipe = api("POST", "/api/shopping-list-recipe/", forMealBody)
        assertEquals(201, listRecipe.status, "$listRecipe")
        assertEquals(mealId, listRecipe.jsonObject.longAt("mealplan"), "the list recipe belongs to the meal")
        val listRecipeId = listRecipe.jsonObject.longAt("id") ?: error("list recipe without id")
        val entry = api(
            "POST",
            "/api/shopping-list-entry/",
            obj("food" to mapOf("name" to "kontraktsmjölk"), "unit" to null, "amount" to 1.0, "list_recipe" to listRecipeId),
        )
        assertEquals(201, entry.status, "$entry")
        val forMeal = api("GET", "/api/shopping-list-entry/?mealplan=$mealId&page_size=200").jsonObject.arrAt("results").objects()
        assertNotNull(forMeal.firstOrNull { it.longAt("id") == entry.jsonObject.longAt("id") }, "the meal's entries: $forMeal")

        assertEquals(204, api("DELETE", "/api/meal-plan/$mealId/").status)
        api("DELETE", "/api/shopping-list-recipe/$listRecipeId/")
    }

    /** The recipe Phase2ContractTest seeded ("Potatisgratäng", with ingredients). */
    private fun recipeWithIngredients(): JsonObject {
        val list = api("GET", "/api/recipe/?query=Potatisgrat&page_size=10").jsonObject.arrAt("results").objects()
        val id = list.firstOrNull()?.longAt("id") ?: error("the Phase 2 recipe is missing: $list")
        return api("GET", "/api/recipe/$id/").jsonObject
    }

    private fun api(method: String, path: String, body: JsonElement? = null): Exchange = http.call(
        name = "shopping-writes",
        method = method,
        path = path,
        bearer = env.fas1Token,
        body = (body ?: JsonObject(emptyMap()).takeIf { method in setOf("POST", "PATCH", "PUT") })?.toString()?.toRequestBody(JSON),
        record = false,
    )

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject =
        JsonObject(pairs.associate { (key, value) -> key to Facts.toJson(value) })

    /** `2026-10-25T10:00:00.123Z`: what `TandoorDates.format` gives for a time with milliseconds. */
    private fun zForm(instant: Instant): String =
        DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS).plusMillis(MILLIS))

    private companion object {
        val JSON = "application/json".toMediaType()
        const val DAYS_AHEAD = 3L
        const val MILLIS = 123L
    }
}
