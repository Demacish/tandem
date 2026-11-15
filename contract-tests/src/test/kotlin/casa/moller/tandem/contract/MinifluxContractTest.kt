package casa.moller.tandem.contract

import java.io.File
import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Fas 5 (rss lane, plan §7 #35, §8.4 "RSS-inkorg"): the recipe inbox's Miniflux API against a REAL,
 * throwaway Miniflux (the project's own image, pinned by version and digest) and PostgreSQL started
 * by miniflux-contract.yml, with a feed served inside the runner (contract-tests/miniflux/recipes.xml).
 *
 * What the app (core:network `MinifluxClient`) rests on: the API key in `X-Auth-Token` (created here
 * with the admin's basic auth, as a user does under Settings → API Keys); `/v1/me` answers 200 to a
 * good key and 401 with `error_message` to a wrong or missing one; `/v1/categories?counts=true` has
 * `total_unread`; `/v1/categories/{id}/entries?status=unread&order=published_at&direction=desc`
 * gives the newest first with `url`, `feed.title`, `enclosures` and the HTML `content`; `PUT
 * /v1/entries` `{"entry_ids", "status": "read"}` answers 204 and the entry leaves the unread list.
 *
 * Recordings go to `build/contract-recordings/miniflux/<tag>/` and are committed to
 * `api-schema/fixtures/miniflux/<tag>/` (the client's unit tests read them). Soft checks as in the
 * Tandoor phases: every fact is recorded and the last step compares [EXPECTED].
 *
 * Skipped unless MINIFLUX_URL is set. Never run against a real Miniflux.
 */
@EnabledIfEnvironmentVariable(named = "MINIFLUX_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MinifluxContractTest {
    private val baseUrl = System.getenv("MINIFLUX_URL").orEmpty().trimEnd('/')
    private val username = System.getenv("MINIFLUX_ADMIN_USERNAME").orEmpty()
    private val password = System.getenv("MINIFLUX_ADMIN_PASSWORD").orEmpty()
    private val feedUrl = System.getenv("MINIFLUX_FEED_URL").orEmpty()
    private val tag = System.getenv("MINIFLUX_TAG").orEmpty().ifBlank { "local" }
    private val recordDir = File(System.getProperty("tandem.contract.recordDir") ?: "build/contract-recordings", "miniflux/$tag")
    private val sanitizer = Sanitizer(listOf(password))
    private val http = TandoorHttp(baseUrl, sanitizer, recordDir, numbered = false)
    private val facts = Facts()
    private val mismatches = mutableListOf<String>()

    private var apiKey = ""
    private var recipesCategory = 0L
    private var newsCategory = 0L
    private var entryIds = listOf<Long>()

    @BeforeAll
    fun setUp() {
        assertTrue(username.isNotBlank() && password.isNotBlank(), "MINIFLUX_ADMIN_USERNAME/PASSWORD must be set")
        assertTrue(feedUrl.isNotBlank(), "MINIFLUX_FEED_URL must be set")
        recordDir.deleteRecursively()
        recordDir.mkdirs()
        facts["minifluxTag"] = tag
    }

    @AfterAll
    fun cleanUp() {
        facts["mismatches"] = mismatches
        facts.write(File(recordDir, "observed-facts.json"), sanitizer)
    }

    @Test
    @Order(10)
    fun theVersionAndAnApiKey() {
        val version = admin("version", "GET", "/v1/version")
        observe("version.status", version.status)
        facts["version.version"] = (version.json as? JsonObject)?.stringAt("version")
        // The key is a secret: known to the sanitizer before the exchange is written.
        val created = admin("api-key-create", "POST", "/v1/api-keys", obj("description" to "tandem-contract"), record = false)
        apiKey = (created.json as? JsonObject)?.stringAt("token").orEmpty()
        sanitizer.addSecret(apiKey)
        http.record(created)
        observe("apiKey.status", created.status)
        observe("apiKey.hasToken", apiKey.length >= MIN_KEY_LENGTH)
    }

    @Test
    @Order(20)
    fun theKeyWorksAndAWrongOneIsRefused() {
        val me = api("me", "GET", "/v1/me")
        observe("me.status", me.status)
        observe("me.hasId", (me.json as? JsonObject)?.longAt("id") != null)
        val wrong = http.call("me-wrong-key", "GET", "/v1/me", authorization = null, extraHeaders = mapOf(KEY_HEADER to WRONG_KEY))
        observe("meWrongKey.status", wrong.status)
        observe("meWrongKey.json", wrong.contentType?.contains("json") == true)
        observe("meWrongKey.hasErrorMessage", (wrong.json as? JsonObject)?.stringAt("error_message") != null)
        val none = http.call("me-no-key", "GET", "/v1/me")
        observe("meNoKey.status", none.status)
        // Not the API (the app never follows a redirect): only recorded.
        val notApi = http.call("not-api", "GET", "/v1/nothing-here", authorization = null, extraHeaders = mapOf(KEY_HEADER to apiKey))
        facts["notApi.status"] = notApi.status
        facts["notApi.contentType"] = notApi.contentType
    }

    @Test
    @Order(30)
    fun categoriesAndAFeed() {
        recipesCategory = idOf(api("category-create", "POST", "/v1/categories", obj("title" to RECIPES)), "id")
        newsCategory = idOf(api("category-create-other", "POST", "/v1/categories", obj("title" to NEWS)), "id")
        observe("category.created", recipesCategory > 0 && newsCategory > 0)
        // Miniflux reads the feed when it is created.
        val feed = api("feed-create", "POST", "/v1/feeds", obj("feed_url" to feedUrl, "category_id" to recipesCategory))
        observe("feed.status", feed.status)
        observe("feed.hasId", (feed.json as? JsonObject)?.longAt("feed_id") != null)
    }

    @Test
    @Order(40)
    fun theCategoriesWithUnreadCounts() {
        val categories = api("categories-counts", "GET", "/v1/categories?counts=true")
        observe("categories.status", categories.status)
        val list = categories.json.objects()
        val recipes = list.firstOrNull { it.longAt("id") == recipesCategory }
        val news = list.firstOrNull { it.longAt("id") == newsCategory }
        observe("categories.recipesTitle", recipes?.stringAt("title"))
        observe("categories.recipesUnread", recipes?.longAt("total_unread"))
        observe("categories.recipesFeeds", recipes?.longAt("feed_count"))
        observe("categories.newsUnread", news?.longAt("total_unread"))
        val plain = api("categories", "GET", "/v1/categories")
        observe("categoriesPlain.hasCounts", plain.json.objects().any { it.containsKey("total_unread") })
    }

    @Test
    @Order(50)
    fun theUnreadEntriesNewestFirst() {
        val page = api("entries-unread", "GET", unreadPath(recipesCategory))
        observe("entries.status", page.status)
        val body = page.json as? JsonObject
        val entries = body?.get("entries").objects()
        entryIds = entries.mapNotNull { it.longAt("id") }
        observe("entries.total", body?.longAt("total"))
        observe("entries.titles", entries.map { it.stringAt("title") })
        observe("entries.urls", entries.map { it.stringAt("url") })
        observe("entries.status.values", entries.map { it.stringAt("status") }.distinct())
        observe("entries.feedTitle", entries.map { it.objAt("feed")?.stringAt("title") }.distinct())
        observe("entries.feedCategory", entries.map { it.objAt("feed")?.objAt("category")?.longAt("id") == recipesCategory }.distinct())
        observe("entries.hasPublishedAt", entries.all { it.stringAt("published_at") != null })
        val first = entries.firstOrNull()
        observe("entries.firstEnclosureTypes", first?.arrAt("enclosures")?.objects()?.map { it.stringAt("mime_type") })
        observe("entries.firstEnclosureUrls", first?.arrAt("enclosures")?.objects()?.map { it.stringAt("url") })
        observe("entries.secondContentHasImage", entries.getOrNull(1)?.stringAt("content")?.contains(SOUP_IMAGE) == true)
        val other = api("entries-unread-other", "GET", unreadPath(newsCategory))
        observe("entriesOther.total", (other.json as? JsonObject)?.longAt("total"))
        val limited = api("entries-unread-limit", "GET", unreadPath(recipesCategory, limit = 1))
        observe("entriesLimit.count", (limited.json as? JsonObject)?.get("entries").objects().size)
        observe("entriesLimit.total", (limited.json as? JsonObject)?.longAt("total"))
    }

    @Test
    @Order(60)
    fun markingReadTakesAnEntryOffTheUnreadList() {
        val first = entryIds.firstOrNull() ?: error("no entries")
        val read = api("entries-mark-read", "PUT", "/v1/entries", obj("entry_ids" to listOf(first), "status" to "read"))
        observe("markRead.status", read.status)
        observe("markRead.emptyBody", read.bodyText.isEmpty())
        val again = api("entries-unread-after-read", "GET", unreadPath(recipesCategory))
        val ids = (again.json as? JsonObject)?.get("entries").objects().mapNotNull { it.longAt("id") }
        observe("afterRead.firstGone", first !in ids)
        observe("afterRead.total", (again.json as? JsonObject)?.longAt("total"))
        // Marking an entry read twice is no error (the app may resend after a lost answer).
        val twice = api("entries-mark-read-again", "PUT", "/v1/entries", obj("entry_ids" to listOf(first), "status" to "read"))
        observe("markReadAgain.status", twice.status)
        val unknown = api("entries-unknown-category", "GET", unreadPath(UNKNOWN_ID))
        facts["unknownCategory.status"] = unknown.status
        facts["unknownCategory.body"] = unknown.json
    }

    @Test
    @Order(90)
    fun compareWithBaseline() {
        for ((key, expected) in EXPECTED) {
            if (facts[key] != Facts.toJson(expected)) mismatches += "$key: expected ${Facts.toJson(expected)}, observed ${facts[key]}"
        }
        assertTrue(mismatches.isEmpty(), "Observed facts differ from the Miniflux baseline:\n" + mismatches.joinToString("\n"))
    }

    private fun unreadPath(category: Long, limit: Int = APP_LIMIT) =
        "/v1/categories/$category/entries?status=unread&order=published_at&direction=desc&limit=$limit"

    private fun idOf(exchange: Exchange, key: String): Long = (exchange.json as? JsonObject)?.longAt(key) ?: 0L

    /** A call with the API key, as the app makes it. */
    private fun api(name: String, method: String, path: String, body: JsonElement? = null): Exchange = http.call(
        name,
        method,
        path,
        authorization = null,
        body = (body ?: JsonObject(emptyMap()).takeIf { method in BODY_METHODS })?.toString()?.toRequestBody(JSON),
        recordBody = body,
        extraHeaders = mapOf(KEY_HEADER to apiKey),
    )

    /** A call with the admin's basic auth (only to create the key; the app never sends a password). */
    private fun admin(name: String, method: String, path: String, body: JsonElement? = null, record: Boolean = true): Exchange {
        val basic = "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        sanitizer.addSecret(basic.removePrefix("Basic "))
        return http.call(
            name,
            method,
            path,
            authorization = basic,
            body = body?.toString()?.toRequestBody(JSON),
            recordBody = body,
            record = record,
        )
    }

    private fun observe(key: String, value: Any?) {
        facts[key] = value
    }

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to Facts.toJson(v) })

    private companion object {
        val JSON = "application/json".toMediaType()
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        const val KEY_HEADER = "X-Auth-Token"
        const val WRONG_KEY = "tandem-contract-wrong-key-0000000000000000"
        const val MIN_KEY_LENGTH = 16
        const val UNKNOWN_ID = 999_999L

        /** The app's page size (MinifluxClient.PAGE_SIZE). */
        const val APP_LIMIT = 50
        const val RECIPES = "Recept"
        const val NEWS = "Nyheter"
        const val SOUP_IMAGE = "https://recipes.example.com/bilder/linssoppa.jpg"

        /** What the app rests on. */
        val EXPECTED: Map<String, Any?> = linkedMapOf(
            "version.status" to 200,
            "apiKey.status" to 201,
            "apiKey.hasToken" to true,
            "me.status" to 200,
            "me.hasId" to true,
            "meWrongKey.status" to 401,
            "meWrongKey.json" to true,
            "meWrongKey.hasErrorMessage" to true,
            "meNoKey.status" to 401,
            "category.created" to true,
            "feed.status" to 201,
            "feed.hasId" to true,
            "categories.status" to 200,
            "categories.recipesTitle" to RECIPES,
            "categories.recipesUnread" to 3,
            "categories.recipesFeeds" to 1,
            "categories.newsUnread" to 0,
            "categoriesPlain.hasCounts" to false,
            "entries.status" to 200,
            "entries.total" to 3,
            "entries.titles" to listOf("Pannkakor med blåbär", "Linssoppa", "Kladdkaka"),
            "entries.urls" to listOf(
                "https://recipes.example.com/recept/pannkakor",
                "https://recipes.example.com/recept/linssoppa",
                "https://recipes.example.com/recept/kladdkaka",
            ),
            "entries.status.values" to listOf("unread"),
            "entries.feedTitle" to listOf("Exempelköket"),
            "entries.feedCategory" to listOf(true),
            "entries.hasPublishedAt" to true,
            "entries.firstEnclosureTypes" to listOf("image/jpeg"),
            "entries.firstEnclosureUrls" to listOf("https://recipes.example.com/bilder/pannkakor.jpg"),
            "entries.secondContentHasImage" to true,
            "entriesOther.total" to 0,
            "entriesLimit.count" to 1,
            "entriesLimit.total" to 3,
            "markRead.status" to 204,
            "markRead.emptyBody" to true,
            "afterRead.firstGone" to true,
            "afterRead.total" to 2,
            "markReadAgain.status" to 204,
        )
    }
}
