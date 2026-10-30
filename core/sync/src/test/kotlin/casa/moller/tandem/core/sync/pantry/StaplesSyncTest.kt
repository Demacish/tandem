package casa.moller.tandem.core.sync.pantry

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.database.entity.FoodEntity
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Fas S (hemma-data lane): staples ("Basvaror", plan §8.8) marked "har hemma" in ONE
 * `food/batch_update/` (outbox `BATCH_ON_HAND`, FACTS "Fas S e"), matched to the space's foods by
 * name in Swedish or English, created by name when the space has none, and the first-time list.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class StaplesSyncTest {
    private fun forEachVersion(block: suspend PantrySyncEnv.() -> Unit) = PantrySyncEnv.forEachVersion(block)

    private suspend fun PantrySyncEnv.spaceFoods() {
        graph.real.referenceDao().replaceFoods(
            ACCOUNT_ID,
            listOf(
                FoodEntity(ACCOUNT_ID, 2, "salt"),
                FoodEntity(ACCOUNT_ID, 3, "svartpeppar"),
                FoodEntity(ACCOUNT_ID, 7, "olivolja"),
                FoodEntity(ACCOUNT_ID, 15, "vatten"),
            ),
        )
    }

    /** The food ids and the value of every batch request, in order. */
    private fun PantrySyncEnv.batches(): List<Pair<Set<Long>, Boolean>> =
        tandoor.requests("PUT", "/api/food/batch_update/").map { (_, body) ->
            val json = Json.parseToJsonElement(body).jsonObject
            val foods = json.getValue("foods").jsonArray.map { it.jsonPrimitive.long }.toSet()
            foods to (json.getValue("on_hand").jsonPrimitive.content == "true")
        }

    @Test
    fun staplesFindTheSpacesFoodsAndAreMarkedInOneBatch() = forEachVersion {
        spaceFoods()
        // An English phone on a Swedish space: "Black pepper" is the space's "svartpeppar".
        ok(graph.staples.addStaples(listOf("salt", "Black pepper", "olivolja", "vatten")))

        val staples = graph.staples.observeStaples().first().associateBy { it.name }
        assertEquals(3L, staples.getValue("Black pepper").foodId)
        assertTrue(staples.values.all { it.onHand })
        drainAll()

        assertEquals(listOf(setOf(2L, 3L, 7L, 15L) to true), batches())
        assertEquals(setOf(2L, 3L, 7L, 15L), tandoor.onHand.toSet())
        assertTrue(tandoor.requests("PATCH", "/api/food/").isEmpty())
    }

    @Test
    fun unmarkingIsOneBatchAndOnAndOffAgainSendsNothing() = forEachVersion {
        spaceFoods()
        ok(graph.staples.addStaples(listOf("salt", "vatten")))
        drainAll()

        offline()
        ok(graph.staples.setOnHand(listOf("salt", "vatten"), onHand = false))
        ok(graph.staples.setOnHand(listOf("vatten"), onHand = true))
        assertFalse(graph.staples.observeStaples().first().single { it.name == "salt" }.onHand)
        online()
        drainAll()

        // Only salt changed in the end; vatten went off and on again before anything was sent.
        assertEquals(listOf(setOf(2L, 15L) to true, setOf(2L) to false), batches())
        assertEquals(setOf(15L), tandoor.onHand.toSet())
    }

    @Test
    fun aStapleTheSpaceDoesNotKnowIsCreatedByNameOnlineOrAtTheNextSync() = forEachVersion {
        spaceFoods()
        ok(graph.staples.addStaples(listOf("saffran")))
        drainAll()
        val saffron = tandoor.foodId("saffran")
        assertEquals(1, tandoor.requests("POST", "/api/food/").size)
        assertEquals(listOf(setOf(saffron) to true), batches())

        offline()
        ok(graph.staples.addStaples(listOf("kardemumma")))
        assertEquals(null, graph.staples.observeStaples().first().single { it.name == "kardemumma" }.foodId)
        online()
        ok(graph.staples.resolvePending(graph.remote, background = true))
        drainAll()
        assertEquals(setOf(saffron, tandoor.foodId("kardemumma")), tandoor.onHand.toSet())
    }

    /**
     * Fas S (komplettering-2): a staple added offline without a food and then unmarked stays
     * unmarked when the next sync creates its food; marked again, it is marked as before.
     */
    @Test
    fun aStapleUnmarkedWhileItWaitsForItsFoodIsNotMarkedWhenItIsResolved() = forEachVersion {
        spaceFoods()
        offline()
        ok(graph.staples.addStaples(listOf("kardemumma", "saffran")))
        assertTrue(graph.staples.observeStaples().first().all { it.onHand && it.foodId == null })
        ok(graph.staples.setOnHand(listOf("kardemumma", "saffran"), onHand = false))
        ok(graph.staples.setOnHand(listOf("saffran"), onHand = true))
        val waiting = graph.staples.observeStaples().first().associateBy { it.name }
        assertFalse(waiting.getValue("kardemumma").onHand)
        assertTrue(waiting.getValue("saffran").onHand)
        online()

        ok(graph.staples.resolvePending(graph.remote, background = true))
        drainAll()

        val cardamom = tandoor.foodId("kardemumma")
        val saffron = tandoor.foodId("saffran")
        val staples = graph.staples.observeStaples().first().associateBy { it.name }
        assertEquals(cardamom, staples.getValue("kardemumma").foodId)
        assertFalse("[$version]", staples.getValue("kardemumma").onHand)
        assertTrue("[$version]", staples.getValue("saffran").onHand)
        assertEquals(listOf(setOf(saffron) to true), batches())
        assertEquals(setOf(saffron), tandoor.onHand.toSet())
        // The wish is forgotten once the food is there: from now on the food's own flag counts.
        assertEquals(null, graph.real.syncStateDao().get(ACCOUNT_ID, DefaultStaplesRepository.UNMARKED_KEY))
    }

    /** Fas S review (fix-data): a switch to another account while a staple's food is created writes nothing there. */
    @Test
    fun anAccountSwitchWhileAFoodIsCreatedNeverWritesIntoTheOtherAccount() = forEachVersion {
        spaceFoods()
        offline()
        ok(graph.staples.addStaples(listOf("saffran")))
        online()
        graph.server.respond("POST", "/api/food/") { request ->
            // The user switches (here: to demo mode) while Tandoor is answering.
            graph.sources.source.value = ActiveSource.Demo
            checkNotNull(tandoor.answer(request))
        }

        val result = graph.staples.resolvePending(graph.remote, background = true)

        assertTrue("[$version] $result", result is Outcome.Failure)
        assertTrue(graph.demoDatabase.stapleDao().getStaples(ActiveSource.Demo.accountId).isEmpty())
        assertTrue(graph.demoDatabase.outboxDao().getAllFor(ActiveSource.Demo.accountId, OutboxEntities.FOOD).isEmpty())
        assertTrue(graph.real.outboxDao().getAllFor(ACCOUNT_ID, OutboxEntities.FOOD).isEmpty())
        // The food is stored for its own account: the next run matches it instead of creating it again.
        assertEquals(null, graph.real.stapleDao().getStaples(ACCOUNT_ID).single().foodId)
        assertTrue(graph.real.referenceDao().getFoods(ACCOUNT_ID).any { it.name == "saffran" })
    }

    @Test
    fun theFirstTimeListIsShownOnceAndGuestsCannotMark() = forEachVersion {
        assertFalse(graph.staples.observeSuggestionsShown().first())
        ok(graph.staples.markSuggestionsShown())
        assertTrue(graph.staples.observeSuggestionsShown().first())

        spaceFoods()
        graph.permissions.setGuest(true)
        assertEquals(Outcome.Failure(DataProblem.READ_ONLY.toError()), graph.staples.addStaples(listOf("salt")))
        assertTrue(graph.staples.observeStaples().first().isEmpty())
        assertEquals(0, outboxCount())
    }
}
