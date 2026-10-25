package casa.moller.tandem.core.network.api

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.network.dto.IngredientParserRequestDto
import casa.moller.tandem.core.network.dto.ShoppingBulkCheckDto
import casa.moller.tandem.core.network.error.apiCall
import casa.moller.tandem.core.network.testing.Fixture
import casa.moller.tandem.core.network.testing.HttpsServerRule
import casa.moller.tandem.core.network.testing.emptyResponse
import casa.moller.tandem.core.network.testing.testClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Phase 2 shopping services (inköp-data lane) against an HTTPS MockWebServer replaying the
 * `fas2/` recordings of 2.6.13 and 2.7.1 (FACTS "Fas 2" b-e).
 */
class ShoppingServicesTest {
    @get:Rule
    val tandoor = HttpsServerRule()

    private val api by lazy { TandoorApiFactory(testClient()).create(tandoor.url("/"), { "tda_device_token" }) }

    @Test
    fun updatedAfterIsUrlEncoded() = runBlocking {
        for (version in VERSIONS) {
            tandoor.enqueue(Fixture.load("fas2/shopping-list-entry-list-updated-after", version).toMockResponse())
            val page = api.shopping.entries(updatedAfter = "2000-01-15T10:00:00.123456+01:00", page = null)
            assertTrue(page.timestamp != null)
            val url = tandoor.takeRequest().url
            // An unencoded "+" becomes a space and Tandoor answers with the whole list (FACTS "Fas 2 b").
            assertTrue(url.encodedQuery!!, "%2B01:00" in url.encodedQuery!! || "%2B01%3A00" in url.encodedQuery!!)
            assertFalse(url.encodedQuery!!, "+" in url.encodedQuery!!)
            assertEquals("2000-01-15T10:00:00.123456+01:00", url.queryParameter("updated_after"))
            assertEquals("200", url.queryParameter("page_size"))
        }
    }

    @Test
    fun theListDecodesWithFoodUnitAndListRecipe() = runBlocking {
        for (version in VERSIONS) {
            tandoor.enqueue(Fixture.load("fas2/shopping-list-entry-list-mealplan", version).toMockResponse())
            val page = api.shopping.entries(updatedAfter = null, page = null)
            val first = page.results.first()
            assertEquals("potatis", first.food?.name)
            assertEquals("kg", first.unit?.name)
            assertEquals(8L, first.listRecipeData?.mealplan)
            assertEquals("Potatisgratäng", first.listRecipeData?.recipeData?.name)
            assertEquals("contract-admin", first.createdBy?.displayName)
            assertTrue(tandoor.takeRequest().url.queryParameter("updated_after") == null)
        }
    }

    @Test
    fun createSendsTheQueuedPayloadAsItIs() = runBlocking {
        tandoor.enqueue(Fixture.load("fas2/shopping-list-entry-create").toMockResponse())
        val payload = Json.parseToJsonElement("""{"food":{"name":"mjölk"},"unit":{"name":"l"},"amount":2}""").jsonObject
        val created = api.shopping.createEntry(payload)
        assertEquals(4L, created.id)
        val request = tandoor.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/shopping-list-entry/", request.url.encodedPath)
        assertEquals(payload, Json.parseToJsonElement(request.body!!.utf8()))
    }

    @Test
    fun bulkCheckSendsIdsAndChecked() = runBlocking {
        tandoor.enqueue(Fixture.load("fas2/shopping-list-entry-bulk-check", "2.7.1").toMockResponse())
        val answer = api.shopping.bulkCheck(ShoppingBulkCheckDto(ids = listOf(5, 6), checked = true))
        assertEquals(listOf(5L, 6L), answer.ids)
        val body = Json.parseToJsonElement(tandoor.takeRequest().body!!.utf8()).jsonObject
        assertEquals(Json.parseToJsonElement("""{"ids":[5,6],"checked":true}"""), JsonObject(body.filterKeys { it != "timestamp" }))
    }

    @Test
    fun aDeletedEntryIsNotFound() = runBlocking {
        tandoor.enqueue(Fixture.load("fas2/shopping-list-entry-deleted-patch").toMockResponse())
        val result = apiCall { api.shopping.patchEntry(4, JsonObject(emptyMap())) }
        assertEquals(Outcome.Failure(TandemError.NotFound), result)
        assertEquals("/api/shopping-list-entry/4/", tandoor.takeRequest().url.encodedPath)

        tandoor.enqueue(emptyResponse(204))
        val deleted = apiCall { api.shopping.deleteEntry(4) }
        assertTrue(deleted is Outcome.Success)
        assertEquals("DELETE", tandoor.takeRequest().method)
    }

    @Test
    fun theParserTakesManyLinesInOneCall() = runBlocking {
        for (version in VERSIONS) {
            tandoor.enqueue(Fixture.load("fas2/ingredient-parser-many", version).toMockResponse())
            val parsed = api.supermarkets.parseIngredients(IngredientParserRequestDto(ingredients = listOf("2 dl grädde", "mjölk")))
            assertEquals("grädde", parsed.ingredients.first().food?.name)
            assertEquals(2.0, parsed.ingredients.first().amount, 0.0)
            val body = Json.parseToJsonElement(tandoor.takeRequest().body!!.utf8()).jsonObject
            assertEquals(setOf("ingredients"), body.keys)
        }
    }

    @Test
    fun storesCarryTheirAisleOrder() = runBlocking {
        for (version in VERSIONS) {
            tandoor.enqueue(Fixture.load("fas2/supermarket-list", version).toMockResponse())
            val store = api.supermarkets.supermarkets(page = null).results.single()
            assertEquals(listOf("Grönsaker", "Mejeri", "Övrigt"), store.categoryToSupermarket.sortedBy { it.order }.map { it.category?.name })
            tandoor.takeRequest()
        }
    }

    private companion object {
        val VERSIONS = listOf("2.6.13", "2.7.1")
    }
}
