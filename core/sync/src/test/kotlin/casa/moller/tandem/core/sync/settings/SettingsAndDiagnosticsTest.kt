package casa.moller.tandem.core.sync.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.common.AppBuildInfo
import casa.moller.tandem.core.common.crash.CrashLogStore
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.model.diagnostics.OutboxKind
import casa.moller.tandem.core.model.diagnostics.OutboxProblem
import casa.moller.tandem.core.model.diagnostics.OutboxSummary
import casa.moller.tandem.core.model.diagnostics.SyncHealth
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.SyncPhase
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.sync.SyncSchedule
import casa.moller.tandem.core.sync.data.DefaultDiagnosticsRepository
import casa.moller.tandem.core.sync.data.DefaultOfflineStorageRepository
import casa.moller.tandem.core.sync.data.RoomPendingChangesExporter
import casa.moller.tandem.core.sync.testing.FakeTandoorServer
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import casa.moller.tandem.core.sync.work.WorkManagerSyncScheduler
import casa.moller.tandem.core.testing.data.FakeTimerController
import casa.moller.tandem.core.testing.fixtures.TandoorFixtures
import casa.moller.tandem.core.testing.time.TestClock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Phase 1b settings and diagnostics in the sync (inställningar lane): "Download all recipes", the
 * full sync timing, the periodic interval, Sync and storage, and the sanitized diagnostics log.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SettingsAndDiagnosticsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val graph = SyncTestGraph()

    @After
    fun tearDown() {
        graph.close()
    }

    private suspend fun storedDetails(): Set<Long> = graph.real.recipeDao().getStoredDetailIds(ACCOUNT_ID).toSet()

    /** `recipe-list-default` with new `updated_at` values for some recipes. */
    private fun recipeListWith(vararg updatedAt: Pair<Long, String>): String {
        val changes = updatedAt.toMap()
        val body = Json.parseToJsonElement(TandoorFixtures.exchange("fas1/recipe-list-default").body.orEmpty()).jsonObject
        val results = body.getValue("results").jsonArray.map { element ->
            val recipe = element.jsonObject
            changes[recipe.getValue("id").jsonPrimitive.long]?.let { JsonObject(recipe + ("updated_at" to JsonPrimitive(it))) } ?: recipe
        }
        return JsonObject(body + ("results" to JsonArray(results))).toString()
    }

    /** `recipe-flat` with one more recipe. */
    private fun flatWith(id: Long, name: String): String {
        val flat = Json.parseToJsonElement(TandoorFixtures.exchange("fas1/recipe-flat").body.orEmpty()).jsonArray
        val extra = JsonObject(mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(name), "image" to JsonNull))
        return JsonArray(flat + extra).toString()
    }

    @Test
    fun downloadAllFetchesEveryRecipeAndItsPhoto() = runBlocking {
        graph.settings.state.value = graph.settings.state.value.copy(downloadAllRecipes = true)

        val result = graph.engine.run(SyncReason.FIRST_SYNC)

        assertEquals(SyncPhase.DONE, result.phase)
        // Recipe 12 answers 404 (deleted on the server) and is removed; the other 11 are stored.
        assertEquals((1L..11L).toSet(), storedDetails())
        val photos = graph.real.recipeDao().getAllOverviews(ACCOUNT_ID).mapNotNull { it.imageUrl }.toSet()
        assertTrue(photos.isNotEmpty())
        assertTrue("every photo was prefetched", graph.images.urls.containsAll(photos))
        // Still at most two detail requests at a time (plan 3.5, 4.1).
        assertTrue(graph.server.maxParallelDetails.get() <= SyncSchedule.MAX_PARALLEL_DETAIL_FETCHES)
    }

    @Test
    fun theFirstSyncIsStoredAsTheFullSyncTiming() = runBlocking {
        assertNull(graph.diagnosticsStore.state.value.lastFullSync)

        graph.engine.run(SyncReason.FIRST_SYNC)

        val timing = graph.diagnosticsStore.state.value.lastFullSync
        assertNotNull(timing)
        timing!!
        assertEquals("recipes in recipe/flat", 12, timing.recipeCount)
        assertFalse(timing.duration.isNegative())
    }

    @Test
    fun aRunThatReadsOnlyChangedRecipesIsNoFullSync() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        val first = graph.diagnosticsStore.state.value.lastFullSync
        graph.clock.advance(2.minutes)

        graph.engine.run(SyncReason.USER_REFRESH)

        assertEquals(first, graph.diagnosticsStore.state.value.lastFullSync)
    }

    // --- fix-data (0.2.0 review) ---

    @Test
    fun downloadAllFetchesAgainWhatChangedOnTheServer() = runBlocking {
        graph.settings.state.value = graph.settings.state.value.copy(downloadAllRecipes = true)
        graph.engine.run(SyncReason.FIRST_SYNC)
        val pyttipanna = graph.server.requestsTo("GET", "/api/recipe/1/").size
        val pasta = graph.server.requestsTo("GET", "/api/recipe/2/").size
        // A household member edits recipe 1 on the web a minute after it was stored.
        val editedAt = java.time.Instant.ofEpochMilli(graph.clock.nowMillis() + 60_000L).toString()
        graph.server.respond("GET", "/api/recipe/") { request ->
            when {
                HOME_QUERIES.any { request.url.queryParameter(it) != null } -> FakeTandoorServer.json(200, FakeTandoorServer.EMPTY_PAGE)
                request.url.queryParameter("updatedon_gte") != null -> FakeTandoorServer.json(200, recipeListWith(1L to editedAt))
                else -> FakeTandoorServer.fixture("fas1/recipe-list-default")
            }
        }
        graph.clock.advance(2.hours)

        graph.engine.run(SyncReason.USER_REFRESH)

        assertEquals("the changed recipe is fetched again", pyttipanna + 1, graph.server.requestsTo("GET", "/api/recipe/1/").size)
        assertEquals("the others are up to date", pasta, graph.server.requestsTo("GET", "/api/recipe/2/").size)

        graph.clock.advance(2.hours)
        graph.engine.run(SyncReason.USER_REFRESH)
        assertEquals("once is enough", pyttipanna + 1, graph.server.requestsTo("GET", "/api/recipe/1/").size)
    }

    @Test
    fun withoutDownloadAllAnUnpinnedChangedRecipeIsNotFetched() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        // Stored once (not pinned, not planned, not viewed), then changed on the web.
        graph.details.fetch(graph.remote, 1L, isBackground = false)
        assertTrue(1L in storedDetails())
        val editedAt = java.time.Instant.ofEpochMilli(graph.clock.nowMillis() + 60_000L).toString()
        graph.server.respond("GET", "/api/recipe/") { request ->
            when {
                HOME_QUERIES.any { request.url.queryParameter(it) != null } -> FakeTandoorServer.json(200, FakeTandoorServer.EMPTY_PAGE)
                else -> FakeTandoorServer.json(200, recipeListWith(1L to editedAt))
            }
        }
        val before = graph.server.requestsTo("GET", "/api/recipe/1/").size
        graph.clock.advance(2.hours)

        graph.engine.run(SyncReason.USER_REFRESH)

        assertEquals(before, graph.server.requestsTo("GET", "/api/recipe/1/").size)
    }

    @Test
    fun aNewProcessSkipsPhotosThatAreInTheDiskCache() = runBlocking {
        graph.settings.state.value = graph.settings.state.value.copy(downloadAllRecipes = true)
        graph.engine.run(SyncReason.FIRST_SYNC)
        val photos = graph.real.recipeDao().getAllOverviews(ACCOUNT_ID).mapNotNull { it.imageUrl }.distinct()
        assertTrue("the fixtures have photos", photos.size >= 2)
        // A periodic run in a new process: nothing in memory, the disk cache has every photo but one.
        graph.prefetched.clear()
        graph.images.urls.clear()
        graph.images.diskCache += photos.drop(1)
        graph.clock.advance(2.hours)

        graph.engine.run(SyncReason.PERIODIC)

        assertEquals(listOf(photos.first()), graph.images.urls.toList())
    }

    @Test
    fun theFullSyncTimingLeavesOutTheDownloadAllWork() = runBlocking {
        graph.settings.state.value = graph.settings.state.value.copy(downloadAllRecipes = true)
        // Every detail takes 10 s on the (fake) clock.
        FakeTandoorServer.RECIPES.forEach { (id, fixture) ->
            graph.server.respond("GET", "/api/recipe/$id/") {
                synchronized(graph.clock) { graph.clock.advance(10.seconds) }
                FakeTandoorServer.fixture(fixture)
            }
        }
        val startedAt = graph.clock.nowMillis()

        graph.engine.run(SyncReason.FIRST_SYNC)

        assertTrue("the details were fetched", graph.clock.nowMillis() - startedAt >= 110_000L)
        val timing = checkNotNull(graph.diagnosticsStore.state.value.lastFullSync)
        assertTrue("index, overviews and the usual details only: ${timing.duration}", timing.duration < 10.seconds)
    }

    @Test
    fun aReReadBecauseARecipeBecameVisibleKeepsTheFirstSyncTiming() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        val first = checkNotNull(graph.diagnosticsStore.state.value.lastFullSync)
        // A recipe is shared with the user: it is in the index but not among the changed overviews.
        graph.server.respond("GET", "/api/recipe/flat/") { FakeTandoorServer.json(200, flatWith(13L, "Delat recept")) }
        graph.clock.advance(2.hours)

        val result = graph.engine.run(SyncReason.USER_REFRESH)

        assertEquals(SyncPhase.DONE, result.phase)
        assertTrue("the shared recipe is in the index", 13L in graph.real.recipeDao().getRecipeIds(ACCOUNT_ID))
        assertEquals(first, graph.diagnosticsStore.state.value.lastFullSync)
    }

    @Test
    fun aFailedFirstSyncIsNotStored() = runBlocking {
        graph.server.respond("GET", "/api/recipe/flat/") { FakeTandoorServer.json(500, "{}") }

        graph.engine.run(SyncReason.FIRST_SYNC)

        assertNull(graph.diagnosticsStore.state.value.lastFullSync)
    }

    @Test
    fun thePeriodicRequestTakesTheChosenInterval() {
        val hourly = WorkManagerSyncScheduler.periodicRequest(60)
        assertEquals(60.minutes.inWholeMilliseconds, hourly.workSpec.intervalDuration)
        assertEquals(60.minutes.inWholeMilliseconds, hourly.workSpec.initialDelay)
        // Never below WorkManager's minimum.
        val tooShort = WorkManagerSyncScheduler.periodicRequest(5)
        assertEquals(SyncSchedule.PERIODIC.inWholeMilliseconds, tooShort.workSpec.intervalDuration)
    }

    @Test
    fun storageCountsDownloadedRecipesAndEstimatesTheRest() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        graph.imageCache.size = 1_000_000
        val storage = storageRepository().observeStorage().first { it.recipeCount > 0 }

        assertEquals(12, storage.recipeCount)
        assertEquals(storedDetails().size, storage.downloadedCount)
        assertEquals(1_000_000L, storage.imageCacheBytes)
        assertTrue("the database file has a size", storage.databaseBytes > 0)
        assertTrue("remaining recipes have an estimate", storage.estimatedDownloadBytes > 0)
    }

    @Test
    fun clearingTheImageCacheEmptiesItAndMeasuresAgain() = runBlocking {
        graph.imageCache.size = 5_000_000
        val repository = storageRepository()
        assertEquals(5_000_000L, repository.observeStorage().first().imageCacheBytes)

        assertEquals(Outcome.Success(Unit), repository.clearImageCache())

        assertEquals(1, graph.imageCache.clears.get())
        assertEquals(0L, repository.observeStorage().first().imageCacheBytes)
    }

    @Test
    fun theDiagnosticsLogHasTheFactsButNothingPersonal() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        graph.real.outboxDao().enqueueViewLog(ACCOUNT_ID, 3, """{"recipe":3}""", 1)
        val failed = graph.real.outboxDao().getByState(ACCOUNT_ID, OutboxState.PENDING).single()
        graph.real.outboxDao().setFailed(
            listOf(failed.id),
            "HTTP 400 for https://tandoor.example.com/api/view-log/ as cook@example.com",
        )
        val crashDir = tmp.newFolder("crash-logs")
        val crashes = CrashLogStore({ crashDir })
        crashes.write("Tandem crashed\njava.lang.IllegalStateException: <url>\n", 1_000)
        val repository = diagnosticsRepository(crashes)
        repository.recordColdStart(843.milliseconds)

        val export = (repository.exportLog() as Outcome.Success).value
        val text = export.text

        assertTrue(export.fileName.startsWith("tandem-diagnostics-") && export.fileName.endsWith(".txt"))
        listOf(
            "Tandem diagnostics",
            "Version: 0.2.0-dev (20)",
            "Tandoor: 2.6.13",
            "Cold start: 843 ms",
            "Full sync:",
            "12 recipes",
            "VIEW_LOG recipe FAILED: 1",
            "Exact alarms allowed: yes",
            "java.lang.IllegalStateException",
        ).forEach { assertTrue("export has \"$it\"", it in text) }
        // No server address, user name, token or e-mail address; the outbox payload is not included.
        val host = graph.account.baseUrl.substringAfter("://").substringBefore(":").substringBefore("/")
        listOf(graph.account.baseUrl, host, "tandoor.example.com", "cook@example.com", "tda_", """"recipe":3""")
            .forEach { assertFalse("export must not contain \"$it\"", it in text) }
    }

    @Test
    fun theSnapshotHasTheVersionsAndTimings() = runBlocking {
        val crashDir = tmp.newFolder("empty")
        val repository = diagnosticsRepository(CrashLogStore({ crashDir }))
        repository.recordFullSync(41.seconds, 312)

        val snapshot = repository.observeDiagnostics().first { it.lastFullSync != null }

        assertEquals("0.2.0-dev", snapshot.appVersion)
        assertEquals("sideload", snapshot.distribution)
        assertEquals(312, snapshot.lastFullSync?.recipeCount)
        assertEquals(41.seconds, snapshot.lastFullSync?.duration)
    }

    /**
     * Phase 2 (koppling lane): "blockerad"-diagnostik: the circuit breaker opened by five 404/400
     * answers, until when, and the outbox per kind of change with its last error, in the snapshot
     * Mer → Diagnostik shows and in the exported log.
     */
    @Test
    fun theSnapshotShowsWhatIsHeldBackPerKindOfChange() = runBlocking {
        val outbox = graph.real.outboxDao()
        fun row(op: String, entity: String, localId: Long? = null, serverId: Long? = null) =
            OutboxEntity(accountId = ACCOUNT_ID, op = op, entity = entity, localId = localId, serverId = serverId, createdAtEpochMillis = 1)
        val entry = outbox.insert(row(OutboxOps.CREATE, OutboxEntities.SHOPPING_ENTRY, localId = -3))
        outbox.markRetry(entry, "circuit-breaker")
        val meal = outbox.insert(row(OutboxOps.PATCH, OutboxEntities.MEAL_PLAN, serverId = 7))
        outbox.setFailed(listOf(meal), "http-400")
        outbox.insert(row(OutboxOps.RECIPE_KEYWORDS, OutboxEntities.RECIPE, serverId = 2))
        repeat(SyncHealth.CLIENT_ERROR_LIMIT) { graph.guard.record(ACCOUNT_ID, null, TandemError.NotFound) }

        val repository = diagnosticsRepository(CrashLogStore({ tmp.newFolder("none") }))
        val health = repository.observeDiagnostics().first { it.syncHealth.outbox.size == 3 && it.syncHealth.pause != null }.syncHealth

        assertEquals(SyncPause.CIRCUIT_BREAKER, health.pause)
        assertEquals(graph.clock.millis + SyncSchedule.BREAKER_PAUSE.inWholeMilliseconds, health.pausedUntil?.toEpochMilliseconds())
        assertEquals(
            listOf(
                OutboxSummary(OutboxKind.SHOPPING_ENTRY, waiting = 1, failed = 0, lastProblem = OutboxProblem.CIRCUIT_BREAKER),
                OutboxSummary(OutboxKind.MEAL_PLAN, waiting = 0, failed = 1, lastProblem = OutboxProblem.BAD_REQUEST),
                OutboxSummary(OutboxKind.WANT_TO_COOK, waiting = 1, failed = 0, lastProblem = null),
            ),
            health.outbox,
        )
        assertEquals(2, health.waiting)
        assertEquals(1, health.failed)

        val text = (repository.exportLog() as Outcome.Success).value.text
        listOf(
            "Paused: CIRCUIT_BREAKER",
            "Kind SHOPPING_ENTRY: waiting 1, failed 0, last error CIRCUIT_BREAKER",
            "Kind MEAL_PLAN: waiting 0",
        )
            .forEach { assertTrue("export has \"$it\"", it in text) }
    }

    /** Phase 2 (koppling lane): "Exportera väntande ändringar" covers the new types with their temporary ids. */
    @Test
    fun thePendingChangesExportHasThePhase2Types() = runBlocking {
        val outbox = graph.real.outboxDao()
        val listRecipe = outbox.insert(
            OutboxEntity(
                accountId = ACCOUNT_ID,
                op = OutboxOps.CREATE,
                entity = OutboxEntities.SHOPPING_LIST_RECIPE,
                localId = -4,
                payload = """{"recipe":2,"servings":4.0,"mealplan":-5}""",
                refs = """{"mealplan":"meal_plan"}""",
                createdAtEpochMillis = 1,
            ),
        )
        outbox.insert(
            OutboxEntity(
                accountId = ACCOUNT_ID,
                op = OutboxOps.CREATE,
                entity = OutboxEntities.SHOPPING_ENTRY,
                localId = -6,
                payload = """{"food":{"name":"grädde"},"amount":2.0,"list_recipe":-4}""",
                refs = """{"list_recipe":"shopping_list_recipe"}""",
                dependsOn = listRecipe,
                createdAtEpochMillis = 2,
            ),
        )
        val json = (RoomPendingChangesExporter(graph.databases).exportAccount(ACCOUNT_ID) as Outcome.Success).value
        val changes = Json.parseToJsonElement(json).jsonObject.getValue("changes").jsonArray.map { it.jsonObject }
        assertEquals(listOf("shopping_list_recipe", "shopping_entry"), changes.map { it.getValue("type").jsonPrimitive.content })
        assertEquals(-6L, changes[1].getValue("localId").jsonPrimitive.long)
        assertEquals(listRecipe, changes[1].getValue("dependsOn").jsonPrimitive.long)
        assertEquals("shopping_list_recipe", changes[1].getValue("refs").jsonObject.getValue("list_recipe").jsonPrimitive.content)
        assertEquals(-4L, changes[1].getValue("payload").jsonObject.getValue("list_recipe").jsonPrimitive.long)
    }

    @Test
    fun errorsAreDescribedByKindWithoutServerText() {
        val badRequest = TandemError.BadRequest(mapOf("recipe" to listOf("Invalid pk \"12\"")))
        assertEquals("BadRequest(fields: recipe)", DefaultDiagnosticsRepository.describe(badRequest))
        assertEquals("ServerError(502)", DefaultDiagnosticsRepository.describe(TandemError.ServerError(502)))
        assertFalse("example.com" in DefaultDiagnosticsRepository.describe(TandemError.Unknown("failed for https://example.com/x")))
    }

    private fun storageRepository() =
        DefaultOfflineStorageRepository(graph.context, graph.sources, graph.databases, graph.imageCache, graph.clock)

    private fun diagnosticsRepository(crashes: CrashLogStore) = DefaultDiagnosticsRepository(
        buildInfo = AppBuildInfo(versionName = "0.2.0-dev", versionCode = 20, distribution = "sideload", isDebug = true, sdkInt = 36),
        store = graph.diagnosticsStore,
        serverStatus = graph.serverStatus,
        syncRepository = graph.sync,
        timers = FakeTimerController(),
        settings = graph.settings,
        databases = graph.databases,
        crashLogs = crashes,
        clock = TestClock(),
        dropped = graph.droppedChanges,
        sources = graph.sources,
        guard = graph.guard,
    )

    private companion object {
        /** Today's carousel queries (FakeTandoorServer answers them with an empty page). */
        val HOME_QUERIES = listOf("timescooked_gte", "new", "viewedon_gte", "cookedon_lte")
    }
}
