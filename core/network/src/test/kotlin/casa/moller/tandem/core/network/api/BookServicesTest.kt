package casa.moller.tandem.core.network.api

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.network.error.apiCall
import casa.moller.tandem.core.network.testing.Fixture
import casa.moller.tandem.core.network.testing.HttpsServerRule
import casa.moller.tandem.core.network.testing.emptyResponse
import casa.moller.tandem.core.network.testing.testClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Phase 2 book services (böcker lane) against an HTTPS MockWebServer replaying the `fas2/`
 * recordings of 2.6.13 and 2.7.1 (FACTS "Fas 2 a", "Fas 2 g").
 */
class BookServicesTest {
    @get:Rule
    val tandoor = HttpsServerRule()

    private val api by lazy { TandoorApiFactory(testClient()).create(tandoor.url("/"), { "tda_device_token" }) }

    @Test
    fun theRoleAndTheHouseholdComeFromAllPersonal() = runBlocking {
        for (version in VERSIONS) {
            tandoor.enqueue(Fixture.load("fas2/user-space-guest", version).toMockResponse())
            val guest = api.userSpaces.allPersonal().single()
            assertEquals(listOf("guest"), guest.groups.map { it.name })
            assertEquals(null, guest.household)
            assertEquals("/api/user-space/all_personal/", tandoor.takeRequest().url.encodedPath)

            tandoor.enqueue(Fixture.load("fas2/user-space-owner", version).toMockResponse())
            val owner = api.userSpaces.allPersonal().single()
            assertEquals(listOf("admin"), owner.groups.map { it.name })
            assertEquals("Contract Household", owner.household?.name)
            assertTrue(owner.active)
            tandoor.takeRequest()
        }
    }

    @Test
    fun aBookIsCreatedWithItsSharedUsers() = runBlocking {
        for (version in VERSIONS) {
            tandoor.enqueue(Fixture.load("fas2/recipe-book-create-shared", version).toMockResponse())
            val text = """{"name":"Favoriter (contract-admin)","description":"","shared":[{"id":3}],"filter":null}"""
            val payload = Json.parseToJsonElement(text).jsonObject
            val book = api.books.createBook(payload)
            assertEquals(listOf(3L), book.shared.map { it.id })
            assertEquals(1L, book.createdBy?.id)
            val request = tandoor.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/recipe-book/", request.url.encodedPath)
            assertEquals(payload, Json.parseToJsonElement(request.body!!.utf8()))
        }
    }

    @Test
    fun entriesAreCreatedListedByRecipeAndDeleted() = runBlocking {
        tandoor.enqueue(Fixture.load("fas2/recipe-book-entry-create-as-shared-member").toMockResponse())
        val created = api.books.createEntry(Json.parseToJsonElement("""{"book":2,"recipe":8}""").jsonObject)
        assertEquals(2L, created.book)
        assertEquals(8L, created.recipe)
        tandoor.takeRequest()

        tandoor.enqueue(Fixture.load("fas2/recipe-book-entry-list-by-recipe").toMockResponse())
        val listed = api.books.entriesOfRecipe(recipe = 14, page = null)
        assertEquals(listOf(14L), listed.results.map { it.recipe })
        assertEquals("14", tandoor.takeRequest().url.queryParameter("recipe"))

        tandoor.enqueue(emptyResponse(204))
        assertTrue(apiCall { api.books.deleteEntry(3) } is Outcome.Success)
        assertEquals("/api/recipe-book-entry/3/", tandoor.takeRequest().url.encodedPath)

        tandoor.enqueue(Fixture.load("fas2/recipe-book-entry-deleted-delete").toMockResponse())
        assertEquals(Outcome.Failure(TandemError.NotFound), apiCall { api.books.deleteEntry(3) })
        tandoor.takeRequest()
    }

    @Test
    fun wantToCookPatchesTheWholeKeywordList() = runBlocking {
        for (version in VERSIONS) {
            tandoor.enqueue(Fixture.load("fas2/recipe-patch-keywords-as-member", version).toMockResponse())
            val payload = Json.parseToJsonElement("""{"keywords":[{"name":"vill-laga"}]}""").jsonObject
            val recipe = api.books.patchRecipe(14, payload)
            val names = (recipe["keywords"] as JsonArray).map { (it.jsonObject["name"] as JsonPrimitive).content }
            assertTrue(names.toString(), "vill-laga" in names)
            val request = tandoor.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/api/recipe/14/", request.url.encodedPath)
            assertEquals(payload, Json.parseToJsonElement(request.body!!.utf8()))
        }
    }

    @Test
    fun aSavedFilterKeepsItsSearchAsAString() = runBlocking {
        for (version in VERSIONS) {
            tandoor.enqueue(Fixture.load("fas2/custom-filter-create-ids", version).toMockResponse())
            val filter = api.books.createFilter(
                Json.parseToJsonElement("""{"name":"Vill laga","search":"{\"keywords_or\":[8]}","shared":[]}""").jsonObject,
            )
            assertEquals("""{"keywords_or":[8]}""", filter.search)
            assertEquals("/api/custom-filter/", tandoor.takeRequest().url.encodedPath)
        }
    }

    private companion object {
        val VERSIONS = listOf("2.6.13", "2.7.1")
    }
}
