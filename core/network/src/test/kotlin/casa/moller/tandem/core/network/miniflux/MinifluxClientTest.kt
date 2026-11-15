package casa.moller.tandem.core.network.miniflux

import casa.moller.tandem.core.model.error.InvalidUrlReason
import casa.moller.tandem.core.model.error.NotTandoorReason
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.core.network.testing.Fixture
import casa.moller.tandem.core.network.testing.HttpsServerRule
import casa.moller.tandem.core.network.testing.TestTls
import casa.moller.tandem.core.network.testing.htmlResponse
import casa.moller.tandem.core.network.testing.jsonResponse
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Fas 5 (rss lane, plan §7 #35): the recipe inbox's Miniflux client against an HTTPS MockWebServer that
 * answers with the recordings of a real Miniflux 2.3.4 (`api-schema/fixtures/miniflux/2.3.4/`,
 * MinifluxContractTest): the key goes only in `X-Auth-Token`, the requests are the ones the contract
 * test checked, every failure is typed, nothing past the size limit is read, and the address the user
 * types is normalized (https only).
 */
class MinifluxClientTest {
    @get:Rule
    val server = HttpsServerRule()

    private val client = MinifluxClient(
        MinifluxClient.httpClient(MinifluxClient.userAgent("0.9.0-test")) {
            sslSocketFactory(TestTls.clientTrust.sslSocketFactory(), TestTls.clientTrust.trustManager)
        },
    )

    private val base: HttpUrl by lazy { server.url("/rss/").toHttpUrl() }

    private fun recorded(name: String): MockResponse = Fixture.load(name, version = VERSION).toMockResponse()

    @Test
    fun meSendsTheKeyOnlyInItsHeader() = runBlocking {
        server.enqueue(recorded("me"))

        val me = client.me(base, KEY)

        assertTrue("$me", me is Outcome.Success)
        val request = server.takeRequest()
        assertEquals("/rss/v1/me", request.url.encodedPath)
        assertEquals(KEY, request.headers[MinifluxClient.HEADER_KEY])
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["Cookie"])
        assertEquals("Tandem/0.9.0-test (+https://github.com/Demacish/tandem)", request.headers["User-Agent"])
    }

    @Test
    fun aRefusedKeyIsUnauthorized() = runBlocking {
        server.enqueue(recorded("me-wrong-key"))
        assertEquals(Outcome.Failure(TandemError.Unauthorized(UnauthorizedSource.PROXY)), client.me(base, KEY))
        // A key that cannot be a header is refused before anything is sent.
        assertEquals(Outcome.Failure(TandemError.Unauthorized(UnauthorizedSource.PROXY)), client.me(base, "nyckel med ö"))
        assertEquals(1, server.server.requestCount)
    }

    @Test
    fun theCategoriesWithTheirUnreadCounts() = runBlocking {
        server.enqueue(recorded("categories-counts"))

        val categories = (client.categories(base, KEY) as Outcome.Success).value

        assertEquals(listOf("Recept", "All", "Nyheter"), categories.map { it.title })
        assertEquals(3, categories.first().totalUnread)
        val request = server.takeRequest()
        assertEquals("/rss/v1/categories", request.url.encodedPath)
        assertEquals("true", request.url.queryParameter("counts"))
    }

    @Test
    fun theUnreadEntriesNewestFirstWithImageAndFeed() = runBlocking {
        server.enqueue(recorded("entries-unread"))

        val page = (client.unreadEntries(base, KEY, categoryId = 2) as Outcome.Success).value

        assertEquals(3, page.total)
        assertEquals(listOf("Pannkakor med blåbär", "Linssoppa", "Kladdkaka"), page.entries.map { it.title })
        val first = page.entries.first()
        assertEquals("https://recipes.example.com/recept/pannkakor", first.url)
        assertEquals("Exempelköket", first.feed?.title)
        assertEquals(2L, first.feed?.category?.id)
        assertEquals(listOf("image/jpeg"), first.enclosures?.map { it.mimeType })
        assertTrue(page.entries[1].content.contains("linssoppa.jpg"))
        val request = server.takeRequest()
        assertEquals("/rss/v1/categories/2/entries", request.url.encodedPath)
        assertEquals("unread", request.url.queryParameter("status"))
        assertEquals("published_at", request.url.queryParameter("order"))
        assertEquals("desc", request.url.queryParameter("direction"))
        assertEquals("50", request.url.queryParameter("limit"))
    }

    @Test
    fun markReadPutsTheIds() = runBlocking {
        server.enqueue(recorded("entries-mark-read"))

        assertEquals(Outcome.Success(Unit), client.markRead(base, KEY, listOf(3, 3, 4)))

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/rss/v1/entries", request.url.encodedPath)
        assertEquals("""{"entry_ids":[3,4],"status":"read"}""", request.body?.utf8())
        // Nothing to mark: nothing is sent.
        assertEquals(Outcome.Success(Unit), client.markRead(base, KEY, emptyList()))
        assertEquals(1, server.server.requestCount)
    }

    @Test
    fun answersThatAreNotMinifluxAreTyped() = runBlocking {
        server.enqueue(recorded("not-api"))
        assertEquals(Outcome.Failure(TandemError.NotTandoor(NotTandoorReason.UNEXPECTED_STATUS, 404)), client.me(base, KEY))

        server.enqueue(htmlResponse(200))
        assertEquals(Outcome.Failure(TandemError.NotTandoor(NotTandoorReason.HTML_RESPONSE, 200)), client.me(base, KEY))

        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://login.example.com/").build())
        assertEquals(Outcome.Failure(TandemError.NotTandoor(NotTandoorReason.REDIRECT, 302)), client.me(base, KEY))

        server.enqueue(jsonResponse(200, "[1, 2]"))
        assertTrue(client.me(base, KEY).let { it is Outcome.Failure && it.error is TandemError.Decode })
    }

    @Test
    fun aDeletedCategoryThrottlingAndServerErrors() = runBlocking {
        server.enqueue(recorded("entries-unknown-category"))
        assertEquals(Outcome.Failure(TandemError.BadRequest()), client.unreadEntries(base, KEY, categoryId = 999_999))

        server.enqueue(jsonResponse(429, "{}", "Retry-After" to "30"))
        assertTrue(client.categories(base, KEY).let { it is Outcome.Failure && it.error is TandemError.Throttled })

        server.enqueue(jsonResponse(502, "{}"))
        assertEquals(Outcome.Failure(TandemError.ServerError(502)), client.categories(base, KEY))

        server.enqueue(MockResponse.Builder().code(403).body("Forbidden by the firewall").build())
        assertEquals(Outcome.Failure(TandemError.Blocked), client.categories(base, KEY))
    }

    @Test
    fun nothingPastTheSizeLimitIsRead() = runBlocking {
        val text = "x".repeat((MinifluxClient.MAX_BODY_BYTES + 10).toInt())
        val huge = """{"total": 1, "entries": [{"id": 1, "content": "$text"}]}"""
        server.enqueue(jsonResponse(200, huge))
        assertEquals(Outcome.Failure(TandemError.Decode("miniflux-too-large")), client.unreadEntries(base, KEY, categoryId = 2))
    }

    @Test
    fun theAddressIsNormalized() {
        fun normalized(input: String) = (MinifluxClient.normalize(input) as? Outcome.Success)?.value?.toString()
        assertEquals("https://rss.example.com/", normalized("rss.example.com"))
        assertEquals("https://rss.example.com/", normalized(" https://rss.example.com "))
        assertEquals("https://example.com/miniflux/", normalized("https://example.com/miniflux"))
        assertEquals("https://example.com/miniflux/", normalized("https://example.com/miniflux/v1/me?x=1#y"))
        assertEquals("https://rss.example.com:8443/", normalized("https://rss.example.com:8443/v1/"))
        assertEquals(
            Outcome.Failure(TandemError.InvalidUrl(InvalidUrlReason.CLEARTEXT_NOT_ALLOWED)),
            MinifluxClient.normalize("http://rss.example.com"),
        )
        assertEquals(Outcome.Failure(TandemError.InvalidUrl(InvalidUrlReason.EMPTY)), MinifluxClient.normalize("  "))
        assertEquals(Outcome.Failure(TandemError.InvalidUrl(InvalidUrlReason.UNSUPPORTED_SCHEME)), MinifluxClient.normalize("ftp://x"))
        assertEquals(
            Outcome.Failure(TandemError.InvalidUrl(InvalidUrlReason.MALFORMED)),
            MinifluxClient.normalize("https://user:pw@x.example"),
        )
    }

    private companion object {
        const val VERSION = "miniflux/2.3.4"
        const val KEY = "miniflux-test-key-0000"
    }
}
