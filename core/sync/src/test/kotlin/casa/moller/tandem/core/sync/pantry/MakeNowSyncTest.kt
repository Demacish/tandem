package casa.moller.tandem.core.sync.pantry

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.entity.FoodEntity
import casa.moller.tandem.core.database.mapper.toDetailWrite
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.makenow.MakeNowFilter
import casa.moller.tandem.core.model.makenow.MakeNowGroup
import casa.moller.tandem.core.model.makenow.MakeNowState
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.recipe.Ingredient
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.model.recipe.Step
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import casa.moller.tandem.core.testing.fixtures.TandoorFixtures
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Fas S (hemma-data lane): "Kan laga nu" (#23) and "Använd upp" (#28) against the recorded
 * `makenow` answers of BOTH 2.6.13 and 2.7.1 (FACTS "Fas S a"): the three groups equal what the
 * server's `makenow=0/1/2` say (the drink that is always in `makenow=0` left out), the missing foods
 * computed on the device agree with the group of every recipe, an expiring food lifts its recipe,
 * at most one read a minute, "Överraska mig", lazily fetched details and demo mode.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MakeNowSyncTest {
    private fun forEachVersion(block: suspend PantrySyncEnv.() -> Unit) = PantrySyncEnv.forEachVersion(block)

    /** The contract's foods (FACTS "Fas S" upplägg): ids as recorded where the recordings show them. */
    private val foods = mapOf(
        "kikärtor" to 51L,
        "tahini" to 52L,
        "citronsaft" to 53L,
        "olivolja" to 54L,
        "havregryn" to 55L,
        "havredryck" to 56L,
        "blåbär" to 57L,
        "persilja" to 58L,
        "spiskummin" to 59L,
        "kikärtsmjöl" to 60L,
        "mellanmjölk" to 61L,
    )

    private val recipes = mapOf(
        "Hummus" to listOf("kikärtor", "tahini", "citronsaft", "olivolja"),
        "Tahinidressing" to listOf("tahini", "olivolja"),
        "Gröt med blåbär" to listOf("havregryn", "havredryck", "blåbär"),
        "Falafel" to listOf("kikärtor", "persilja", "spiskummin", "kikärtsmjöl"),
    )

    /**
     * The contract's state before makenow: tahini, havregryn and mellanmjölk "har hemma", olivolja
     * `ignore_shopping`, havredryck with mellanmjölk as substitute, kikärtor 2 in Kyl, citronsaft 0,
     * persilja and blåbär 1; the recorded answers of `makenow=0/1/2` and the recipes' ingredients.
     */
    private suspend fun PantrySyncEnv.contractState(tahiniExpires: String? = null) {
        val id = { name: String -> foods.getValue(name) }
        graph.real.referenceDao().replaceFoods(
            ACCOUNT_ID,
            foods.map { (name, foodId) ->
                FoodEntity(
                    accountId = ACCOUNT_ID,
                    id = foodId,
                    name = name,
                    onHand = name in setOf("tahini", "havregryn", "mellanmjölk"),
                    ignoreShopping = name == "olivolja",
                    substituteOnHand = name == "havredryck",
                    substituteIds = if (name == "havredryck") "[61]" else "[]",
                )
            },
        )
        val fridge = tandoor.seedLocation("Kyl")
        tandoor.seedEntry(fridge, "kikärtor", amount = 2.0, withFoodId = id("kikärtor"))
        tandoor.seedEntry(fridge, "citronsaft", amount = 0.0, withFoodId = id("citronsaft"))
        tandoor.seedEntry(fridge, "persilja", amount = 1.0, withFoodId = id("persilja"))
        tandoor.seedEntry(fridge, "blåbär", amount = 1.0, withFoodId = id("blåbär"))
        if (tahiniExpires != null) tandoor.seedEntry(fridge, "tahini", amount = 1.0, expires = tahiniExpires, withFoodId = id("tahini"))
        read()

        val recorded = FakePantryTandoor.overviews("recipe-makenow-1", version) +
            FakePantryTandoor.overviews("recipe-makenow-foods-or", version) +
            FakePantryTandoor.overviews("recipe-search-drink", version)
        val byName = recorded.associateBy { it.getValue("name").jsonPrimitive.content }
        // The facts are flat, with dotted keys ("makenow.0").
        val facts = Json.parseToJsonElement(TandoorFixtures.text("fas-s/observed-facts.json", version)).jsonObject
        for (n in 0..2) {
            // The recorded answer (seeded recipes only) and the drink without ingredients, which is always in makenow=0.
            val names = facts.getValue("makenow.$n").jsonArray.map { it.jsonPrimitive.content } + "Rioja Crianza"
            tandoor.makeNow[n] = names.map { byName.getValue(it) }
        }
        for ((name, ingredients) in recipes) {
            val recipeId = byName.getValue(name).getValue("id").jsonPrimitive.content.toLong()
            val recipe = Recipe(
                id = recipeId,
                name = name,
                steps = listOf(
                    Step(
                        id = recipeId * 10,
                        ingredients = ingredients.mapIndexed { index, food ->
                            Ingredient(id = recipeId * 100 + index, food = Food(id(food), food))
                        },
                    ),
                ),
            )
            graph.real.recipeDao().replaceDetail(ACCOUNT_ID, recipe.toDetailWrite(ACCOUNT_ID, rawJson = "{}", fetchedAtEpochMillis = 1))
        }
    }

    private suspend fun PantrySyncEnv.state(filter: MakeNowFilter = MakeNowFilter()): MakeNowState = withTimeout(15.seconds) {
        graph.makeNow.observe(filter, graph.clock.today()).first { state -> state.groups.values.any { it.isNotEmpty() } }
    }

    private fun MakeNowState.names(group: MakeNowGroup): List<String> = recipes(group).map { it.recipe.name }

    @Test
    fun theGroupsAreWhatTheServersMakenowSaysAndTheMissingFoodsAgree() = forEachVersion {
        contractState()
        ok(graph.makeNow.refresh(force = true))

        val state = state()

        assertEquals(listOf("Gröt med blåbär", "Tahinidressing"), state.names(MakeNowGroup.HAS_ALL))
        assertEquals(listOf("Hummus"), state.names(MakeNowGroup.MISSING_1))
        assertEquals(listOf("Falafel"), state.names(MakeNowGroup.MISSING_2))
        // The device names exactly as many missing foods as the server's group says.
        for (group in MakeNowGroup.entries) {
            for (recipe in state.recipes(group)) assertEquals("[$version] ${recipe.recipe.name}", group.missing, recipe.missingFoods?.size)
        }
        assertEquals(listOf("citronsaft"), state.recipes(MakeNowGroup.MISSING_1).single().missingFoods?.map { it.name })
        assertEquals(listOf("spiskummin", "kikärtsmjöl"), state.recipes(MakeNowGroup.MISSING_2).single().missingFoods?.map { it.name })
        // Three requests, makenow as an integer (FACTS "Fas S a").
        val asked = tandoor.requests("GET", "/api/recipe/").map { (call, _) -> call.substringAfter("makenow=").substringBefore('&') }
        assertEquals(listOf("0", "1", "2"), asked)
        assertEquals(false, state.stale)
    }

    @Test
    fun aFoodThatExpiresTomorrowLiftsItsRecipeToTheTop() = forEachVersion {
        contractState(tahiniExpires = graph.clock.today().plus(DatePeriod(days = 1)).toString())
        ok(graph.makeNow.refresh(force = true))

        val top = state().recipes(MakeNowGroup.HAS_ALL)

        assertEquals(listOf("Tahinidressing", "Gröt med blåbär"), top.map { it.recipe.name })
        assertEquals(listOf("tahini"), top.first().expiring.map { it.name })
        assertTrue(top[1].expiring.isEmpty())
    }

    @Test
    fun theGroupsAreReadAtMostOnceAMinute() = forEachVersion {
        contractState()
        ok(graph.makeNow.refresh(force = true))
        ok(graph.makeNow.refresh())
        state()
        assertEquals(3, tandoor.requests("GET", "/api/recipe/").size)

        graph.clock.advance(61.seconds)
        ok(graph.makeNow.refresh())
        assertEquals(6, tandoor.requests("GET", "/api/recipe/").size)

        // Offline: the stored groups, marked stale.
        offline()
        graph.clock.advance(61.seconds)
        assertTrue(graph.makeNow.refresh() is Outcome.Failure)
        val stale = state()
        assertTrue(stale.stale)
        assertEquals(listOf("Hummus"), stale.names(MakeNowGroup.MISSING_1))
    }

    @Test
    fun surpriseMePicksWithinTheGroupAndTheFiltersApply() = forEachVersion {
        contractState()
        ok(graph.makeNow.refresh(force = true))
        graph.makeNow.random = Random(SEED)

        repeat(5) {
            val picked = value(graph.makeNow.surprise(MakeNowGroup.HAS_ALL, MakeNowFilter()))
            assertTrue("[$version] ${picked?.recipe?.name}", picked?.recipe?.name in setOf("Gröt med blåbär", "Tahinidressing"))
        }
        assertEquals("Falafel", value(graph.makeNow.surprise(MakeNowGroup.MISSING_2, MakeNowFilter()))?.recipe?.name)
        // A keyword no recipe has: every group is empty, and so is the surprise.
        assertNull(value(graph.makeNow.surprise(MakeNowGroup.HAS_ALL, MakeNowFilter(keywordIds = setOf(UNKNOWN_KEYWORD)))))
    }

    @Test
    fun theMissingFoodsOfARecipeWithoutDetailAreFetchedOnceWhenAsked() = forEachVersion {
        // Pytt i panna (recipe 1) has no stored detail: it is fetched now, then reused.
        val missing = value(graph.makeNow.missingFoods(1))
        assertTrue("[$version] $missing", missing.isNotEmpty())
        assertEquals(1, graph.server.requestsTo("GET", "/api/recipe/1/").size)
        assertEquals(missing, value(graph.makeNow.missingFoods(1)))
        assertEquals(1, graph.server.requestsTo("GET", "/api/recipe/1/").size)
    }

    @Test
    fun demoModeComputesTheGroupsFromTheDemoPantryWithoutTheNetwork() = forEachVersion {
        graph.sources.source.value = ActiveSource.Demo
        ok(graph.demo.ensureSeeded())
        val before = graph.server.requests.size

        val state = state()

        assertTrue(state.names(MakeNowGroup.HAS_ALL).containsAll(listOf("Pannkakor", "Ugnsbakad lax med dillpotatis")))
        assertTrue(state.names(MakeNowGroup.MISSING_1).contains("Havregrynsgröt med äpple och kanel"))
        assertTrue(state.names(MakeNowGroup.MISSING_2).contains("Krämig tomatsoppa"))
        // Cream and salmon expire tomorrow: their recipes come first.
        assertTrue(state.recipes(MakeNowGroup.HAS_ALL).first().expiring.isNotEmpty())
        assertEquals("demo mode sends nothing", before, graph.server.requests.size)
    }

    private companion object {
        const val SEED = 7
        const val UNKNOWN_KEYWORD = 99_999L
    }
}
