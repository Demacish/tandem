package casa.moller.tandem.core.network.paging

import casa.moller.tandem.core.network.api.RecipeListQuery
import casa.moller.tandem.core.network.api.TandoorApiFactory
import casa.moller.tandem.core.network.api.list
import casa.moller.tandem.core.network.dto.PageDto
import casa.moller.tandem.core.network.http.TokenProvider
import casa.moller.tandem.core.network.testing.Fixture
import casa.moller.tandem.core.network.testing.HttpsServerRule
import casa.moller.tandem.core.network.testing.testClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PaginatorTest {
    @get:Rule
    val tandoor = HttpsServerRule()

    @get:Rule
    val evil = HttpsServerRule()

    private val api by lazy { TandoorApiFactory(testClient()).create(tandoor.url("/"), TokenProvider { "tda_device_token" }) }
    private val query = RecipeListQuery(pageSize = 4)

    @Test
    fun followsRecordedPagesAgainstTheAccountsBaseUrl() = runBlocking {
        // The recorded next links point at https://tandoor.example.com (the proxy's Host).
        tandoor.enqueue(
            Fixture.load("fas1/recipe-list-page-1").toMockResponse(),
            Fixture.load("fas1/recipe-list-page-2").toMockResponse(),
            Fixture.load("fas1/recipe-list-page-3").toMockResponse(),
        )
        val result = Paginator.fetchAll { page -> api.recipes.list(query, page) }
        assertTrue(result.complete)
        assertEquals(3, result.pages)
        assertEquals(12, result.count)
        assertEquals(12, result.items.size)
        assertEquals(12, result.items.mapNotNull { it.id }.toSet().size)
        assertNull(result.timestamp)

        val urls = List(3) { tandoor.takeRequest().url }
        assertEquals(listOf(null, "2", "3"), urls.map { it.queryParameter("page") })
        assertTrue(urls.all { it.queryParameter("page_size") == "4" && it.encodedPath == "/api/recipe/" })
        assertTrue(urls.all { it.host == tandoor.server.hostName && it.port == tandoor.server.port })
    }

    @Test
    fun crossOriginNextLinkIsNeverRequested() = runBlocking {
        val hostile = evil.url("/steal/?page=2&page_size=1000&token=please")
        val page1 = Fixture.load("fas1/recipe-list-page-1").toMockResponse(mapOf("next" to JsonPrimitive(hostile)))
        val page2 = Fixture.load("fas1/recipe-list-page-3").toMockResponse(mapOf("next" to JsonNull))
        tandoor.enqueue(page1, page2)

        val result = Paginator.fetchAll { page -> api.recipes.list(query, page) }
        assertTrue(result.complete)
        assertEquals(2, result.pages)
        // Only the page number was taken from the link: same base, same filters and page size.
        tandoor.takeRequest()
        val second = tandoor.takeRequest()
        assertEquals("/api/recipe/", second.url.encodedPath)
        assertEquals("2", second.url.queryParameter("page"))
        assertEquals("4", second.url.queryParameter("page_size"))
        assertNull(second.url.queryParameter("token"))
        assertEquals("Bearer tda_device_token", second.headers["Authorization"])
        assertNull("the hostile host never sees a request", evil.server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun stopsOnALinkWithoutAUsablePageNumber() = runBlocking {
        val result = Paginator.fetchAll { pageOf(1, next = "https://tandoor.example.com/api/recipe/?cursor=abc") }
        assertFalse(result.complete)
        assertEquals(1, result.pages)
    }

    @Test
    fun stopsWhenThePageDoesNotAdvance() = runBlocking {
        val requested = mutableListOf<Int>()
        val result = Paginator.fetchAll { page ->
            requested += page
            pageOf(page, next = "https://tandoor.example.com/api/recipe/?page=$page")
        }
        assertFalse(result.complete)
        assertEquals(listOf(1), requested)

        val backwards = Paginator.fetchAll { page -> pageOf(page, next = if (page == 1) "?page=3" else "?page=2") }
        assertFalse(backwards.complete)
        assertEquals(2, backwards.pages)
    }

    @Test
    fun stopsAtMaxPages() = runBlocking {
        val result = Paginator.fetchAll(maxPages = 5) { page -> pageOf(page, next = "/api/recipe/?page=${page + 1}") }
        assertFalse(result.complete)
        assertEquals(5, result.pages)
        assertEquals(5, result.items.size)
    }

    @Test
    fun cancellationStopsThePaging() = runBlocking {
        val firstPageDone = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requested = mutableListOf<Int>()
        val job = async {
            Paginator.fetchAll { page ->
                requested += page
                if (page == 2) {
                    firstPageDone.complete(Unit)
                    release.await()
                }
                pageOf(page, next = "?page=${page + 1}")
            }
        }
        firstPageDone.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals("no page after the cancellation", listOf(1, 2), requested)
    }

    @Test
    fun errorsPropagate() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { Paginator.fetchAll<Int> { page -> if (page == 2) error("boom") else pageOf(page, next = "?page=2") } }
        }
    }

    @Test
    fun pageNumbers() {
        assertEquals(2, Paginator.pageNumberOf("https://tandoor.example.com/api/recipe/?page=2&page_size=4"))
        assertEquals(3, Paginator.pageNumberOf("http://127.0.0.1:8080/api/recipe/?page_size=4&page=3"))
        assertEquals(7, Paginator.pageNumberOf("/api/keyword/?page=7"))
        assertEquals(7, Paginator.pageNumberOf("?page=7"))
        assertNull("DRF's link to page 1 has no page", Paginator.pageNumberOf("https://tandoor.example.com/api/recipe/?page_size=4"))
        assertNull(Paginator.pageNumberOf("?page=0"))
        assertNull(Paginator.pageNumberOf("?page=-1"))
        assertNull(Paginator.pageNumberOf("?page=two"))
        assertNull(Paginator.pageNumberOf("?page=2&page=3"))
        assertNull(Paginator.pageNumberOf(""))
        assertNull(Paginator.pageNumberOf("?page=99999999999"))
    }

    private fun pageOf(number: Int, next: String?): PageDto<Int> = PageDto(count = 1000, next = next, results = listOf(number))
}
