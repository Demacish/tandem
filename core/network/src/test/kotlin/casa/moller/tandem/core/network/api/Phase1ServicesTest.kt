package casa.moller.tandem.core.network.api

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.core.network.dto.CookLogCreateRequestDto
import casa.moller.tandem.core.network.dto.RecipeDto
import casa.moller.tandem.core.network.dto.ViewLogCreateRequestDto
import casa.moller.tandem.core.network.error.apiCall
import casa.moller.tandem.core.network.http.TokenProvider
import casa.moller.tandem.core.network.json.TandoorJson
import casa.moller.tandem.core.network.testing.Fixture
import casa.moller.tandem.core.network.testing.HttpsServerRule
import casa.moller.tandem.core.network.testing.testClient
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** The Phase 1 Retrofit services against an HTTPS MockWebServer replaying the 2.6.13 fixtures. */
class Phase1ServicesTest {
    @get:Rule
    val tandoor = HttpsServerRule()

    private val api by lazy { TandoorApiFactory(testClient()).create(tandoor.url("/"), TOKEN) }

    @Test
    fun flatIndex() = runBlocking {
        tandoor.enqueue(Fixture.load("fas1/recipe-flat").toMockResponse())
        val flat = api.recipes.flat()
        assertEquals(12, flat.size)
        val request = tandoor.takeRequest()
        assertEquals("/api/recipe/flat/", request.url.encodedPath)
        assertEquals("Bearer tda_device_token", request.headers["Authorization"])
    }

    @Test
    fun listSendsEveryFilterAndRepeatsArrays() = runBlocking {
        tandoor.enqueue(Fixture.load("fas1/recipe-list-keywords-or-parent").toMockResponse())
        val query = RecipeListQuery(
            text = "  svamp ",
            keywordsOr = setOf(3, 1),
            booksOr = setOf(7),
            foodsAndNot = setOf(9),
            includeChildren = false,
            ratingAtLeast = 4,
            timesCookedAtLeast = 1,
            cookedOnOrBefore = LocalDate(2026, 8, 12),
            viewedOnOrAfter = LocalDate(2026, 9, 27),
            updatedOnOrAfter = LocalDate(2026, 10, 10),
            newFirst = true,
            numRecent = 16,
            sortOrder = RecipeSortOrder.LAST_COOKED_DESC,
            pageSize = 500,
        )
        val page = api.recipes.list(query, page = 3)
        assertEquals(page.count, page.results.size)
        val url = tandoor.takeRequest().url
        assertEquals("/api/recipe/", url.encodedPath)
        assertEquals("svamp", url.queryParameter("query"))
        assertEquals(listOf("1", "3"), url.queryParameterValues("keywords_or"))
        assertEquals(listOf("7"), url.queryParameterValues("books_or"))
        assertEquals(listOf("9"), url.queryParameterValues("foods_and_not"))
        assertEquals("false", url.queryParameter("include_children"))
        assertEquals("4", url.queryParameter("rating_gte"))
        assertEquals("1", url.queryParameter("timescooked_gte"))
        // Plain dates only: with a time updatedon_gte answers 500 (FACTS "Fas 1 c").
        assertEquals("2026-08-12", url.queryParameter("cookedon_lte"))
        assertEquals("2026-09-27", url.queryParameter("viewedon_gte"))
        assertEquals("2026-10-10", url.queryParameter("updatedon_gte"))
        assertEquals("true", url.queryParameter("new"))
        assertEquals("16", url.queryParameter("num_recent"))
        assertEquals("-lastcooked", url.queryParameter("sort_order"))
        assertEquals("3", url.queryParameter("page"))
        assertEquals("100", url.queryParameter("page_size"))
        for (absent in listOf("keywords_and", "foods_or", "makenow", "random", "filter", "createdon_gte")) {
            assertNull(absent, url.queryParameter(absent))
        }
    }

    @Test
    fun firstPageAndEmptyQueryLeaveOutParameters() = runBlocking {
        tandoor.enqueue(Fixture.load("fas1/recipe-list-default").toMockResponse())
        api.recipes.list(RecipeListQuery(text = "   ", pageSize = 0), page = 1)
        val url = tandoor.takeRequest().url
        assertEquals(setOf("page_size"), url.queryParameterNames)
        assertEquals("1", url.queryParameter("page_size"))
    }

    @Test
    fun noSortOrderThatAnswers500() {
        val values = RecipeSortOrder.entries.map { it.apiValue }
        assertEquals(emptyList<String>(), values.filter { "times_cooked" in it })
    }

    @Test
    fun recipeAndRawJsonFromOneShape() = runBlocking {
        val fixture = Fixture.load("fas1/recipe-appelkaka")
        tandoor.enqueue(fixture.toMockResponse(), fixture.toMockResponse())
        val recipe = api.recipes.recipe(10)
        val raw = api.recipes.recipeJson(10)
        assertEquals("/api/recipe/10/", tandoor.takeRequest().url.encodedPath)
        assertEquals("/api/recipe/10/", tandoor.takeRequest().url.encodedPath)
        assertEquals(recipe, TandoorJson.decodeFromJsonElement(RecipeDto.serializer(), raw))
        assertEquals(10, raw["id"]!!.jsonPrimitive.int)
    }

