package casa.moller.tandem.core.database

import androidx.room3.useReaderConnection
import androidx.test.core.app.ApplicationProvider
import casa.moller.tandem.core.database.mapper.toDetailWrite
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.recipe.Ingredient
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.model.recipe.Step
import java.util.Locale

internal const val ACCOUNT_A = "account-a"
internal const val ACCOUNT_B = "account-b"

internal val SWEDISH: Locale = Locale.forLanguageTag("sv-SE")

internal fun newDatabase(): TandemDatabase = TandemDatabase.buildInMemory(ApplicationProvider.getApplicationContext())

/** Small, deterministic recipes for the database tests (no server, no fixtures). */
internal object TestRecipes {
    val Pasta = Keyword(id = 1, name = "Pasta")
    val Soup = Keyword(id = 2, name = "Soppa")
    val Dessert = Keyword(id = 3, name = "Efterrätt")

    val MushroomPasta = recipe(
        id = 1,
        name = "Krämig svamppasta",
        description = "Snabb vardagsmiddag",
        keywords = listOf(Pasta),
        foods = listOf("Champinjoner", "Grädde", "Pasta"),
        working = 15,
        waiting = 10,
        rating = 4.0,
    )
    val PeaSoup = recipe(
        id = 2,
        name = "Ärtsoppa",
        keywords = listOf(Soup),
        foods = listOf("Gula ärtor", "Fläsk"),
        working = 20,
        waiting = 100,
    )
    val ApplePie = recipe(
        id = 3,
        name = "Äppelkaka",
        keywords = listOf(Dessert),
        foods = listOf("Äpplen", "Smör"),
        working = 20,
        rating = 5.0,
    )
    val Meatballs = recipe(id = 4, name = "Köttbullar med potatismos", foods = listOf("Köttfärs", "Potatis"), working = 40)
    val CinnamonBuns = recipe(id = 5, name = "Kanelbullar", foods = listOf("Vetemjöl", "Kanel"), working = 60, waiting = 60)
    val MushroomSoup = recipe(
        id = 6,
        name = "Svampsoppa",
        keywords = listOf(Soup),
        foods = listOf("Kantareller"),
        working = 25,
        rating = 3.0,
    )

    val All: List<Recipe> = listOf(MushroomPasta, PeaSoup, ApplePie, Meatballs, CinnamonBuns, MushroomSoup)

    fun recipe(
        id: Long,
        name: String,
        description: String? = null,
        keywords: List<Keyword> = emptyList(),
        foods: List<String> = emptyList(),
        working: Int = 0,
        waiting: Int = 0,
        rating: Double? = null,
    ): Recipe = Recipe(
        id = id,
        name = name,
        description = description,
        servings = 4,
        workingMinutes = working,
        waitingMinutes = waiting,
        rating = rating,
        keywords = keywords,
        steps = listOf(
            Step(
                id = id * 100,
                instruction = "Gör så här.",
                ingredients = foods.mapIndexed { index, food ->
                    Ingredient(id = id * 100 + index, food = Food(id = id * 100 + index, name = food), amount = 1.0, order = index)
                },
            ),
        ),
    )
}

/** Stores [recipe] as a fetched detail of [accountId]. */
internal suspend fun TandemDatabase.store(accountId: String, recipe: Recipe, fetchedAtEpochMillis: Long = 1_000L) {
    recipeDao().replaceDetail(accountId, recipe.toDetailWrite(accountId, rawJson = """{"id":${recipe.id}}""", fetchedAtEpochMillis))
}

/** Runs a `SELECT COUNT(*) ...` (or any single-number query) directly on the database. */
internal suspend fun TandemDatabase.count(sql: String): Long = useReaderConnection { connection ->
    connection.usePrepared(sql) { statement ->
        statement.step()
        statement.getLong(0)
    }
}
