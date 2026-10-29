package casa.moller.tandem.core.network.api

import casa.moller.tandem.core.network.testing.Fixture
import casa.moller.tandem.core.network.testing.HttpsServerRule
import casa.moller.tandem.core.network.testing.testClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Fas S (hemma-data lane): the pantry, FOOD_ALIAS and batch services against an HTTPS MockWebServer
 * replaying the `fas-s/` recordings of 2.6.13 and 2.7.1 (FACTS "Fas S" a, b, d, e).
 */
class PantryServicesTest {
    @get:Rule
    val tandoor = HttpsServerRule()

    private val api by lazy { TandoorApiFactory(testClient()).create(tandoor.url("/"), { "tda_device_token" }) }

    @Test
    fun locationsAndEntriesDecodeWithTheirHousehold() = runBlocking {
        for (version in VERSIONS) {
            tandoor.enqueue(Fixture.load("fas-s/inventory-location-list", version).toMockResponse())
            val locations = api.inventory.locations(page = null)
            assertEquals(listOf("Kyl", "Frys", "Skafferiet"), locations.results.map { it.name })
            assertEquals(true, locations.results[1].isFreezer)
            assertEquals("Contract Household", locations.results[0].household?.name)
            assertEquals("/api/inventory-location/", tandoor.takeRequest().url.encodedPath)

            tandoor.enqueue(Fixture.load("fas-s/inventory-entry-list", version).toMockResponse())
            val entries = api.inventory.entries(page = null)
            val first = entries.results.first()
            assertEquals("kikärtor", first.food?.name)
            assertEquals("burk", first.unit?.name)
            assertEquals(1L, first.inventoryLocation?.id)
            // Without `empty` (any value lists the empty entries too, FACTS "Fas S b").
            assertNull(tandoor.takeRequest().url.queryParameter("empty"))
        }
    }

    @Test
    fun theExistenceCheckAsksForTheEmptyEntriesToo() = runBlocking {
        tandoor.enqueue(Fixture.load("fas-s/inventory-entry-list").toMockResponse())
        api.inventory.entries(page = null, empty = true)
        assertEquals("true", tandoor.takeRequest().url.queryParameter("empty"))
    }

    @Test
    fun anEntryIsCreatedWithTheQueuedBodyAsItIs() = runBlocking {
        for (version in VERSIONS) {
            val recorded = Fixture.load("fas-s/inventory-entry-create", version)
            tandoor.enqueue(recorded.toMockResponse())
            val location = """{"id":1,"name":"Kyl","is_freezer":false,"household":{"id":1,"name":"Contract Household"}}"""
            val body = Json.parseToJsonElement(
                """{"inventory_location":$location,"food":{"name":"kikärtor"},"unit":{"name":"burk"},""" +
                    """"amount":2.0,"expires":"2000-01-17","note":"","sub_location":null}""",
            ).jsonObject
            val created = api.inventory.createEntry(body)
            assertEquals(1L, created.id)
            assertEquals("2000-01-17", created.expires)
            val request = tandoor.takeRequest()
            assertEquals("POST", request.method)
            assertEquals(body, Json.parseToJsonElement(request.body!!.utf8()))
        }
    }

    @Test
    fun batchUpdateAnswersAnEmptyObject() = runBlocking {
        for (version in VERSIONS) {
            tandoor.enqueue(Fixture.load("fas-s/food-batch-update-on-hand", version).toMockResponse())
            val body = Json.parseToJsonElement("""{"foods":[55,61,62],"on_hand":true}""").jsonObject
            assertEquals(JsonObject(emptyMap()), api.pantryFoods.batchUpdate(body))
            val request = tandoor.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/api/food/batch_update/", request.url.encodedPath)
        }
    }

    @Test
    fun foodAliasesAreListedByType() = runBlocking {
        for (version in VERSIONS) {
            tandoor.enqueue(Fixture.load("fas-s/automation-list-food-alias", version).toMockResponse())
            val page = api.automations.automations(type = "FOOD_ALIAS", page = null)
            assertEquals(listOf("2000000000015", "KIKÄRTOR 380G EKO"), page.results.map { it.param1 })
            assertEquals(listOf("Tandem: streckkod", "Tandem: kvitto"), page.results.map { it.name })
            assertEquals("FOOD_ALIAS", tandoor.takeRequest().url.queryParameter("type"))
        }
    }

    @Test
    fun makeNowIsSentAsAnInteger() = runBlocking {
        for (version in VERSIONS) {
            tandoor.enqueue(Fixture.load("fas-s/recipe-makenow-1", version).toMockResponse())
            val page = api.recipes.list(RecipeListQuery(makeNow = 1, pageSize = 100))
            assertTrue(page.results.any { it.name == "Hummus" })
            val url = tandoor.takeRequest().url
            assertEquals("1", url.queryParameter("makenow"))
            assertEquals("100", url.queryParameter("page_size"))
        }
    }

    private companion object {
        val VERSIONS = listOf("2.6.13", "2.7.1")
    }
}
