package casa.moller.tandem.core.sync.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.common.AppBuildInfo
import casa.moller.tandem.core.common.crash.CrashLogStore
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.sync.SyncPhase
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.sync.SyncSchedule
import casa.moller.tandem.core.sync.data.DefaultDiagnosticsRepository
import casa.moller.tandem.core.sync.data.DefaultOfflineStorageRepository
import casa.moller.tandem.core.sync.testing.FakeTandoorServer
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import casa.moller.tandem.core.sync.work.WorkManagerSyncScheduler
import casa.moller.tandem.core.testing.data.FakeTimerController
import casa.moller.tandem.core.testing.time.TestClock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
    )
}
