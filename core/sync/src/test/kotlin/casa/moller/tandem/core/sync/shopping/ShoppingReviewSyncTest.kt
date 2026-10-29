package casa.moller.tandem.core.sync.shopping

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.database.entity.ShoppingEntryEntity
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.shopping.RecipeShoppingRequest
import casa.moller.tandem.core.model.shopping.ShoppingEntryDraft
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * The 0.3.0 review's fixes in the shopping list's sync (fix-data): lost answers, rows deleted
 * elsewhere, rows that can never be sent, queued aisle changes, "Rensa" per list and guests, against
 * the same stateful fake as [ShoppingSyncTest] for 2.6.13 and 2.7.1.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ShoppingReviewSyncTest {
    private fun forEachVersion(block: suspend ShoppingSyncEnv.() -> Unit) = ShoppingSyncEnv.forEachVersion(block)

    /**
     * 0.3.0 review (fix-data): rows the partner deletes right after the full read this phone checks
     * against cost ONE 404 (it counts in the circuit breaker, plan 3.5/4.1): the 404 makes the next
     * update read the list again, and the rest is dropped without a request. The breaker stays shut.
     */
    @Test
    fun deletionsElsewhereRightAfterTheCheckCostOne404AndNoBreaker() = forEachVersion {
        val ids = (1..8).map { tandoor.seed("vara $it") }
        read()
        // Deleted right after the full read this phone checks against (both press "Rensa").
        tandoor.partnerDelete(ids)
        ids.forEach { ok(repo.update(it, amount = 5.0)) }
        drainAll()
        assertEquals("[$version] one 404, then a fresh read: ${tandoor.notFound}", 1, tandoor.notFound.size)
        assertEquals(1, graph.guard.state.value.clientErrorTimes.size)
        assertNull(graph.guard.pauseAt(graph.guard.state.value, ACCOUNT_ID, graph.clock.millis))
        assertEquals(1, graph.droppedChanges.recent.value.count { it.reason == DroppedChanges.NOT_FOUND })
        assertEquals(7, graph.droppedChanges.recent.value.count { it.reason == DroppedChanges.DELETED_ELSEWHERE })
        assertTrue(entries().isEmpty())
    }

    /**
     * 0.3.0 komplettering (plan 3.5/4.1): five DISTINCT refused rows (three 404s and two 400s on
     * rows the server still lists, so the check before sending cannot drop them) within 100 s open
     * the circuit breaker: the drain stops, the rest waits, and after the pause it is checked
     * against a NEW full read first. A row the partner deleted meanwhile is dropped without a
     * request; the other one is sent; the two 400s stay FAILED (kept for "Väntande ändringar").
     */
    @Test
    fun fiveDistinct404sAnd400sOpenTheBreakerAndTheRestIsCheckedAgainstANewRead() = forEachVersion {
        val ids = (1..7).map { tandoor.seed("vara $it") }
        read()
        val refused = mutableListOf<Long>()
        ids.take(3).forEach { id ->
            graph.server.respond("PATCH", "/api/shopping-list-entry/$id/") {
                refused += id
                jsonAnswer(404, """{"detail":"No ShoppingListEntry matches the given query."}""")
            }
        }
        ids.subList(3, 5).forEach { id ->
            graph.server.respond("PATCH", "/api/shopping-list-entry/$id/") {
                refused += id
                jsonAnswer(400, """{"amount":["A valid number is required."]}""")
            }
        }
        ids.forEach { ok(repo.update(it, amount = 5.0)) }

        assertEquals(DrainResult.STOPPED, graph.outbox.drain(graph.remote))
        assertEquals("[$version] five distinct rows refused, then the breaker", ids.take(5), refused)
        // The fifth trips the breaker, which starts a new count (SyncGuard.withError).
        assertTrue(graph.guard.state.value.breakerUntil > graph.clock.millis)
        assertTrue(graph.guard.state.value.clientErrorTimes.isEmpty())
        assertEquals(SyncPause.CIRCUIT_BREAKER, graph.guard.pauseAt(graph.guard.state.value, ACCOUNT_ID, graph.clock.millis))
        val waiting = ids.drop(5)
        val sentForTheRest = waiting.flatMap { tandoor.requests("PATCH", "/api/shopping-list-entry/$it/") }
        assertTrue("nothing sent for the rest: $sentForTheRest", sentForTheRest.isEmpty())

        // Meanwhile the partner deletes one of the waiting rows; after the pause the rest is checked first.
        tandoor.partnerDelete(listOf(waiting.first()))
        val readsBefore = fullReads()
        graph.clock.advance(6.minutes)
        assertEquals(DrainResult.DONE, graph.outbox.drain(graph.remote))
        assertTrue("a new full read before sending", fullReads() > readsBefore)
        assertTrue("no request for the deleted row", tandoor.requests("PATCH", "/api/shopping-list-entry/${waiting.first()}/").isEmpty())
        assertEquals(5.0, tandoor.entry(waiting.last())?.amount ?: 0.0, 0.0)
        assertEquals(5, refused.size)
        assertNull(graph.guard.pauseAt(graph.guard.state.value, ACCOUNT_ID, graph.clock.millis))
        assertEquals(3, graph.droppedChanges.recent.value.count { it.reason == DroppedChanges.NOT_FOUND })
        assertEquals(1, graph.droppedChanges.recent.value.count { it.reason == DroppedChanges.DELETED_ELSEWHERE })
        val left = graph.real.outboxDao().observeAll(ACCOUNT_ID).first()
        assertEquals("the 400s stay FAILED", listOf(ids[3], ids[4]), left.mapNotNull { it.serverId ?: it.localId }.sorted())
        assertTrue(left.all { it.state == OutboxState.FAILED })
    }

    /** 0.3.0 review (fix-data): the answer of a CREATE is lost after the server saved it: the retry adopts it. */
    @Test
    fun aCreateWhoseAnswerWasLostIsNotPostedASecondTime() = forEachVersion {
        read()
        var lose = true
        graph.server.respond("POST", "/api/shopping-list-entry/") { request ->
            val saved = checkNotNull(tandoor.answer(request))
            if (lose) {
                lose = false
                MockResponse.Builder().code(503).addHeader("Content-Type", "text/html").body("<html>busy</html>").build()
            } else {
                saved
            }
        }
        add("mjölk", 1.0, "l")
        assertEquals(DrainResult.RETRY, graph.outbox.drain(graph.remote))
        // The next poll reads the list (the server's copy is stored) and then the retry goes out.
        read()
        drainAll()
        val posts = tandoor.requests("POST", "/api/shopping-list-entry/").count { "bulk" !in it.first }
        assertEquals("[$version] one POST: ${tandoor.calls}", 1, posts)
        assertEquals(listOf("mjölk"), tandoor.all().map { it.food })
        val local = graph.real.shoppingDao().getEntries(ACCOUNT_ID)
        assertEquals("one row here, keyed to the server's id", listOf(tandoor.all().single().id), local.map { it.localId })
    }

    /** 0.3.0 review (fix-data): the same for a recipe put on the list: one list recipe on the server. */
    @Test
    fun aListRecipeWhoseAnswerWasLostIsNotPostedASecondTime() = forEachVersion {
        ok(graph.details.fetch(graph.remote, RECIPE_ID, isBackground = false))
        val ingredient = graph.real.shoppingDao().getRecipeIngredients(ACCOUNT_ID, RECIPE_ID)
            .first { !it.isHeader && it.foodName != null }
        var lose = true
        graph.server.respond("POST", "/api/shopping-list-recipe/") { request ->
            val saved = checkNotNull(tandoor.answer(request))
            if (lose) {
                lose = false
                MockResponse.Builder().code(503).addHeader("Content-Type", "text/html").body("<html>busy</html>").build()
            } else {
                saved
            }
        }
        ok(repo.addRecipe(RecipeShoppingRequest(RECIPE_ID, 4.0, setOf(ingredient.ingredientId))))
        assertEquals(DrainResult.RETRY, graph.outbox.drain(graph.remote))
        drainAll()
        assertEquals("[$version] one POST", 1, tandoor.requests("POST", "/api/shopping-list-recipe/").size)
        val listRecipe = tandoor.listRecipes().single()
        assertEquals(listOf(listRecipe.id), tandoor.all().map { it.listRecipe })
    }

    /** 0.3.0 review (fix-data): a recipe removed from the list on the web while this phone removed it offline: no 404. */
    @Test
    fun aListRecipeRemovedOnBothSidesDrainsWithoutA404() = forEachVersion {
        ok(graph.details.fetch(graph.remote, RECIPE_ID, isBackground = false))
        val ingredient = graph.real.shoppingDao().getRecipeIngredients(ACCOUNT_ID, RECIPE_ID)
            .first { !it.isHeader && it.foodName != null }
        ok(repo.addRecipe(RecipeShoppingRequest(RECIPE_ID, 4.0, setOf(ingredient.ingredientId))))
        drainAll()
        val listRecipe = tandoor.listRecipes().single().id
        offline()
        ok(repo.removeListRecipe(listRecipe))
        tandoor.partnerDeleteListRecipe(listRecipe)
        graph.network.reconnect()
        drainAll()
        assertTrue("[$version] no 404: ${tandoor.notFound}", tandoor.notFound.isEmpty())
        assertTrue(tandoor.requests("DELETE", "/api/shopping-list-recipe/").isEmpty())
        assertEquals(0, graph.real.outboxDao().observeCount(ACCOUNT_ID).first())
    }

    /** 0.3.0 review (fix-data): a change of a row that can never be sent (its CREATE is gone) never blocks later rows. */
    @Test
    fun aRowWhoseCreateIsGoneNeverHoldsUpTheOutbox() = forEachVersion {
        val milk = tandoor.seed("mjölk")
        read()
        // A row an older version left behind: an entry created here whose CREATE is gone.
        val ghost = ShoppingEntryEntity(ACCOUNT_ID, localId = -40, serverId = null, foodName = "spöke")
        graph.real.shoppingDao().upsertEntries(listOf(ghost))
        graph.real.outboxDao().insert(
            OutboxEntity(
                accountId = ACCOUNT_ID,
                op = OutboxOps.BULK_CHECK,
                entity = OutboxEntities.SHOPPING_ENTRY,
                localId = -40,
                payload = ShoppingPayloads.check(-40, checked = true),
                refs = ShoppingPayloads.checkRefs(-40),
                batchKey = ShoppingPayloads.checkBatchKey(checked = true),
                createdAtEpochMillis = graph.clock.millis,
            ),
        )
        graph.real.outboxDao().insert(
            OutboxEntity(
                accountId = ACCOUNT_ID,
                op = OutboxOps.DELETE,
                entity = OutboxEntities.SHOPPING_ENTRY,
                localId = -41,
                createdAtEpochMillis = graph.clock.millis,
            ),
        )
        // Touching the ghost queues nothing (it can never be sent); a later change of a real row goes out.
        ok(repo.setChecked(listOf(-40), true))
        ok(repo.update(milk, amount = 3.0))
        drainAll()
        assertEquals(3.0, tandoor.entry(milk)?.amount ?: 0.0, 0.0)
        assertTrue(tandoor.requests("POST", "/api/shopping-list-entry/bulk/").isEmpty())
        assertTrue("the ghost is gone", graph.real.shoppingDao().getEntries(ACCOUNT_ID).none { it.localId < 0 })
        assertEquals(2, graph.droppedChanges.recent.value.count { it.reason == DroppedChanges.ORPHAN })
    }

    /** 0.3.0 review (fix-data): an aisle change waiting in the outbox survives a full read with the old aisle, and its send. */
    @Test
    fun aQueuedAisleChangeSurvivesAFullReadAndItsSend() = forEachVersion {
        val milk = tandoor.seed("mjölk")
        read()
        val foodId = checkNotNull(graph.real.shoppingDao().getEntry(ACCOUNT_ID, milk)?.foodId)
        val before = graph.real.shoppingDao().getEntry(ACCOUNT_ID, milk)?.categoryId
        val aisle = 77L
        graph.real.outboxDao().insert(
            OutboxEntity(
                accountId = ACCOUNT_ID,
                op = OutboxOps.PATCH,
                entity = OutboxEntities.FOOD,
                serverId = foodId,
                payload = ShoppingPayloads.foodCategory(aisle, "Mejeri"),
                createdAtEpochMillis = graph.clock.millis,
            ),
        )
        graph.real.shoppingDao().setCategoryOfFood(ACCOUNT_ID, foodId, aisle)
        read()
        val afterRead = graph.real.shoppingDao().getEntry(ACCOUNT_ID, milk)?.categoryId
        assertEquals("[$version] the queued aisle stays after a read", aisle, afterRead)
        // A read that slipped in before the change was queued again (the old aisle), then the send.
        graph.real.shoppingDao().setCategoryOfFood(ACCOUNT_ID, foodId, before)
        drainAll()
        assertEquals("the sent aisle is applied", aisle, graph.real.shoppingDao().getEntry(ACCOUNT_ID, milk)?.categoryId)
    }

    /** 0.3.0 review (fix-data): "Rensa" on a named list deletes that list's checked rows only. */
    @Test
    fun clearingCheckedRowsOfOneListLeavesTheOtherListsRows() = forEachVersion {
        read()
        val drafts = listOf(ShoppingEntryDraft("chips", listIds = setOf(1)), ShoppingEntryDraft("dipp", listIds = setOf(1)))
        val weekend = (repo.addAll(drafts) as Outcome.Success).value
        val week = (repo.add(ShoppingEntryDraft("mjölk", listIds = setOf(2))) as Outcome.Success).value
        ok(repo.setChecked(weekend + week, true))
        drainAll()
        val shown = entries().filter { it.checked && 1L in it.listIds }.map { it.id }
        assertEquals(2, shown.size)
        ok(repo.clearChecked(shown))
        drainAll()
        assertEquals(listOf("mjölk"), tandoor.all().map { it.food })
        assertEquals(listOf("mjölk"), entries().map { it.food.name })
    }

    /**
     * 0.3.0 review (fix-data): guest mode (#42) for rows queued before the role was known: once the
     * stored role is `guest`, nothing is sent and the account is not sent back to the login.
     */
    @Test
    fun aGuestsRowsQueuedBeforeTheRoleWasKnownAreNeverSent() = forEachVersion {
        val milk = tandoor.seed("mjölk")
        read()
        ok(repo.setChecked(listOf(milk), true))
        graph.real.accountDao().setMembership(ACCOUNT_ID, groupLevel = "guest", householdId = null, householdName = null)
        tandoor.calls.clear()
        assertEquals(DrainResult.DONE, graph.outbox.drain(graph.remote))
        assertTrue("[$version] nothing sent: ${tandoor.calls}", tandoor.calls.isEmpty())
        val rows = graph.real.outboxDao().observeAll(ACCOUNT_ID).first()
        assertEquals(listOf(OutboxState.FAILED to "tandem.sync.read-only"), rows.map { it.state to it.lastError })
        assertNull(graph.guard.state.value.unauthorizedAccount)
    }

    /**
     * 0.3.0 review (fix-data): a JSON 403 on a write while the token works is a refused row
     * (FAILED), never "log in again", and it is not sent again.
     */
    @Test
    fun aRefusedWriteIsFailedAndTheAccountStaysLoggedIn() = forEachVersion {
        val milk = tandoor.seed("mjölk")
        read()
        graph.server.respond("POST", "/api/shopping-list-entry/bulk/") {
            MockResponse.Builder()
                .code(403)
                .addHeader("Content-Type", "application/json")
                .body("""{"detail":"You do not have the required permissions to view this page."}""")
                .build()
        }
        ok(repo.setChecked(listOf(milk), true))
        assertEquals(DrainResult.STOPPED, graph.outbox.drain(graph.remote))
        assertNull("[$version] not logged out", graph.guard.state.value.unauthorizedAccount)
        val rows = graph.real.outboxDao().observeAll(ACCOUNT_ID).first()
        assertEquals(listOf(OutboxState.FAILED to "tandem.sync.forbidden"), rows.map { it.state to it.lastError })
        val posts = graph.server.requestsTo("POST", "/api/shopping-list-entry/bulk/").size
        assertEquals(DrainResult.DONE, graph.outbox.drain(graph.remote))
        assertEquals("not sent again", posts, graph.server.requestsTo("POST", "/api/shopping-list-entry/bulk/").size)
        assertTrue("the role was read again", graph.server.requestsTo("GET", "/api/user-space/all_personal/").isNotEmpty())
    }

    /** Full reads of the list (no `updated_after`). */
    private fun ShoppingSyncEnv.fullReads(): Int =
        graph.server.requestsTo("GET", "/api/shopping-list-entry/").count { "updated_after" !in it }

    private fun jsonAnswer(code: Int, body: String): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private companion object {
        const val RECIPE_ID = 3L
    }
}