    @Test
    fun privateAndDeletedRecipes() = runBlocking {
        tandoor.enqueue(
            Fixture.load("fas1/recipe-private-as-other-member").toMockResponse(),
            Fixture.load("fas1/recipe-deleted-get").toMockResponse(),
        )
        // 403 + JSON is classified like an invalid token (FACTS e); the data layer tells them
        // apart by context (other calls still work), see FACTS "konsekvenser" 7.
        assertEquals(Outcome.Failure(TandemError.Unauthorized(UnauthorizedSource.TANDOOR)), apiCall { api.recipes.recipe(7) })
        assertEquals(Outcome.Failure(TandemError.NotFound), apiCall { api.recipes.recipe(12) })
    }

    @Test
    fun smallTablesUseBigPages() = runBlocking {
        tandoor.enqueue(
            Fixture.load("fas1/keyword-list").toMockResponse(),
            Fixture.load("fas1/food-list").toMockResponse(),
            Fixture.load("fas1/unit-list").toMockResponse(),
            Fixture.load("fas1/meal-type-list").toMockResponse(),
            Fixture.load("fas1/recipe-book-list").toMockResponse(),
            Fixture.load("fas1/recipe-book-entry-list").toMockResponse(),
        )
        assertEquals(7, api.taxonomy.keywords(page = null).results.size)
        api.taxonomy.foods(page = 2)
        api.taxonomy.units(page = null)
        api.taxonomy.mealTypes(page = null)
        api.taxonomy.recipeBooks(page = null)
        api.taxonomy.recipeBookEntries(book = 1, page = null)
        val paths = List(6) { tandoor.takeRequest().url }
        assertEquals(
            listOf("/api/keyword/", "/api/food/", "/api/unit/", "/api/meal-type/", "/api/recipe-book/", "/api/recipe-book-entry/"),
            paths.map { it.encodedPath },
        )
        assertEquals(List(6) { "200" }, paths.map { it.queryParameter("page_size") })
        assertEquals("2", paths[1].queryParameter("page"))
        assertNull(paths[0].queryParameter("page"))
        assertEquals("1", paths[5].queryParameter("book"))
    }

    @Test
    fun mealPlanByDateRange() = runBlocking {
        tandoor.enqueue(Fixture.load("fas1/meal-plan-list").toMockResponse())
        val page = api.mealPlans.mealPlans(from = LocalDate(2026, 10, 11), to = LocalDate(2026, 10, 12), page = null)
        assertEquals(3, page.results.size)
        val url = tandoor.takeRequest().url
        assertEquals("/api/meal-plan/", url.encodedPath)
        assertEquals("2026-10-11", url.queryParameter("from_date"))
        assertEquals("2026-10-12", url.queryParameter("to_date"))
    }

    @Test
    fun preferences() = runBlocking {
        tandoor.enqueue(Fixture.load("fas1/user-preference").toMockResponse())
        assertEquals(2, api.preferences.userPreferences().single().ingredientDecimals)
        assertEquals("/api/user-preference/", tandoor.takeRequest().url.encodedPath)
    }

    @Test
    fun cookLogAndViewLog() = runBlocking {
        tandoor.enqueue(
            Fixture.load("fas1/cook-log-list-recipe").toMockResponse(),
            Fixture.load("fas1/cook-log-create").toMockResponse(),
            Fixture.load("fas1/view-log-create").toMockResponse(),
        )
        assertEquals(3, api.logs.cookLogs(recipe = 10, page = null).results.size)
        assertEquals("10", tandoor.takeRequest().url.queryParameter("recipe"))

        val created = api.logs.createCookLog(CookLogCreateRequestDto(recipe = 10, servings = 8, rating = 5, comment = "Gott!"))
        assertEquals(1L, created.id)
        val post = tandoor.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/api/cook-log/", post.url.encodedPath)
        val body = TandoorJson.parseToJsonElement(post.body!!.utf8()).jsonObject
        assertEquals(setOf("recipe", "servings", "rating", "comment"), body.keys)

        assertEquals(2L, api.logs.createViewLog(ViewLogCreateRequestDto(recipe = 2)).recipe)
        val view = tandoor.takeRequest()
        assertEquals("/api/view-log/", view.url.encodedPath)
        assertEquals("""{"recipe":2}""", view.body!!.utf8())
    }

    @Test
    fun subPathInstall() = runBlocking {
        val sub = TandoorApiFactory(testClient()).create(tandoor.url("/tandoor/"), TOKEN)
        tandoor.enqueue(Fixture.load("fas1/recipe-flat").toMockResponse())
        sub.recipes.flat()
        assertEquals("/tandoor/api/recipe/flat/", tandoor.takeRequest().url.encodedPath)
    }

    private companion object {
        val TOKEN = TokenProvider { "tda_device_token" }
    }
}
