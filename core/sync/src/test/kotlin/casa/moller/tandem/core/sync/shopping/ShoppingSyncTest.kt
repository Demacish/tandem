package casa.moller.tandem.core.sync.shopping

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.database.DatabaseKind
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.database.entity.UserPreferenceEntity
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.shopping.RecipeShoppingRequest
import casa.moller.tandem.core.model.shopping.ShoppingChange
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingEntryDraft
import casa.moller.tandem.core.model.sync.ForegroundTarget
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import casa.moller.tandem.core.testing.data.FakeMealPlanRepository
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * The shopping list's data and sync (feature #9, plan 3.5, 5.7; Phase 2 "Klart när") against a
 * stateful Tandoor answering in the recorded shapes of BOTH 2.6.13 and 2.7.1 ([FakeShoppingTandoor]):
 * offline changes and their drain (temporary ids, merges, order), entries deleted elsewhere drained
 * without a single 404, two phones with `updated_after` and the window rule, bulk checks, "köp
 * senare", guests, 404s counted by the circuit breaker, 429, safe mode, accounts and demo mode.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ShoppingSyncTest {
    private class Env(val graph: SyncTestGraph, val tandoor: FakeShoppingTandoor, val version: String) {
        val repo = graph.shopping

        suspend fun read(full: Boolean = true) {
            assertTrue(graph.shoppingReader.sync(graph.remote, background = false, forceFull = full) is Outcome.Success)
        }

        /** Sends the whole outbox (several drains of at most 50 rows). */
        suspend fun drainAll() {
            repeat(MAX_DRAINS) {
                val result = graph.outbox.drain(graph.remote)
                if (result == DrainResult.DONE && graph.real.outboxDao().observeCount(ACCOUNT_ID).first() == 0) return
            }
            throw AssertionError("[$version] the outbox did not drain: ${graph.real.outboxDao().observeAll(ACCOUNT_ID).first()}")
        }

        suspend fun entries(): List<ShoppingEntry> = repo.observeEntries().first()

        suspend fun named(name: String): ShoppingEntry? = entries().firstOrNull { it.food.name == name }

        suspend fun add(name: String, amount: Double = 0.0, unit: String? = null): Long =
            (repo.add(ShoppingEntryDraft(foodName = name, amount = amount, unitName = unit)) as Outcome.Success).value

        fun offline() {
            graph.network.online.value = false
        }

        fun online() {
            graph.network.online.value = true
        }
    }

    private fun forEachVersion(block: suspend Env.() -> Unit) {
        for (version in VERSIONS) {
            val graph = SyncTestGraph(serverVersion = version)
            val tandoor = FakeShoppingTandoor(version, graph.clock).also { it.install(graph.server) }
            try {
                runBlocking {
                    mirror(graph)
                    Env(graph, tandoor, version).block()
                }
            } finally {
                graph.close()
            }
        }
    }

    private fun ok(outcome: Outcome<*>) = assertTrue("$outcome", outcome is Outcome.Success)

    /** The account's `server_account` row, as `AccountMirror` writes it after a login. */
    private suspend fun mirror(graph: SyncTestGraph) {
        val account = graph.account
        val row = ServerAccountEntity(id = account.id, baseUrl = account.baseUrl, userId = account.userId, spaceId = account.spaceId)
        graph.real.accountDao().upsert(row)
    }

    // ------------------------------------------------------------------ Phase 2 "Klart när"

    /** 20 changes offline (create + check of the same item among them), then one drain online. */
    @Test
    fun twentyOfflineChangesIncludingCreateAndCheckOfTheSameItemDrainCorrectly() = forEachVersion {
        val milk = tandoor.seed("mjölk", 2.0, "l")
        val bread = tandoor.seed("bröd")
        val eggs = tandoor.seed("ägg", 6.0)
        val cheese = tandoor.seed("ost")
        val coffee = tandoor.seed("kaffe")
        read()
        tandoor.calls.clear()
        offline()
        val until = Instant.fromEpochMilliseconds(graph.clock.millis) + 4.hours

        val cream = add("grädde", 3.0, "dl") // 1
        ok(repo.setChecked(listOf(cream), true)) // 2: create + check of the same item
        val butter = add("smör") // 3
        ok(repo.update(butter, amount = 2.0)) // 4: merged into its CREATE
        val chips = add("chips") // 5
        ok(repo.remove(listOf(chips))) // 6: cancels the CREATE, nothing is sent
        ok(repo.setChecked(listOf(milk), true)) // 7
        ok(repo.setChecked(listOf(bread), true)) // 8
        ok(repo.setChecked(listOf(bread), false)) // 9: cancels 8
        ok(repo.setDelayed(listOf(eggs), until)) // 10
        ok(repo.update(cheese, amount = 3.0)) // 11
        ok(repo.update(cheese, unitName = "kg")) // 12: merged with 11
        ok(repo.setLists(listOf(coffee), setOf(1))) // 13
        ok(repo.remove(listOf(coffee))) // 14: replaces 13
        val tomato = add("tomat", 4.0) // 15
        val cucumber = add("gurka") // 16
        ok(repo.setChecked(listOf(tomato, cucumber), true)) // 17
        ok(repo.setChecked(listOf(cucumber), false)) // 18: back to unchecked before it was sent
        ok(repo.update(tomato, foodName = "körsbärstomat")) // 19
        ok(repo.setChecked(listOf(eggs), true)) // 20

        // Everything is on the device at once; nothing went out.
        assertEquals(true, named("grädde")?.checked)
        assertEquals(true, named("grädde")?.pending)
        assertNull(named("chips"))
        assertNull(named("kaffe"))
        assertTrue(tandoor.calls.isEmpty())
        assertTrue(repo.observePendingCount().first() > 0)

        online()
        drainAll()

        val server = tandoor.all().associateBy { it.food }
        assertEquals(setOf("mjölk", "bröd", "ägg", "ost", "grädde", "smör", "körsbärstomat", "gurka"), server.keys)
        assertTrue(server.getValue("mjölk").checked)
        assertFalse(server.getValue("bröd").checked)
        assertTrue(server.getValue("ägg").checked)
        assertEquals(until.toEpochMilliseconds() / 1000, server.getValue("ägg").delayUntil!! / 1000)
        assertEquals(3.0, server.getValue("ost").amount, 0.0)
        assertEquals("kg", server.getValue("ost").unit)
        assertTrue("checked after its create, with completed_at", server.getValue("grädde").checked)
        assertNotNull(server.getValue("grädde").completedAt)
        assertEquals(2.0, server.getValue("smör").amount, 0.0)
        assertEquals(4.0, server.getValue("körsbärstomat").amount, 0.0)
        assertTrue(server.getValue("körsbärstomat").checked)
        assertFalse(server.getValue("gurka").checked)
        assertTrue("no 404: ${tandoor.notFound}", tandoor.notFound.isEmpty())
        assertTrue("chips never sent", tandoor.calls.none { (_, body) -> "chips" in body })
        assertEquals("one DELETE (kaffe)", 1, tandoor.requests("DELETE", "/api/shopping-list-entry/").size)
        val creates = tandoor.requests("POST", "/api/shopping-list-entry/").count { (call, _) -> !call.contains("bulk") }
        assertEquals("4 creates (not the chips)", 4, creates)

        // The temporary ids were rewritten: the device holds the server's ids and nothing waits.
        val local = graph.real.shoppingDao().getEntries(ACCOUNT_ID)
        assertTrue(local.all { it.localId > 0 && it.serverId == it.localId })
        assertEquals(server.values.map { it.id }.toSet(), local.mapNotNull { it.serverId }.toSet())
        assertEquals(0, repo.observePendingCount().first())
        assertEquals(server.values.map { it.food to it.checked }.toSet(), entries().map { it.food.name to it.checked }.toSet())
        // An old temporary id still works.
        assertEquals(true, graph.real.idRewriteDao().current(ACCOUNT_ID, "shopping_entry", cream) > 0)
        ok(repo.setChecked(listOf(cream), false))
        assertEquals(false, named("grädde")?.checked)
    }

    /** The partner deletes 15 rows while this phone, offline, changed them: the drain sends no request that ends in a 404. */
    @Test
    fun partnerDeletes15RowsWhileOfflineEditsExistAndTheDrainHasNo404() = forEachVersion {
        val ids = (1..20).map { tandoor.seed("vara $it", amount = it.toDouble()) }
        read()
        offline()
        val deleted = ids.take(15)
        deleted.forEachIndexed { index, id ->
            when (index % 5) {
                0 -> ok(repo.update(id, amount = 10.0 + index))
                1 -> ok(repo.setDelayed(listOf(id), Instant.fromEpochMilliseconds(graph.clock.millis) + 2.hours))
                2 -> ok(repo.remove(listOf(id)))
                3 -> ok(repo.setLists(listOf(id), setOf(1)))
                else -> ok(repo.setChecked(listOf(id), true))
            }
        }
        ok(repo.update(ids[15], amount = 99.0))
        ok(repo.setChecked(listOf(ids[16]), true))
        tandoor.partnerDelete(deleted)
        graph.clock.advance(5.minutes)
        tandoor.calls.clear()

        online()
        drainAll()

        assertTrue("not a single 404: ${tandoor.notFound}", tandoor.notFound.isEmpty())
        val writes = tandoor.calls.filter { (call, _) -> call.startsWith("PATCH") || call.startsWith("DELETE") }
        assertTrue("nothing sent for a deleted row: $writes", writes.none { (call, _) -> deleted.any { "/$it/" in call } })
        assertEquals(listOf("PATCH /api/shopping-list-entry/${ids[15]}/"), writes.map { it.first })
        assertEquals(99.0, tandoor.entry(ids[15])?.amount ?: 0.0, 0.0)
        assertTrue(tandoor.entry(ids[16])?.checked == true)
        assertEquals("one full read checked the list before sending", 1, tandoor.requests("GET", "/api/shopping-list-entry/").size)
        val local = graph.real.shoppingDao().getEntries(ACCOUNT_ID).mapNotNull { it.serverId }.toSet()
        assertEquals(ids.drop(15).toSet(), local)
        assertEquals(0, graph.real.outboxDao().observeCount(ACCOUNT_ID).first())
        assertTrue(graph.droppedChanges.recent.value.count { it.reason == DroppedChanges.DELETED_ELSEWHERE } >= 12)
        assertNull("the circuit breaker is not open", graph.guard.pauseAt(graph.guard.state.value, ACCOUNT_ID, graph.clock.millis))
    }

    /**
     * Phase 2 (integration lane): the same within a minute of the last full read. The reconnect
     * makes the stored read stale, so the drain reads the list again first: still no 404, also when
     * the device was offline for only a few seconds (the release smoke test's case).
     */
    @Test
    fun rowsDeletedElsewhereWhileBrieflyOfflineDrainWithoutA404() = forEachVersion {
        val ids = (1..6).map { tandoor.seed("vara $it") }
        read()
        offline()
        ok(repo.update(ids[0], amount = 5.0))
        ok(repo.remove(listOf(ids[1])))
        ok(repo.setDelayed(listOf(ids[2]), Instant.fromEpochMilliseconds(graph.clock.millis) + 2.hours))
        ok(repo.update(ids[3], amount = 7.0))
        tandoor.partnerDelete(ids.take(3))
        graph.clock.advance(10.seconds)
        tandoor.calls.clear()

        graph.network.reconnect()
        drainAll()

        assertTrue("not a single 404: ${tandoor.notFound}", tandoor.notFound.isEmpty())
        val writes = tandoor.calls.filter { (call, _) -> call.startsWith("PATCH") || call.startsWith("DELETE") }.map { it.first }
        assertEquals(listOf("PATCH /api/shopping-list-entry/${ids[3]}/"), writes)
        assertEquals("one fresh full read after the reconnect", 1, tandoor.requests("GET", "/api/shopping-list-entry/").size)
        assertEquals(7.0, tandoor.entry(ids[3])?.amount ?: 0.0, 0.0)
    }

    /** Two phones in one household: checks through `updated_after`, deletions through the window rule. */
    @Test
    fun twoPhonesSeeEachOthersChangesWithUpdatedAfterAndTheWindowRule() {
        for (version in VERSIONS) {
            val phoneA = SyncTestGraph(serverVersion = version)
            val phoneB = SyncTestGraph(
                server = phoneA.server,
                databasePrefix = "b-",
                accountId = "account-b",
                serverVersion = version,
                userId = 2,
            )
            val tandoor = FakeShoppingTandoor(version, phoneA.clock).also { it.install(phoneA.server) }
            try {
                runBlocking { twoPhones(phoneA, phoneB, tandoor) }
            } finally {
                phoneB.close()
                phoneA.close()
            }
        }
    }

    private suspend fun twoPhones(phoneA: SyncTestGraph, phoneB: SyncTestGraph, tandoor: FakeShoppingTandoor) {
        val milk = tandoor.seed("mjölk")
        val bread = tandoor.seed("bröd")
        tandoor.seed("gammalt", checkedDaysAgo = 20)
        ok(phoneA.shoppingSync.refresh(phoneA.remote, background = false, full = true))
        ok(phoneB.shoppingSync.refresh(phoneB.remote, background = false, full = true))
        assertEquals(setOf("mjölk", "bröd"), phoneB.shopping.observeEntries().first().map { it.food.name }.toSet())
        val changes = coroutineScope {
            val collected = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(TIMEOUT_MILLIS) { phoneB.shopping.observeChanges().take(3).toList() }
            }
            delay(SETTLE_MILLIS)

            // A checks the milk and adds cheese; B polls (a delta with the URL-encoded server timestamp).
            ok(phoneA.shopping.setChecked(listOf(milk), true))
            phoneA.shopping.add(ShoppingEntryDraft("ost"))
            ok(phoneA.shoppingSync.refresh(phoneA.remote, background = true))
            phoneA.clock.advance(20.seconds)
            phoneB.clock.advance(20.seconds)
            ok(phoneB.shoppingSync.refresh(phoneB.remote, background = true))
            val delta = tandoor.calls.last { (call, _) -> call.startsWith("GET /api/shopping-list-entry/") }.first
            assertTrue("a delta: $delta", "updated_after=" in delta)
            assertFalse("the + of the offset is encoded: $delta", delta.substringAfter("updated_after=").contains('+'))
            val seenByB = phoneB.shopping.observeEntries().first().associateBy { it.food.name }
            assertEquals(true, seenByB["mjölk"]?.checked)
            assertNotNull(seenByB["ost"])

            // The partner deletes the bread: a delta cannot see it, the next full read does.
            tandoor.partnerDelete(listOf(bread))
            ok(phoneB.shoppingSync.refresh(phoneB.remote, background = true))
            assertNotNull(phoneB.shopping.observeEntries().first().firstOrNull { it.food.name == "bröd" })
            ok(phoneB.shoppingSync.refresh(phoneB.remote, background = true, full = true))
            assertNull(phoneB.shopping.observeEntries().first().firstOrNull { it.food.name == "bröd" })
            collected.await()
        }
        assertEquals(
            setOf(ShoppingChange.Checked("mjölk"), ShoppingChange.Added("ost", "contract-admin"), ShoppingChange.Removed("bröd")),
            changes.toSet(),
        )

        // Ten days later the checked milk has aged out of the window: archived on B, not deleted.
        phoneA.clock.advance(10.days)
        phoneB.clock.advance(10.days)
        ok(phoneB.shoppingSync.refresh(phoneB.remote, background = true, full = true))
        assertNull(phoneB.shopping.observeEntries().first().firstOrNull { it.food.name == "mjölk" })
        assertEquals(listOf("mjölk"), phoneB.shopping.observeHistory().first().map { it.foodName })
        assertNotNull("still on the server", tandoor.entry(milk))
        // Polling while the list is open is fast enough for "within 30 s".
        assertTrue(foregroundOf(phoneB).intervalFor(ForegroundTarget.ShoppingList, phoneB.remote) <= 30.seconds)
    }

    // ------------------------------------------------------------------ outbox details

    @Test
    fun checksAreSentAsOneBulkCallPerValue() = forEachVersion {
        val open = (1..5).map { tandoor.seed("öppen $it") }
        val done = (1..2).map { tandoor.seed("klar $it", checkedDaysAgo = 0) }
        read()
        offline()
        ok(repo.setChecked(open, true))
        ok(repo.setChecked(done, false))
        online()
        drainAll()
        val bulks = tandoor.requests("POST", "/api/shopping-list-entry/bulk/").map { (_, body) -> Json.parseToJsonElement(body).jsonObject }
        assertEquals(2, bulks.size)
        val byValue = bulks.associate { bulk ->
            bulk.getValue("checked").jsonPrimitive.content to bulk.getValue("ids").jsonArray.map { it.jsonPrimitive.long }.toSet()
        }
        assertEquals(open.toSet(), byValue["true"])
        assertEquals(done.toSet(), byValue["false"])
        assertTrue(open.all { tandoor.entry(it)?.checked == true } && done.none { tandoor.entry(it)?.checked == true })
    }

    @Test
    fun buyLaterSendsDelayUntilAndUndoesItWithTandoorsValue() = forEachVersion {
        val eggs = tandoor.seed("ägg", 6.0)
        read()
        val until = Instant.fromEpochMilliseconds(graph.clock.millis) + 4.hours
        ok(repo.setDelayed(listOf(eggs), until))
        assertTrue(named("ägg")!!.isDelayed(Instant.fromEpochMilliseconds(graph.clock.millis)))
        drainAll()
        val sent = Json.parseToJsonElement(tandoor.requests("PATCH", "/api/shopping-list-entry/$eggs/").last().second).jsonObject
        assertEquals(setOf("delay_until"), sent.keys)
        assertTrue(sent.getValue("delay_until").jsonPrimitive.content.endsWith("Z"))
        assertEquals(until.toEpochMilliseconds() / 1000, tandoor.entry(eggs)!!.delayUntil!! / 1000)

        ok(repo.setDelayed(listOf(eggs), null))
        drainAll()
        val undone = Json.parseToJsonElement(tandoor.requests("PATCH", "/api/shopping-list-entry/$eggs/").last().second).jsonObject
        assertEquals(JsonPrimitive("1970-01-01T00:00:00Z"), undone["delay_until"])
        assertFalse(named("ägg")!!.isDelayed(Instant.fromEpochMilliseconds(graph.clock.millis)))
        read()
        assertNull("Tandoor's 1970 is no delay", named("ägg")?.delayUntil)
    }

    @Test
    fun aRecipeOnTheListIsOneListRecipeAndEntriesThatWaitForIt() = forEachVersion {
        ok(graph.details.fetch(graph.remote, RECIPE_ID, isBackground = false))
        val ingredients = graph.real.shoppingDao().getRecipeIngredients(ACCOUNT_ID, RECIPE_ID)
            .filter { !it.isHeader && !it.noAmount && it.amount > 0 && it.foodName != null }
        assertTrue(ingredients.size >= 2)
        offline()
        val servings = (graph.real.shoppingDao().getRecipeServings(ACCOUNT_ID, RECIPE_ID) ?: 1) * 2.0
        ok(repo.addRecipe(RecipeShoppingRequest(RECIPE_ID, servings, ingredients.take(2).map { it.ingredientId }.toSet())))
        val added = entries().filter { it.recipe != null }
        assertEquals(2, added.size)
        assertTrue(added.all { it.id < 0 && it.recipe!!.listRecipeId < 0 })
        assertEquals(ingredients[0].amount * 2, added.first { it.ingredientId == ingredients[0].ingredientId }.amount, 1e-9)

        online()
        drainAll()
        val created = tandoor.requests("POST", "/api/shopping-list-recipe/").single()
        assertEquals(RECIPE_ID, Json.parseToJsonElement(created.second).jsonObject.getValue("recipe").jsonPrimitive.long)
        val listRecipeId = tandoor.all().mapNotNull { it.listRecipe }.distinct().single()
        assertNotNull(tandoor.listRecipe(listRecipeId))
        val local = entries().filter { it.recipe != null }
        assertTrue(local.all { it.id > 0 && it.recipe?.listRecipeId == listRecipeId })

        // Removing the recipe from the list: one DELETE of the list recipe, its entries go with it.
        ok(repo.removeListRecipe(added.first().recipe!!.listRecipeId))
        drainAll()
        assertEquals(1, tandoor.requests("DELETE", "/api/shopping-list-recipe/").size)
        assertTrue(tandoor.all().none { it.listRecipe == listRecipeId })
        assertTrue(entries().none { it.recipe != null })
    }

    @Test
    fun clearingCheckedEntriesCleansTheOutboxFirst() = forEachVersion {
        val ids = (1..4).map { tandoor.seed("vara $it") }
        read()
        offline()
        ok(repo.setChecked(ids.take(3), true))
        val local = add("lokal")
        ok(repo.setChecked(listOf(local), true))
        ok(repo.clearChecked())
        assertEquals(listOf("vara 4"), entries().map { it.food.name })
        val rows = graph.real.outboxDao().observeAll(ACCOUNT_ID).first()
        assertEquals("only the three DELETEs wait: ${rows.map { it.op }}", listOf("DELETE", "DELETE", "DELETE"), rows.map { it.op })
        online()
        drainAll()
        assertTrue(tandoor.requests("POST", "/api/shopping-list-entry/").isEmpty())
        assertEquals(listOf("vara 4"), tandoor.all().map { it.food })
    }

    /**
     * Phase 2 (koppling lane): a 404 that still comes is done for the row, but it COUNTS in the
     * circuit breaker (plan 3.5/4.1, CrowdSec `http-probing`): five within 100 s pause the outbox,
     * the rest waits, and after the pause it is checked against the list again before anything is sent.
     */
    @Test
    fun a404AfterTheCheckIsDoneAndCountedByTheCircuitBreaker() = forEachVersion {
        val ids = (1..6).map { tandoor.seed("vara $it") }
        read()
        // Deleted right after the full read this phone checks against: the PATCHes get their 404.
        tandoor.partnerDelete(ids)
        ids.forEach { ok(repo.update(it, amount = 5.0)) }
        assertEquals(DrainResult.STOPPED, graph.outbox.drain(graph.remote))
        assertEquals("[$version] the breaker stops after five 404s", 5, tandoor.notFound.size)
        assertEquals(SyncPause.CIRCUIT_BREAKER, graph.guard.pauseAt(graph.guard.state.value, ACCOUNT_ID, graph.clock.millis))
        assertEquals(1, graph.real.outboxDao().observeCount(ACCOUNT_ID).first())
        graph.clock.advance(6.minutes)
        drainAll()
        // The last row is checked against a new full read first and dropped without a request.
        assertEquals(5, tandoor.notFound.size)
        assertNull(graph.guard.pauseAt(graph.guard.state.value, ACCOUNT_ID, graph.clock.millis))
        assertEquals(5, graph.droppedChanges.recent.value.count { it.reason == DroppedChanges.NOT_FOUND })
        assertTrue(graph.droppedChanges.recent.value.any { it.reason == DroppedChanges.DELETED_ELSEWHERE })
        assertTrue(entries().isEmpty())
    }

    @Test
    fun a429KeepsTheChangeAndPausesPolling() = forEachVersion {
        val id = tandoor.seed("mjölk")
        read()
        graph.server.respond("POST", "/api/shopping-list-entry/bulk/") {
            MockResponse.Builder()
                .code(429)
                .addHeader("Retry-After", "120")
                .addHeader("Content-Type", "application/json")
                .body("{}")
                .build()
        }
        ok(repo.setChecked(listOf(id), true))
        assertEquals(DrainResult.STOPPED, graph.outbox.drain(graph.remote))
        assertEquals(1, graph.real.outboxDao().observeCount(ACCOUNT_ID).first())
        val foreground = foregroundOf(graph)
        assertEquals(SyncPause.BLOCKED, foreground.pauseFor(graph.remote))
        assertEquals(true, named("mjölk")?.checked)
    }

    @Test
    fun safeModeReadsTheListAndHoldsTheChanges() {
        val graph = SyncTestGraph(serverVersion = "2.6.99")
        val tandoor = FakeShoppingTandoor("2.6.13", graph.clock).also { it.install(graph.server) }
        try {
            runBlocking {
                mirror(graph)
                tandoor.seed("mjölk")
                ok(graph.shoppingSync.refresh(graph.remote, background = false, full = true))
                assertEquals(listOf("mjölk"), graph.shopping.observeEntries().first().map { it.food.name })
                ok(graph.shopping.add(ShoppingEntryDraft("bröd")))
                assertEquals(DrainResult.PAUSED, graph.outbox.drain(graph.remote))
                assertEquals(listOf(OutboxState.PAUSED), graph.real.outboxDao().observeAll(ACCOUNT_ID).first().map { it.state })
                assertTrue(tandoor.requests("POST", "/api/shopping-list-entry/").isEmpty())
                assertEquals(SyncPause.SAFE_MODE, foregroundOf(graph).pauseFor(graph.remote))
            }
        } finally {
            graph.close()
        }
    }

    // ------------------------------------------------------------------ polling

    @Test
    fun pollingUsesTheServersFloorSharesOnePollerAndStopsWithItsLastCollector() = forEachVersion {
        tandoor.seed("mjölk")
        val waits = CopyOnWriteArrayList<Duration>()
        val foreground = foregroundOf(graph) { interval ->
            waits += interval
            // Real time stands in for the interval, long enough that a test sees no extra poll.
            delay(POLL_PAUSE_MILLIS)
        }
        val target = ForegroundTarget.ShoppingList
        assertTrue(foreground.keepFresh(target) === foreground.keepFresh(target))

        val states = withTimeout(TIMEOUT_MILLIS) { foreground.keepFresh(target).take(3).toList() }
        assertTrue(states.all { it.active && it.paused == null && it.lastPollAt != null })
        assertEquals(20.seconds, states.first().interval)
        val reads = tandoor.requests("GET", "/api/shopping-list-entry/")
        assertTrue("one read per poll: $reads", reads.size >= 3)
        assertTrue("later polls are deltas", reads.drop(1).all { (call, _) -> "updated_after=" in call })
        delay(POLL_PAUSE_MILLIS * 2)
        assertEquals("no polling without a collector", reads.size, tandoor.requests("GET", "/api/shopping-list-entry/").size)

        // A server floor above 20 s wins; never below 15 s.
        graph.real.shoppingDao().setMinAutosyncSeconds(ACCOUNT_ID, 45)
        assertEquals(45.seconds, foreground.intervalFor(target, graph.remote))
        graph.real.shoppingDao().setMinAutosyncSeconds(ACCOUNT_ID, 5)
        assertEquals(20.seconds, foreground.intervalFor(target, graph.remote))

        // Offline: no request, the state says why.
        offline()
        val before = tandoor.calls.size
        val paused = withTimeout(TIMEOUT_MILLIS) { foreground.keepFresh(target).take(2).toList() }.last()
        assertEquals(SyncPause.OFFLINE, paused.paused)
        assertFalse(paused.active)
        assertEquals(before, tandoor.calls.size)
        assertTrue(waits.isNotEmpty())
    }

    /** Phase 2 (integration lane): the user's `shopping_auto_sync` slows polling down, and 0 turns it off. */
    @Test
    fun theUsersAutoSyncPreferenceSlowsOrStopsPolling() = forEachVersion {
        tandoor.seed("mjölk")
        val foreground = foregroundOf(graph) { delay(POLL_PAUSE_MILLIS) }
        val target = ForegroundTarget.ShoppingList
        graph.real.shoppingDao().setMinAutosyncSeconds(ACCOUNT_ID, 5)
        autoSync(graph, seconds = 40)
        assertEquals(40.seconds, foreground.intervalFor(target, graph.remote))
        autoSync(graph, seconds = 5)
        assertEquals(20.seconds, foreground.intervalFor(target, graph.remote))

        autoSync(graph, seconds = 0)
        tandoor.calls.clear()
        val states = withTimeout(TIMEOUT_MILLIS) { foreground.keepFresh(target).take(2).toList() }
        assertTrue("not polling with the web's sync off: $states", states.none { it.active || it.lastPollAt != null })
        assertTrue("no request: ${tandoor.calls}", tandoor.calls.isEmpty())
        // Pull to refresh still reads the list.
        ok(graph.shoppingSync.refresh(graph.remote, background = false))
        assertEquals(listOf("mjölk"), entries().map { it.food.name })
    }

    private suspend fun autoSync(graph: SyncTestGraph, seconds: Int) {
        graph.real.referenceDao().upsertUserPreference(
            UserPreferenceEntity(
                accountId = ACCOUNT_ID,
                useFractions = false,
                ingredientDecimals = 2,
                defaultUnit = "g",
                useKj = false,
                showStepIngredients = true,
                defaultMealTypeId = null,
                shoppingAutoSyncSeconds = seconds,
                mealPlanAutoAddShopping = false,
                mealPlanAutoExcludeOnHand = true,
                mealPlanAutoIncludeRelated = false,
                defaultDelayHours = 4.0,
                filterToSupermarket = false,
                shoppingRecentDays = 7,
                shoppingAddOnHand = false,
                commentsEnabled = true,
            ),
        )
    }

    @Test
    fun theServersAutosyncFloorIsReadWithTheSmallTables() = forEachVersion {
        graph.shoppingTables.sync(graph.remote, background = false)
        // fas2/server-settings is not routed; the Phase 0 recording says "5" (a STRING).
        assertEquals(5, graph.real.shoppingDao().getMinAutosyncSeconds(ACCOUNT_ID))
        assertEquals(listOf("Testbutiken"), graph.supermarkets.observeSupermarkets().first().map { it.name })
        val aisles = graph.supermarkets.observeSupermarkets().first().single().aisles
        assertEquals(listOf("Grönsaker", "Mejeri", "Övrigt"), aisles.map { it.category.name })
        assertEquals(listOf("Veckohandling"), repo.observeLists().first().map { it.name })
    }

    // ------------------------------------------------------------------ guests, accounts, demo

    @Test
    fun aGuestCannotChangeTheListAndNothingIsQueued() = forEachVersion {
        val id = tandoor.seed("mjölk")
        read()
        graph.permissions.setGuest(true)
        val refused = Outcome.Failure(DataProblem.READ_ONLY.toError())
        assertEquals(refused, repo.add(ShoppingEntryDraft("bröd")))
        assertEquals(refused, repo.setChecked(listOf(id), true))
        assertEquals(refused, repo.clearChecked())
        assertEquals(refused, graph.supermarkets.setFoodOnHand(1, true))
        ok(graph.supermarkets.selectSupermarket(1))
        assertEquals(listOf("mjölk"), entries().map { it.food.name })
        assertFalse(entries().single().checked)
        assertEquals(0, graph.real.outboxDao().observeCount(ACCOUNT_ID).first())
    }

    @Test
    fun accountsNeverSeeEachOthersList() = forEachVersion {
        tandoor.seed("mjölk")
        read()
        add("bröd")
        val other = ActiveSource.Remote(graph.account.copy(id = "account-other"), graph.api)
        graph.sources.source.value = other
        assertTrue(entries().isEmpty())
        assertEquals(0, repo.observePendingCount().first())
        repo.add(ShoppingEntryDraft("ost"))
        assertEquals(listOf("ost"), entries().map { it.food.name })

        graph.sources.source.value = graph.remote
        assertEquals(setOf("mjölk", "bröd"), entries().map { it.food.name }.toSet())
        graph.databases.clearAccount(DatabaseKind.REAL, "account-other")
        assertEquals(setOf("mjölk", "bröd"), entries().map { it.food.name }.toSet())
        assertEquals(1, graph.real.outboxDao().observeCount(ACCOUNT_ID).first())
    }

    @Test
    fun demoModeHasARealisticListAndNeverTouchesTheNetwork() = forEachVersion {
        graph.sources.source.value = ActiveSource.Demo
        ok(graph.demo.ensureSeeded())
        val list = entries()
        assertTrue("${list.size} demo entries", list.size >= 15)
        assertTrue(list.any { it.checked } && list.any { it.recipe != null } && list.any { it.addedBy == "Alex" })
        assertTrue(list.any { it.isDelayed(Instant.fromEpochMilliseconds(graph.clock.millis)) })
        assertEquals(2, graph.supermarkets.observeSupermarkets().first().size)
        val milk = list.first { it.food.name == "mjölk" }
        ok(repo.setChecked(listOf(milk.id), true))
        val added = add("chokladkaka")
        ok(repo.refresh(full = true))
        ok(graph.demo.ensureSeeded())
        assertNotNull("the demo keeps what the user did", entries().firstOrNull { it.id == added })
        assertEquals(true, entries().first { it.food.name == "mjölk" }.checked)
        assertEquals(0, graph.demoDatabase.outboxDao().observeCount("demo").first())
        assertEquals(0, repo.observePendingCount().first())
        assertTrue("demo mode sends nothing: ${graph.server.requests}", graph.server.requests.isEmpty())
        assertTrue(graph.real.shoppingDao().getEntries(ACCOUNT_ID).isEmpty())
        val text = (repo.shareText() as Outcome.Success).value
        assertTrue(text, "2 l mjölk" !in text.lines() && "1 påse kaffe" in text.lines())
    }

    private fun foregroundOf(graph: SyncTestGraph, wait: suspend (Duration) -> Unit = {}) = DefaultForegroundSync(
        graph.sources,
        graph.shoppingSync,
        { FakeMealPlanRepository() },
        graph.databases,
        graph.guard,
        graph.network,
        graph.clock,
        graph.scope,
        wait,
    )

    private companion object {
        val VERSIONS = listOf("2.6.13", "2.7.1")
        const val MAX_DRAINS = 10
        const val RECIPE_ID = 3L
        const val TIMEOUT_MILLIS = 15_000L
        const val SETTLE_MILLIS = 100L
        const val POLL_PAUSE_MILLIS = 1_000L
    }
}
