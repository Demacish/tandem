package casa.moller.tandem.core.sync.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.database.entity.CookLogEntity
import casa.moller.tandem.core.database.entity.CookLogProblems
import casa.moller.tandem.core.database.entity.CookLogState
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.database.entity.RecipeOverviewEntity
import casa.moller.tandem.core.model.cooklog.CookLogDraft
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.sync.SyncPhase
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.DEMO_ACCOUNT_ID
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.testing.FakeTandoorServer
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import casa.moller.tandem.core.testing.fixtures.TandoorFixtures
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * "Klart!" -> Tandoor's cook log (feature #8) through the outbox, against the recorded 2.6.13
 * answers (`fas1/cook-log-create*.json`): offline first, the date that is kept, the outbox rules
 * (404/400/429/401, safe mode), demo and account isolation, and statistics without double counting.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CookLogRepositoryTest {
    private val graph = SyncTestGraph()
    private val server = graph.server
    private val repository = graph.cookLogs

    /** Bodies of every `POST /api/cook-log/`, in order. */
    private val posted = CopyOnWriteArrayList<JsonObject>()
    private val nextServerId = AtomicLong(100)

    @After
    fun tearDown() {
        graph.close()
    }

    private suspend fun overview(recipeId: Long, demo: Boolean = false): RecipeOverviewEntity {
        val database = if (demo) graph.demoDatabase else graph.real
        val account = if (demo) DEMO_ACCOUNT_ID else ACCOUNT_ID
        return database.recipeDao().getListItems(account, listOf(recipeId)).single().overview
    }

    private suspend fun entries(recipeId: Long): List<CookLogEntity> =
        graph.real.cookLogDao().getEntries(ACCOUNT_ID).filter { it.recipeId == recipeId }

    private suspend fun pending(): Int = repository.observePendingCount().first()

    private fun draft(recipeId: Long, rating: Int? = null, at: Instant = NOW, comment: String? = null) =
        CookLogDraft(recipeId = recipeId, servings = 4, rating = rating, comment = comment, cookedAt = at)

    private fun record(request: RecordedRequest): JsonObject = Json.parseToJsonElement(checkNotNull(request.body).utf8()).jsonObject.also {
        posted += it
    }

    /** Answers like Tandoor: the recorded 201 with the sent fields and a new id. */
    private fun echo(request: RecordedRequest): MockResponse {
        val sent = record(request)
        val template = Json.parseToJsonElement(checkNotNull(TandoorFixtures.exchange("fas1/cook-log-create").body)).jsonObject
        val created = JsonObject(template - "comment" + sent + ("id" to JsonPrimitive(nextServerId.incrementAndGet())))
        return FakeTandoorServer.json(201, created.toString())
    }

    private fun answerPosts(status: Int, body: String) {
        server.respond("POST", "/api/cook-log/") { request ->
            record(request)
            FakeTandoorServer.json(status, body)
        }
    }

    @Test
    fun anOfflineLogCountsAtOnceAndIsSentWithItsOwnDateOnceOnline() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        graph.network.online.value = false
        val recorded = Json.parseToJsonElement(TandoorFixtures.text("fas1/cook-log-create-backdated.json")).jsonObject
        val recordedBody = recorded.getValue("request").jsonObject.getValue("body").jsonObject
        val cookedAt = Instant.parse(recordedBody.getValue("created_at").jsonPrimitive.content)

        val logged = repository.logCooked(CookLogDraft(recipeId = 3, servings = 2, rating = 3, comment = " ", cookedAt = cookedAt))

        assertTrue("$logged", logged is Outcome.Success)
        val pancakes = overview(3)
        assertEquals("counted at once, offline", 1, pancakes.timesCooked)
        assertEquals(cookedAt.toEpochMilliseconds(), pancakes.lastCookedEpochMillis)
        assertEquals(3.0, checkNotNull(pancakes.rating), 0.0)
        assertEquals(1, pending())
        assertTrue("the outbox worker is scheduled", "outbox" in graph.scheduler.calls)
        assertEquals(DrainResult.RETRY, graph.outbox.drain(graph.remote))
        assertTrue(server.requestsTo("POST", "/api/cook-log/").isEmpty())

        graph.network.online.value = true
        server.respond("POST", "/api/cook-log/") { request ->
            record(request)
            FakeTandoorServer.fixture("fas1/cook-log-create-backdated")
        }
        assertEquals(DrainResult.DONE, graph.outbox.drain(graph.remote))

        // The same request as the recording: a blank comment is left out, created_at is the cooking time.
        val body = posted.single()
        assertEquals(recordedBody.keys, body.keys)
        listOf("recipe", "servings", "rating").forEach { assertEquals(it, recordedBody[it], body[it]) }
        assertEquals(cookedAt, Instant.parse(body.getValue("created_at").jsonPrimitive.content))
        val sent = entries(3).single()
        assertEquals(6L, sent.serverId)
        assertEquals(CookLogState.SENT, sent.state)
        assertEquals("the server kept the back date", cookedAt.toEpochMilliseconds(), sent.cookedAtEpochMillis)
        assertEquals(0, pending())
        assertEquals(1, overview(3).timesCooked)
    }

    @Test
    fun theNextSyncsReconcileTheEntryWithoutCountingItTwice() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        assertEquals(3, overview(10).timesCooked)
        assertEquals("own 5, 4, 5", 5.0, checkNotNull(overview(10).rating), 0.0)
        graph.network.online.value = false
        repository.logCooked(draft(10, rating = 1))
        assertEquals(4, overview(10).timesCooked)
        assertEquals("(5 + 4 + 5 + 1) / 4 rounds to 4", 4.0, checkNotNull(overview(10).rating), 0.0)

        // Back online: the run lists the server's log (without the entry) and then sends it.
        graph.network.online.value = true
        server.respond("POST", "/api/cook-log/") { echo(it) }
        graph.clock.advance(2.hours)
        assertEquals(SyncPhase.DONE, graph.engine.run(SyncReason.USER_REFRESH).phase)
        assertEquals(1, posted.size)
        assertEquals(CookLogState.SENT, entries(10).single { it.serverId == nextServerId.get() }.state)
        assertEquals(4, overview(10).timesCooked)

        // The next list has it: the server's copy replaces the sent one.
        val listed = Json.parseToJsonElement(checkNotNull(TandoorFixtures.exchange("fas1/cook-log-list-recipe").body)).jsonObject
        val created = buildJsonObject {
            put("id", nextServerId.get())
            put("recipe", 10)
            put("rating", 1)
            put("created_by", buildJsonObject { put("id", 1) })
            put("created_at", posted.single().getValue("created_at"))
        }
        val results = JsonArray(listed.getValue("results").jsonArray + created)
        server.respond("GET", "/api/cook-log/") { FakeTandoorServer.json(200, JsonObject(listed + ("results" to results)).toString()) }
        graph.clock.advance(2.hours)
        assertEquals(SyncPhase.DONE, graph.engine.run(SyncReason.USER_REFRESH).phase)

        assertEquals(4, overview(10).timesCooked)
        assertEquals(4.0, checkNotNull(overview(10).rating), 0.0)
        assertEquals(List(4) { CookLogState.SYNCED }, entries(10).map { it.state })
        assertEquals(1, posted.size)
    }

    @Test
    fun aDetailFetchKeepsTheRatingOfAnUnsentEntry() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        server.respond("POST", "/api/cook-log/") { FakeTandoorServer.json(503, "{}") }
        repository.logCooked(draft(10, rating = 1))

        assertTrue(graph.recipes.refreshRecipe(10) is Outcome.Success)

        assertEquals("the detail says 5.0; the unsent 1 still counts", 4.0, checkNotNull(overview(10).rating), 0.0)
        assertEquals(4, overview(10).timesCooked)
    }

    @Test
    fun aDeletedRecipeDropsTheEntryWithItsReasonAndNeverRetries() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        answerPosts(404, """{"detail":"Not found."}""")
        repository.logCooked(draft(2, rating = 5))
        assertEquals(1, overview(2).timesCooked)

        assertEquals(DrainResult.DONE, graph.outbox.drain(graph.remote))

        val dropped = entries(2).single()
        assertEquals(CookLogState.DROPPED, dropped.state)
        assertEquals(CookLogProblems.NOT_FOUND, dropped.problem)
        assertNull("no longer counted", overview(2).timesCooked)
        assertEquals(0, pending())
        assertEquals(DrainResult.DONE, graph.outbox.drain(graph.remote))
        assertEquals("never sent again", 1, posted.size)

        // A recipe that is no longer stored: dropped without a request.
        repository.logCooked(draft(77))
        assertEquals(DrainResult.DONE, graph.outbox.drain(graph.remote))
        assertEquals(1, posted.size)
        assertEquals(CookLogProblems.RECIPE_GONE, entries(77).single().problem)
    }

    @Test
    fun tandoorsAnswer400ForADeletedRecipeDropsTheEntryLike404() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        // What Tandoor 2.6.13 answers for a recipe deleted on the server (contract test, FACTS "Fas 1 f").
        answerPosts(400, """{"recipe":["Invalid pk \"2\" - object does not exist."]}""")
        repository.logCooked(draft(2, rating = 5))

        assertEquals(DrainResult.DONE, graph.outbox.drain(graph.remote))

        val dropped = entries(2).single()
        assertEquals(CookLogState.DROPPED, dropped.state)
        assertEquals(CookLogProblems.RECIPE_UNKNOWN, dropped.problem)
        assertEquals("not kept as a pending change", 0, pending())
        assertNull(overview(2).timesCooked)
    }

    @Test
    fun manyAnswers404NeverBecomeAStorm() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        answerPosts(404, """{"detail":"Not found."}""")
        (1L..6L).forEach { repository.logCooked(draft(it)) }

        assertEquals(DrainResult.STOPPED, graph.outbox.drain(graph.remote))

        assertEquals("the breaker stops after 5 answers 404 (plan 4.1)", 5, posted.size)
        assertEquals("the sixth waits", 1, pending())
        assertEquals(CookLogState.PENDING, entries(6).single().state)
    }

    @Test
    fun aRejectedEntryIsKeptAsFailedAndInTheExport() = runBlocking {
        graph.mirror.onSession(Session.LoggedIn(graph.account, "2.6.13", ServerCompatibility.SUPPORTED))
        graph.engine.run(SyncReason.FIRST_SYNC)
        answerPosts(400, """{"rating":["Ensure this value is less than or equal to 5."]}""")
        repository.logCooked(draft(2, rating = 5, comment = "Gott!"))

        assertEquals(DrainResult.DONE, graph.outbox.drain(graph.remote))

        assertEquals(CookLogState.FAILED, entries(2).single().state)
        assertEquals(OutboxState.FAILED, graph.real.outboxDao().observeAll(ACCOUNT_ID).first().single().state)
        assertEquals("shown as waiting until exported or retried", 1, pending())
        assertNull(overview(2).timesCooked)
        assertEquals(DrainResult.DONE, graph.outbox.drain(graph.remote))
        assertEquals("not retried automatically", 1, posted.size)

        val export = RoomPendingChangesExporter(graph.databases).exportAsJson()
        val change = Json.parseToJsonElement((export as Outcome.Success).value).jsonObject.getValue("changes").jsonArray.single().jsonObject
        assertEquals("COOK_LOG", change.getValue("operation").jsonPrimitive.content)
        assertEquals("FAILED", change.getValue("state").jsonPrimitive.content)
        val payload = change.getValue("payload").jsonObject
        assertEquals("Gott!", payload.getValue("comment").jsonPrimitive.content)
        assertEquals(5, payload.getValue("rating").jsonPrimitive.int)
        assertEquals(NOW, Instant.parse(payload.getValue("created_at").jsonPrimitive.content))

        // The rejected entry holds nothing back: the next "Klart!" of the recipe is sent.
        server.respond("POST", "/api/cook-log/") { echo(it) }
        repository.logCooked(draft(2, rating = 4))
        assertEquals(DrainResult.DONE, graph.outbox.drain(graph.remote))
        assertEquals(2, posted.size)
        assertEquals(listOf(CookLogState.FAILED, CookLogState.SENT), entries(2).map { it.state })
        assertEquals(1, overview(2).timesCooked)
    }

    @Test
    fun anAnswer429KeepsTheEntryWithoutAnAutomaticRetry() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        server.respond("POST", "/api/cook-log/") { request ->
            record(request)
            MockResponse.Builder()
                .code(429)
                .addHeader("Content-Type", "application/json")
                .addHeader("Retry-After", "120")
                .body("""{"detail":"Request was throttled."}""")
                .build()
        }
        repository.logCooked(draft(2))

        assertEquals(DrainResult.STOPPED, graph.outbox.drain(graph.remote))
        assertEquals(DrainResult.STOPPED, graph.outbox.drain(graph.remote))

        assertEquals(1, posted.size)
        assertEquals(1, pending())
        assertEquals(CookLogState.PENDING, entries(2).single().state)
        assertEquals(1, overview(2).timesCooked)
        assertTrue(graph.guard.check(ACCOUNT_ID, background = true) is TandemError.Throttled)
    }

    @Test
    fun aRejectedTokenKeepsTheEntryAndALoginToTheSameAccountSendsIt() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        answerPosts(403, """{"detail":"Invalid token."}""")
        repository.logCooked(draft(2))

        assertEquals(DrainResult.STOPPED, graph.outbox.drain(graph.remote))
        assertEquals(1, pending())
        assertEquals(CookLogState.PENDING, entries(2).single().state)

        // "Logga in igen" with the same user and space: a new token, the same account id.
        graph.sources.token = "tda_new_login"
        server.respond("POST", "/api/cook-log/") { echo(it) }
        assertEquals(SyncPhase.DONE, graph.engine.run(SyncReason.USER_REFRESH).phase)

        assertEquals(2, posted.size)
        assertTrue("a retried entry is looked up first", server.requestsTo("GET", "/api/cook-log/").any { "recipe=2" in it })
        assertEquals(CookLogState.SENT, entries(2).single().state)
        assertEquals(0, pending())
    }

    @Test
    fun aRetryAfterALostAnswerFindsTheEntryInsteadOfPostingItTwice() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        // The server stores the entry, but the answer never arrives (here: a 502 from a proxy).
        answerPosts(502, "{}")
        repository.logCooked(draft(2, rating = 4))
        assertEquals(DrainResult.RETRY, graph.outbox.drain(graph.remote))
        server.respond("GET", "/api/cook-log/") { request ->
            val stored = buildJsonObject {
                put("id", 55)
                put("recipe", 2)
                put("rating", 4)
                put("created_by", buildJsonObject { put("id", 1) })
                put("created_at", posted.single().getValue("created_at"))
            }
            val results = if (request.url.queryParameter("recipe") == "2") listOf(stored) else emptyList()
            val page = buildJsonObject {
                put("count", results.size)
                put("results", JsonArray(results))
            }
            FakeTandoorServer.json(200, page.toString())
        }

        assertEquals(DrainResult.DONE, graph.outbox.drain(graph.remote))

        assertEquals("no second POST", 1, posted.size)
        val sent = entries(2).single()
        assertEquals(55L, sent.serverId)
        assertEquals(CookLogState.SENT, sent.state)
        assertEquals(1, overview(2).timesCooked)
    }

    @Test
    fun safeModeHoldsTheEntryUntilTheVersionIsTestedAgain() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        val untested = graph.remote.copy(account = graph.account.copy(serverVersion = "2.7.3"))
        graph.sources.source.value = untested
        repository.logCooked(draft(2))
        assertEquals(1, overview(2).timesCooked)

        assertEquals(DrainResult.PAUSED, graph.outbox.drain(untested))
        assertTrue(posted.isEmpty())
        assertEquals(OutboxState.PAUSED, graph.real.outboxDao().observeAll(ACCOUNT_ID).first().single().state)

        graph.sources.source.value = graph.remote
        server.respond("POST", "/api/cook-log/") { echo(it) }
        assertEquals(DrainResult.DONE, graph.outbox.drain(graph.remote))
        assertEquals(1, posted.size)
        assertEquals(CookLogState.SENT, entries(2).single().state)
    }

    @Test
    fun demoEntriesStayInTheDemoDatabaseUntilTheReset() = runBlocking {
        graph.sources.source.value = ActiveSource.Demo
        assertTrue(graph.demo.ensureSeeded() is Outcome.Success)
        val recipeId = 3L
        val before = checkNotNull(overview(recipeId, demo = true).timesCooked)
        assertEquals(5.0, checkNotNull(overview(recipeId, demo = true).rating), 0.0)

        assertTrue(repository.logCooked(draft(recipeId, rating = 1)) is Outcome.Success)

        assertEquals(before + 1, overview(recipeId, demo = true).timesCooked)
        assertTrue("rated in demo mode: the log decides", checkNotNull(overview(recipeId, demo = true).rating) < 5.0)
        assertEquals("never queued", 0, pending())
        assertEquals(0, graph.demoDatabase.outboxDao().observeCount(DEMO_ACCOUNT_ID).first())
        assertTrue("no network in demo mode: ${server.requests}", server.requests.isEmpty())
        assertTrue(graph.real.cookLogDao().getEntries(DEMO_ACCOUNT_ID).isEmpty())

        // A new day rewrites the demo content and keeps the entry.
        graph.clock.days.value = graph.clock.days.value.plus(1, DateTimeUnit.DAY)
        graph.demo.ensureSeeded()
        assertEquals(before + 1, overview(recipeId, demo = true).timesCooked)

        assertTrue(graph.demo.resetDemoData() is Outcome.Success)
        assertEquals(before, overview(recipeId, demo = true).timesCooked)
        assertTrue(graph.demoDatabase.cookLogDao().getEntries(DEMO_ACCOUNT_ID).none { it.localId < 0 })
    }

    @Test
    fun anEntryBelongsToTheAccountThatLoggedItAndGoesWithItsLogout() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        repository.logCooked(draft(2))
        assertEquals(1, pending())

        val other = graph.remote.copy(account = graph.account.copy(id = "account-b"))
        graph.sources.source.value = other
        assertEquals(0, pending())
        assertEquals(DrainResult.DONE, graph.outbox.drain(other))
        assertTrue("B sends nothing of A's", posted.isEmpty())

        graph.sources.source.value = graph.remote
        assertEquals(1, pending())
        graph.real.accountDao().deleteAccountData(ACCOUNT_ID)
        assertEquals(0, pending())
        assertTrue(graph.real.cookLogDao().getEntries(ACCOUNT_ID).isEmpty())

        graph.sources.source.value = ActiveSource.None
        val refused = repository.logCooked(draft(2))
        assertTrue("$refused", refused is Outcome.Failure)
        assertNotNull((refused as Outcome.Failure).error)
    }

    private companion object {
        /** "Klart!" pressed at noon on the test day (FakeClock.TODAY), whole seconds. */
        val NOW: Instant = Instant.parse("2026-10-06T12:00:00Z")
    }
}
