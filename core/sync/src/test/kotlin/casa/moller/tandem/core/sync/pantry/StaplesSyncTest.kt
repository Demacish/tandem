package casa.moller.tandem.core.sync.pantry

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.database.entity.FoodEntity
import casa.moller.tandem.core.model.error.Outcome
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
