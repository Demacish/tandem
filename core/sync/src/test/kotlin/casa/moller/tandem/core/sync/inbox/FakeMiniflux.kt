package casa.moller.tandem.core.sync.inbox

import casa.moller.tandem.core.datastore.RecipeInboxKeyStore
import casa.moller.tandem.core.datastore.RecipeInboxRecord
import casa.moller.tandem.core.datastore.RecipeInboxStore
import casa.moller.tandem.core.network.miniflux.MinifluxClient
import casa.moller.tandem.core.sync.testing.TestTls
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect

/**
 * Fas 5 (rss lane): an in-process Miniflux with the answers of Miniflux 2.3.4
 * (api-schema/fixtures/miniflux/2.3.4/FACTS.md): `X-Auth-Token`, `/v1/me`, the categories with their
 * unread counts, a category's unread entries newest first (400 for a category that does not exist),
 * and `PUT /v1/entries` marking read. Stateful: marked entries leave the unread list.
 */
internal class FakeMiniflux {
    data class Post(val id: Long, val category: Long, val title: String, val published: String, val read: Boolean = false)

    private val server = MockWebServer()

    /** Every request as `METHOD /path?query`, in arrival order. */
    val requests: MutableList<String> = CopyOnWriteArrayList()

    /** The key the server takes. */
    @Volatile
    var key: String = KEY

    /** When true, every connection is closed at once (the server cannot be reached). */
    @Volatile
    var down: Boolean = false

    /** Answers a request before the routes (null = not handled). */
    @Volatile
    var override: ((RecordedRequest) -> MockResponse?)? = null

    val categories: MutableMap<Long, String> = ConcurrentHashMap(mapOf(1L to "All", 2L to "Recept", 3L to "Nyheter"))
    val posts: MutableMap<Long, Post> = ConcurrentHashMap()

    /** The ids each `PUT /v1/entries` marked read. */
    val markedRead: MutableList<List<Long>> = CopyOnWriteArrayList()

    val baseUrl: String
        get() = server.url("/miniflux/").toString()

    fun start(): FakeMiniflux {
        server.useHttps(TestTls.server.sslSocketFactory())
        server.protocolNegotiationEnabled = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        }
        server.start()
        return this
    }

    fun close() = server.close()

    fun post(id: Long, title: String, day: Int, category: Long = 2) {
        posts[id] = Post(id, category, title, "2026-11-%02dT08:00:00Z".format(day))
    }

    /** The client as the app builds it, trusting the test CA. */
    fun client(): MinifluxClient = MinifluxClient(
        MinifluxClient.httpClient("Tandem/0-test") { sslSocketFactory(TestTls.client.sslSocketFactory(), TestTls.client.trustManager) },
    )

    private fun answer(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath.removePrefix("/miniflux")
        requests += "${request.method} $path" + (request.url.encodedQuery?.let { "?$it" } ?: "")
        if (down) return MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
        override?.invoke(request)?.let { return it }
        if (request.headers["X-Auth-Token"] != key) return json(401, """{"error_message":"access unauthorized"}""")
        val entries = Regex("/v1/categories/(\\d+)/entries").matchEntire(path)
        return when {
            request.method == "GET" && path == "/v1/me" -> json(200, """{"id":1,"username":"contract-admin"}""")
            request.method == "GET" && path == "/v1/categories" -> json(200, categoriesJson())
            request.method == "GET" && entries != null -> entriesJson(entries.groupValues[1].toLong())
            request.method == "PUT" && path == "/v1/entries" -> markRead(request.body?.utf8().orEmpty())
            else -> MockResponse.Builder().code(404).addHeader("Content-Type", "text/plain").body("404 page not found\n").build()
        }
    }

    private fun categoriesJson(): String = categories.entries.sortedBy { it.key }.joinToString(",", "[", "]") { (id, title) ->
        val unread = posts.values.count { it.category == id && !it.read }
        """{"id":$id,"title":"$title","user_id":1,"hide_globally":false,"feed_count":1,"total_unread":$unread}"""
    }

    private fun entriesJson(category: Long): MockResponse {
        if (category !in categories) return json(400, """{"error_message":"invalid category ID"}""")
        val unread = posts.values.filter { it.category == category && !it.read }.sortedByDescending { it.published }
        val list = unread.joinToString(",", "[", "]") { post ->
            """{"id":${post.id},"user_id":1,"feed_id":7,"status":"unread","title":"${post.title}",""" +
                """"url":"https://recipes.example.com/recept/${post.id}","content":"<p>Text om ${post.title}.</p>",""" +
                """"published_at":"${post.published}","enclosures":[{"url":"https://recipes.example.com/bilder/${post.id}.jpg",""" +
                """"mime_type":"image/jpeg"}],"feed":{"id":7,"title":"Exempelköket","site_url":"https://recipes.example.com/",""" +
                """"category":{"id":$category,"title":"${categories[category]}"}}}"""
        }
        return json(200, """{"total":${unread.size},"entries":$list}""")
    }

    private fun markRead(body: String): MockResponse {
        val ids = Json.parseToJsonElement(body).jsonObject.getValue("entry_ids").jsonArray.map { it.jsonPrimitive.long }
        markedRead += ids
        ids.forEach { id -> posts.computeIfPresent(id) { _, post -> post.copy(read = true) } }
        return MockResponse.Builder().code(204).build()
    }

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    companion object {
        const val KEY = "miniflux-test-key-0000"
    }
}

/** [RecipeInboxStore] in memory. */
internal class FakeRecipeInboxStore : RecipeInboxStore {
    val records = MutableStateFlow<Map<String, RecipeInboxRecord>>(emptyMap())

    override fun observe(accountId: String): Flow<RecipeInboxRecord> = records.map { it[accountId] ?: RecipeInboxRecord() }

    override suspend fun update(accountId: String, transform: (RecipeInboxRecord) -> RecipeInboxRecord) {
        records.update { all ->
            val next = transform(all[accountId] ?: RecipeInboxRecord())
            if (next == RecipeInboxRecord()) all - accountId else all + (accountId to next)
        }
    }

    override suspend fun retainOnly(accountIds: Set<String>) = records.update { all -> all.filterKeys { it in accountIds } }

    override suspend fun accounts(): Set<String> = records.value.keys
}

/** [RecipeInboxKeyStore] in memory. */
internal class FakeRecipeInboxKeyStore : RecipeInboxKeyStore {
    val keys = MutableStateFlow<Map<String, String>>(emptyMap())

    override val accountsWithKey: Flow<Set<String>> = keys.map { it.keys }

    override suspend fun store(accountId: String, key: String) = keys.update { it + (accountId to key) }

    override suspend fun remove(accountId: String) = keys.update { it - accountId }

    override suspend fun <T> useKey(accountId: String, block: suspend (key: String) -> T): T? = keys.value[accountId]?.let { block(it) }

    override suspend fun retainOnly(accountIds: Set<String>) = keys.update { all -> all.filterKeys { it in accountIds } }
}
