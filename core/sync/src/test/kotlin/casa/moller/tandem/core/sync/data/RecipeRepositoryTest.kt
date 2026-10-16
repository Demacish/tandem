package casa.moller.tandem.core.sync.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.dao.RecipeIndexEntry
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.recipe.RecipeDetailState
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.testing.FakeTandoorServer
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import casa.moller.tandem.core.sync.testing.waitUntil
import casa.moller.tandem.core.testing.fixtures.TandoorFixtures
import java.text.Collator
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/** Stale-while-revalidate, view log outbox and its protections (plan 3.3, 3.5, 3.6, 4.1). */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RecipeRepositoryTest {
    private val graph = SyncTestGraph()
    private val server = graph.server
    private val repository = graph.recipes

    @After
    fun tearDown() {
        graph.close()
    }

    private fun renamedRecipe(name: String): String {
        val recorded = Json.parseToJsonElement(TandoorFixtures.exchange("fas1/recipe-svamppasta").body!!).jsonObject
        return JsonObject(recorded + ("name" to JsonPrimitive(name))).toString()
    }

    @Test
    fun aRecipeIsShownFromStorageFirstThenRefreshedInTheBackground() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        graph.details.fetch(graph.remote, 2, isBackground = false)
        val original = graph.real.recipeDao().getListItems(ACCOUNT_ID, listOf(2L)).single().overview.name
        server.respond("GET", "/api/recipe/2/") { FakeTandoorServer.json(200, renamedRecipe("Ny svamppasta")) }
        graph.clock.advance(10.minutes)
        server.requests.clear()

        val states = CopyOnWriteArrayList<RecipeDetailState>()
        val collector = launch { repository.observeRecipe(2).collect { states += it } }
        waitUntil { states.any { it.recipe?.name == "Ny svamppasta" && !it.refreshing } }
        collector.cancel()

        val first = states.first()
        assertEquals("the stored copy comes first", original, first.recipe?.name)
        assertFalse(first.refreshing)
        val refreshing = states.indexOfFirst { it.refreshing }
        assertTrue("a refreshing state follows: $states", refreshing > 0)
        assertEquals(original, states[refreshing].recipe?.name)
        assertNull(states.last().error)
        assertEquals(1, server.requestsTo("GET", "/api/recipe/2/").size)
    }

    @Test
    fun aFreshStoredRecipeIsNotFetchedAgain() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        graph.details.fetch(graph.remote, 2, isBackground = false)
        server.requests.clear()

        val state = repository.observeRecipe(2).first { it.recipe != null }
        assertFalse(state.refreshing)
        delay(300)
        assertTrue(server.requestsTo("GET", "/api/recipe/2/").isEmpty())
    }

    @Test
    fun offlineTheStoredCopyComesWithTheNetworkError() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        graph.details.fetch(graph.remote, 2, isBackground = false)
        graph.clock.advance(10.minutes)
        graph.network.online.value = false
        server.requests.clear()

        val state = repository.observeRecipe(2).first { it.error != null }

        assertNotNull(state.recipe)
        assertEquals(TandemError.NetworkUnavailable, state.error)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun aRecipeDeletedOnTheServerIsRemovedAndReportedAsDeleted() = runBlocking {
        graph.real.recipeDao().applyIndex(ACCOUNT_ID, listOf(RecipeIndexEntry(12, "Tillfälligt recept")))

        val state = repository.observeRecipe(12).first { it.isDeleted }

        assertNull(state.overview)
        assertTrue(graph.real.recipeDao().getRecipeIds(ACCOUNT_ID).isEmpty())
    }

    @Test
    fun viewsAreQueuedCoalescedAndSentAsOneViewLog() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        repeat(3) { repository.markViewed(2) }

        val queued = graph.real.outboxDao().getByState(ACCOUNT_ID, OutboxState.PENDING)
        assertEquals(1, queued.size)
        assertTrue("the outbox worker is scheduled", "outbox" in graph.scheduler.calls)
        assertNotNull(graph.real.recipeDao().getDetailRow(ACCOUNT_ID, 2)?.lastViewedLocalEpochMillis)

        assertEquals(DrainResult.DONE, graph.outbox.drain(graph.remote))
        assertEquals(1, server.requestsTo("POST", "/api/view-log/").size)
        assertEquals(0, graph.real.outboxDao().observeCount(ACCOUNT_ID).first())
    }

    @Test
    fun fiveNotFoundAnswersDropTheirRowsAndPauseTheOutbox() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        server.respond("POST", "/api/view-log/") { FakeTandoorServer.json(404, """{"detail":"Not found."}""") }
        (1L..6L).forEach { repository.markViewed(it) }

        val result = graph.outbox.drain(graph.remote)

        assertEquals(DrainResult.STOPPED, result)
        assertEquals("the breaker stops after 5 answers 404 (plan 4.1)", 5, server.requestsTo("POST", "/api/view-log/").size)
        val left = graph.real.outboxDao().observeAll(ACCOUNT_ID).first()
        assertEquals("404 = deleted elsewhere: dropped; the sixth waits", 1, left.size)
        assertEquals(OutboxState.PENDING, left.single().state)
    }

    @Test
    fun viewsOfRecipesNoLongerStoredAreDroppedWithoutSending() = runBlocking {
        graph.real.outboxDao().enqueueViewLog(ACCOUNT_ID, 77, "{}", 1)

        assertEquals(DrainResult.DONE, graph.outbox.drain(graph.remote))

        assertTrue(server.requestsTo("POST", "/api/view-log/").isEmpty())
        assertEquals(0, graph.real.outboxDao().observeCount(ACCOUNT_ID).first())
    }

    @Test
    fun safeModeHoldsTheOutboxUntilTheVersionIsTestedAgain() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        val untested = graph.remote.copy(account = graph.account.copy(serverVersion = "2.7.1"))
        graph.sources.source.value = untested
        repository.markViewed(2)

        assertEquals(DrainResult.PAUSED, graph.outbox.drain(untested))
        assertTrue(server.requestsTo("POST", "/api/view-log/").isEmpty())
        assertEquals(OutboxState.PAUSED, graph.real.outboxDao().observeAll(ACCOUNT_ID).first().single().state)

        graph.sources.source.value = graph.remote
        assertEquals(DrainResult.DONE, graph.outbox.drain(graph.remote))
        assertEquals(1, server.requestsTo("POST", "/api/view-log/").size)
    }

    @Test
    fun aRejectedTokenKeepsTheQueuedViews() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        server.respond("POST", "/api/view-log/") { FakeTandoorServer.json(403, """{"detail":"Invalid token."}""") }
        repository.markViewed(2)
        repository.markViewed(3)

        assertEquals(DrainResult.STOPPED, graph.outbox.drain(graph.remote))
        assertEquals(1, server.requestsTo("POST", "/api/view-log/").size)
        assertEquals(2, graph.real.outboxDao().observeCount(ACCOUNT_ID).first())
    }

    @Test
    fun serverSearchStoresWhatItFinds() = runBlocking {
        val found = repository.searchServer(RecipeQuery(text = "svamp"))

        val recipes = (found as Outcome.Success).value
        assertTrue(recipes.any { it.id == 2L })
        assertTrue(server.requestsTo("GET", "/api/recipe/").single().contains("query=svamp"))
        assertTrue(graph.real.recipeDao().getRecipeIds(ACCOUNT_ID).contains(2L))
    }

    /** Plan 6.2 "livsmedel": the food filter is `foods_and` on the server and the stored ingredients offline. */
    @Test
    fun theFoodFilterSearchesTheServerWithFoodsAndAndTheStoredIngredientsOffline() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        val foods = repository.observeFoods().first()
        // The food list from the sync (38 foods in the recording), by name.
        assertEquals(38, foods.size)
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }
        assertEquals(foods.map { it.name }.sortedWith(collator), foods.map { it.name })
        val onion = foods.single { it.name == "gul lök" }.id
        val potato = foods.single { it.name == "potatis" }.id

        server.requests.clear()
        repository.searchServer(RecipeQuery(foodIds = setOf(potato, onion)))
        val search = server.requestsTo("GET", "/api/recipe/").single()
        assertTrue(search, search.contains("foods_and=$onion") && search.contains("foods_and=$potato"))

        // Locally only stored details have ingredients (2 = svamppasta, 5 = köttbullar; both with onion).
        graph.details.fetch(graph.remote, 2, isBackground = false)
        graph.details.fetch(graph.remote, 5, isBackground = false)
        val withOnion = repository.observeRecipes(RecipeQuery(foodIds = setOf(onion))).first().map { it.id }
        assertTrue("$withOnion", withOnion.containsAll(listOf(2L, 5L)))
        assertEquals(listOf(5L), repository.observeRecipes(RecipeQuery(foodIds = setOf(onion, potato))).first().map { it.id })
    }

    @Test
    fun servingsChoicesAndPinsAreLocal() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        server.requests.clear()
        graph.network.online.value = false

        repository.setServingsChoice(2, 6.0)
        repository.setAvailableOffline(2, true)

        assertEquals(6.0, repository.observeServingsChoice(2).first()?.servings)
        assertTrue(repository.observeRecipes(RecipeQuery.All).first().single { it.id == 2L }.pinned)
        assertTrue(server.requests.isEmpty())
        assertTrue(repository.setServingsChoice(2, -1.0) is Outcome.Failure)
    }

    @Test
    fun anotherAccountSeesNoneOfTheRecipes() = runBlocking {
        graph.engine.run(SyncReason.FIRST_SYNC)
        assertEquals(12, repository.observeRecipes(RecipeQuery.All).first().size)

        val other = graph.remote.copy(account = graph.account.copy(id = "account-b"))
        graph.sources.source.value = other
        assertTrue(repository.observeRecipes(RecipeQuery.All).first().isEmpty())

        graph.sources.source.value = ActiveSource.None
        assertTrue(repository.observeRecipes(RecipeQuery.All).first().isEmpty())

        graph.sources.source.value = graph.remote
        assertEquals(12, repository.observeRecipes(RecipeQuery.All).first().size)
    }
}
