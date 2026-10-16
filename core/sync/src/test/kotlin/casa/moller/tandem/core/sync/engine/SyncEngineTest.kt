package casa.moller.tandem.core.sync.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.database.dao.RecipeIndexEntry
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.SyncPhase
import casa.moller.tandem.core.model.sync.SyncProgress
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.sync.SyncSchedule
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.testing.FakeClock
import casa.moller.tandem.core.sync.testing.FakeTandoorServer
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import casa.moller.tandem.core.sync.testing.waitUntil
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/** The sync run against the recorded Tandoor 2.6.13 answers (plan 3.5, 4.1, 6.2 first sync). */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SyncEngineTest {
    private val graph = SyncTestGraph()
    private val server = graph.server

    @After
    fun tearDown() {
        graph.close()
    }

    private suspend fun storedRecipeIds(): Set<Long> = graph.real.recipeDao().getRecipeIds(ACCOUNT_ID).toSet()

    @Test
    fun firstSyncStoresTheIndexOverviewsAndSmallTablesAndReportsProgress() = runBlocking {
        val progress = CopyOnWriteArrayList<SyncProgress>()
        val result = graph.engine.run(SyncReason.FIRST_SYNC) { progress += it }

        assertEquals(SyncPhase.DONE, result.phase)
        assertEquals(SyncPhase.DONE, progress.last().phase)
        val phases = progress.map { it.phase }.distinct()
        val expected = listOf(
            SyncPhase.SERVER_CHECK,
            SyncPhase.REFERENCE_DATA,
            SyncPhase.RECIPE_INDEX,
            SyncPhase.MEAL_PLAN,
            SyncPhase.OUTBOX,
            SyncPhase.DONE,
        )
        assertEquals(expected, phases)
        val index = progress.last { it.phase == SyncPhase.RECIPE_INDEX }
        assertEquals("all 12 recipes of recipe/flat", 12, index.total)
        assertEquals(12, index.done)
        assertEquals((1L..12L).toSet(), storedRecipeIds())
        // Overviews carry the list's columns (keywords come with them, FACTS "Fas 1 c").
        val pasta = graph.recipes.observeRecipes(RecipeQuery(text = "svamp")).first().first { it.id == 2L }
        assertTrue(pasta.keywords.isNotEmpty())
        assertTrue(graph.real.recipeDao().observeKeywords(ACCOUNT_ID).first().size >= 7)
        assertNotNull(graph.real.referenceDao().getUserPreference(ACCOUNT_ID))
        // Times cooked come from the cook log (3 entries for recipe 10).
        assertEquals(3, graph.real.recipeDao().getListItems(ACCOUNT_ID, listOf(10L)).single().overview.timesCooked)
        // The first sync reads every overview in pages of 100, without a date filter.
        val overviews = server.requestsTo("GET", "/api/recipe/").filter { "page_size=100" in it }
        assertTrue(overviews.isNotEmpty())
        assertTrue(overviews.none { "updatedon_gte" in it })
        assertFalse(graph.sync.status.value.running)
    }

    @Test
    fun firstSyncStreamsItsProgressUntilDone() = runBlocking {
        val progress = graph.sync.firstSync().toList()

        assertEquals(SyncPhase.DONE, progress.last().phase)
        assertTrue(progress.dropLast(1).none { it.isFinished })
        assertEquals((1L..12L).toSet(), storedRecipeIds())
    }

    @Test
    fun theIndexRemovesRecipesTheServerNoLongerListsWithTheirQueuedViews() = runBlocking {
        val recipes = graph.real.recipeDao()
        recipes.applyIndex(ACCOUNT_ID, listOf(RecipeIndexEntry(99, "Raderat på servern")))
        graph.real.outboxDao().enqueueViewLog(ACCOUNT_ID, 99, "{}", 1)

        graph.engine.run(SyncReason.USER_REFRESH)

        assertFalse(99L in storedRecipeIds())
        val queued = graph.real.outboxDao().getByState(ACCOUNT_ID, OutboxState.PENDING)
        assertTrue(queued.none { it.entity == OutboxEntities.RECIPE && it.serverId == 99L })
    }

    @Test
    fun aFailedIndexRemovesNothing() = runBlocking {
        graph.real.recipeDao().applyIndex(ACCOUNT_ID, listOf(RecipeIndexEntry(99, "Bara lokalt")))
        graph.server.respond("GET", "/api/recipe/flat/") { FakeTandoorServer.json(500, """{"detail":"Server error"}""") }

        val result = graph.engine.run(SyncReason.USER_REFRESH)

        assertEquals(SyncPhase.FAILED, result.phase)
        assertTrue("a failed index never deletes", 99L in storedRecipeIds())
    }

    @Test
    fun laterSyncsAskOnlyForRecipesChangedSinceTheLastSyncDayMinusOneDay() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        server.requests.clear()

        graph.clock.days.value = LocalDate(2026, 10, 8)
        graph.clock.advance(2.hours)
        graph.engine.run(SyncReason.APP_RESUMED)

        val overviews = server.requestsTo("GET", "/api/recipe/").filter { "page_size=100" in it }
        assertEquals(listOf(true), overviews.map { "updatedon_gte=2026-10-05" in it }.distinct())
    }

    @Test
    fun startAndResumeReadTheIndexAtMostOncePerHour() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        server.requests.clear()

        graph.engine.run(SyncReason.APP_RESUMED)
        assertTrue(server.requestsTo("GET", "/api/recipe/flat/").isEmpty())

        graph.clock.advance(1.hours)
        graph.engine.run(SyncReason.APP_RESUMED)
        assertEquals(1, server.requestsTo("GET", "/api/recipe/flat/").size)
    }

    @Test
    fun prefetchDownloadsPinnedRecipesAtMostTwoAtATime() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        val pinned = listOf(1L, 2L, 3L, 4L, 5L, 6L)
        pinned.forEach { graph.real.recipeDao().setPinned(ACCOUNT_ID, it, true) }
        server.detailDelayMillis = 300

        graph.engine.run(SyncReason.USER_REFRESH)

        assertTrue(graph.real.recipeDao().getStoredDetailIds(ACCOUNT_ID).containsAll(pinned))
        assertTrue("at most 2 parallel detail requests, saw ${server.maxParallelDetails.get()}", server.maxParallelDetails.get() <= 2)
        assertTrue("images of pinned recipes with an image are prefetched", graph.images.urls.any { it.endsWith("_2.png") })

        // Fresh details are not fetched again within 24 hours.
        server.requests.clear()
        graph.engine.run(SyncReason.USER_REFRESH)
        assertTrue(server.requests.none { it.matches(Regex("GET /api/recipe/\\d+/")) })
    }

    @Test
    fun offlineTheRunFailsWithoutSendingAnything() = runBlocking {
        graph.network.online.value = false

        val result = graph.engine.run(SyncReason.USER_REFRESH)

        assertEquals(SyncPhase.FAILED, result.phase)
        assertEquals(TandemError.NetworkUnavailable, result.error)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun aRejectedTokenStopsTheSyncUntilANewLogin() = runBlocking {
        server.respond("GET", "/api/recipe/flat/") { FakeTandoorServer.json(403, """{"detail":"Invalid token."}""") }

        val first = graph.engine.run(SyncReason.USER_REFRESH)
        assertEquals(TandemError.Unauthorized(UnauthorizedSource.TANDOOR), first.error)
        waitUntil { graph.sync.status.value.pause == SyncPause.UNAUTHORIZED }

        server.requests.clear()
        val again = graph.engine.run(SyncReason.PERIODIC)
        assertEquals(SyncPhase.FAILED, again.phase)
        assertTrue("no request with a rejected token: ${server.requests}", server.requests.isEmpty())

        // A new login (another token) lifts the pause.
        graph.sources.token = "tda_new_login"
        server.respond("GET", "/api/recipe/flat/") { FakeTandoorServer.fixture("fas1/recipe-flat") }
        assertEquals(SyncPhase.DONE, graph.engine.run(SyncReason.USER_REFRESH).phase)
    }

    @Test
    fun todaysCarouselsAndMealPlanAreStored() = runBlocking {
        server.respond("GET", "/api/recipe/") { request ->
            if (request.url.queryParameter("viewedon_gte") != null) {
                FakeTandoorServer.fixture("fas1/recipe-list-viewedon-gte")
            } else {
                FakeTandoorServer.fixture("fas1/recipe-list-default")
            }
        }

        graph.engine.run(SyncReason.FIRST_SYNC)

        val sections = graph.home.observeSections().first()
        assertTrue(sections.isNotEmpty())
        val window = graph.real.syncStateDao().get(ACCOUNT_ID, SyncKeys.MEAL_PLAN)
        assertEquals("2026-09-22", window?.windowFrom)
        assertEquals("2026-11-17", window?.windowTo)
        assertTrue(server.requestsTo("GET", "/api/meal-plan/").single().contains("from_date=2026-09-22"))
        assertEquals(FakeClock.TODAY, graph.home.observeUpcomingMeals().first().today)
    }

    @Test
    fun overviewRatingsComeFromTheUsersOwnCookLogEntries() = runBlocking {
        server.respond("GET", "/api/cook-log/") { FakeTandoorServer.json(200, COOK_LOG) }

        graph.engine.run(SyncReason.FIRST_SYNC)

        val recipes = graph.real.recipeDao()
        assertNull("no detail of 3 was fetched", recipes.getDetailRow(ACCOUNT_ID, 3L)?.rawJson)
        val pancakes = recipes.getListItems(ACCOUNT_ID, listOf(3L)).single().overview
        assertEquals("own 5 and 4 (and one unrated) round to 5", 5.0, checkNotNull(pancakes.rating), 0.0)
        assertEquals(3, pancakes.timesCooked)
        val buns = recipes.getListItems(ACCOUNT_ID, listOf(4L)).single().overview
        assertNull("only another member rated it", buns.rating)
        assertEquals(2, buns.timesCooked)
        // The offline "Rating 4+" filter finds it without a stored detail.
        assertEquals(listOf(3L), graph.recipes.observeRecipes(RecipeQuery(minRating = 4.0)).first().map { it.id })

        // A later index pass (name-sorted lists carry no rating) keeps it.
        graph.clock.advance(5.minutes)
        graph.engine.run(SyncReason.USER_REFRESH)
        assertEquals(5.0, checkNotNull(recipes.getListItems(ACCOUNT_ID, listOf(3L)).single().overview.rating), 0.0)
    }

    @Test
    fun imagesOfRecentlyViewedRecipesArePrefetched() = runBlocking {
        server.respond("GET", "/api/recipe/") { request ->
            if (request.url.queryParameter("viewedon_gte") != null) {
                FakeTandoorServer.fixture("fas1/recipe-list-viewedon-gte")
            } else {
                FakeTandoorServer.fixture("fas1/recipe-list-default")
            }
        }

        graph.engine.run(SyncReason.FIRST_SYNC)

        // Recipe 2 is neither pinned nor planned: only the server's "Recently viewed" lists it.
        assertTrue("${graph.images.urls}", graph.images.urls.any { it.endsWith("_2.png") })
    }

    @Test
    fun aTemporarilyFailedImageIsTriedAgainInTheNextRunAndALoadedOneIsNot() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        listOf(2L, 10L).forEach { graph.real.recipeDao().setPinned(ACCOUNT_ID, it, true) }
        graph.images.failing += "_2.png"
        graph.images.urls.clear()

        graph.engine.run(SyncReason.USER_REFRESH)
        assertEquals(2, graph.images.urls.size)

        graph.images.failing.clear()
        graph.images.urls.clear()
        graph.engine.run(SyncReason.USER_REFRESH)
        assertEquals(1, graph.images.urls.size)
        assertTrue(graph.images.urls.single().endsWith("_2.png"))
    }

    @Test
    fun todaysRefreshAndASyncRunFetchTheMealPlanOnlyOnce() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        graph.clock.advance(SyncSchedule.HOME_INTERVAL)
        server.requests.clear()

        coroutineScope {
            launch { graph.engine.refreshHome(graph.remote) }
            launch { graph.engine.run(SyncReason.USER_REFRESH) }
        }

        assertEquals(1, server.requestsTo("GET", "/api/meal-plan/").size)
        assertEquals(1, server.requests.count { "viewedon_gte" in it })
    }

    @Test
    fun timesStoredWhileTheClockWasAheadDoNotStopTheSync() = runBlocking {
        val today = graph.clock.days.value
        graph.clock.days.value = today.plus(2, DateTimeUnit.DAY)
        graph.clock.advance(48.hours)
        graph.engine.run(SyncReason.FIRST_SYNC)

        // The clock is corrected two days back.
        graph.clock.days.value = today
        graph.clock.millis -= 48.hours.inWholeMilliseconds
        server.requests.clear()
        graph.engine.run(SyncReason.APP_RESUMED)

        assertEquals("the index is due again", 1, server.requestsTo("GET", "/api/recipe/flat/").size)
        assertEquals("the small tables too", 1, server.requestsTo("GET", "/api/keyword/").size)
        val overviews = server.requestsTo("GET", "/api/recipe/").filter { "page_size=100" in it }
        assertTrue(overviews.isNotEmpty())
        assertTrue("since is never after today - 1: $overviews", overviews.all { "updatedon_gte=2026-10-05" in it })
    }

    @Test
    fun a429StopsTheRunAndNothingMoreIsSent() = runBlocking {
        server.respond("GET", "/api/keyword/") { throttled() }

        val result = graph.engine.run(SyncReason.USER_REFRESH)

        assertEquals(SyncPhase.FAILED, result.phase)
        assertTrue("${result.error}", result.error is TandemError.Throttled)
        assertTrue("nothing after the 429: ${server.requests}", server.requests.last().startsWith("GET /api/keyword/"))
        server.requests.clear()
        graph.engine.run(SyncReason.PERIODIC)
        assertTrue("no request before Retry-After: ${server.requests}", server.requests.isEmpty())
    }

    @Test
    fun aViewLogAnswered429StaysQueuedWithoutAnAutomaticRetry() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        graph.recipes.markViewed(2)
        server.respond("POST", "/api/view-log/") { throttled() }

        assertEquals(DrainResult.STOPPED, graph.outbox.drain(graph.remote))
        assertEquals(1, graph.real.outboxDao().observeCount(ACCOUNT_ID).first())
        assertTrue(graph.guard.check(ACCOUNT_ID, background = true) is TandemError.Throttled)
    }

    @Test
    fun aLogoutDuringTheOverviewPagesLeavesNoRowsOfTheAccount() = runBlocking {
        server.respond("GET", "/api/recipe/") { request ->
            if (request.url.queryParameter("page_size") == "100") {
                // The user logs out while this page is on its way.
                runBlocking {
                    graph.accountStore.remove(ACCOUNT_ID)
                    graph.sources.source.value = ActiveSource.None
                    graph.mirror.onSession(Session.NoAccount)
                }
            }
            FakeTandoorServer.fixture("fas1/recipe-list-default")
        }

        val result = graph.engine.run(SyncReason.FIRST_SYNC)
        graph.mirror.onSession(Session.NoAccount)

        assertEquals(SyncPhase.FAILED, result.phase)
        assertTrue(storedRecipeIds().isEmpty())
        assertTrue(graph.real.syncStateDao().observe(ACCOUNT_ID).first().isEmpty())
        assertTrue(graph.real.accountDao().getStoredAccountIds().isEmpty())
    }

    private fun throttled(): MockResponse = MockResponse.Builder()
        .code(429)
        .addHeader("Content-Type", "application/json")
        .addHeader("Retry-After", "120")
        .body("""{"detail":"Request was throttled."}""")
        .build()

    private companion object {
        /** Recipe 3: own 5, 4 and one unrated entry; recipe 4: only another member rated it. */
        val COOK_LOG = """
            {"count":5,"next":null,"previous":null,"results":[
            {"id":1,"recipe":3,"rating":5,"created_by":{"id":1},"created_at":"2026-10-01T18:00:00.000000+02:00"},
            {"id":2,"recipe":3,"rating":4,"created_by":{"id":1},"created_at":"2026-10-02T18:00:00.000000+02:00"},
            {"id":3,"recipe":3,"rating":null,"created_by":{"id":1},"created_at":"2026-10-03T18:00:00.000000+02:00"},
            {"id":4,"recipe":4,"rating":5,"created_by":{"id":2},"created_at":"2026-10-01T18:00:00.000000+02:00"},
            {"id":5,"recipe":4,"rating":null,"created_by":{"id":1},"created_at":"2026-10-02T18:00:00.000000+02:00"}
            ]}
        """.trimIndent()
    }
}
