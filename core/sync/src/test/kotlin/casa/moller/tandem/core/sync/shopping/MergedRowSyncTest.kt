package casa.moller.tandem.core.sync.shopping

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.model.shopping.UnitConversions
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Fas 5, lane handla (plan §8.2 "Sammanslagning i vyn"): what checking a merged row does to the
 * outbox and the server (the view merges, the rows stay separate), and the conversion cache the
 * merging reads (per account, refreshed with the small tables, never asked for by a guest).
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MergedRowSyncTest {
    private fun forEachVersion(block: suspend ShoppingSyncEnv.() -> Unit) = ShoppingSyncEnv.forEachVersion(block)

    private fun bulks(env: ShoppingSyncEnv) = env.tandoor.requests("POST", "/api/shopping-list-entry/bulk/")
        .map { (_, body) -> Json.parseToJsonElement(body).jsonObject }
        .map { it.getValue("checked").jsonPrimitive.content to it.getValue("ids").jsonArray.map { id -> id.jsonPrimitive.long }.toSet() }

    @Test
    fun checkingAMergedRowQueuesEveryEntryAndSendsOneBulk() = forEachVersion {
        // "3 dl + 200 g + 1 st grädde" is one row on the screen and three entries on the server.
        val rows = listOf(tandoor.seed("grädde", 3.0, "dl"), tandoor.seed("grädde", 200.0, "g"), tandoor.seed("grädde", 1.0, "st"))
        read()
        offline()
        ok(repo.setChecked(rows, true))
        val queued = graph.real.outboxDao().getAllFor(ACCOUNT_ID, OutboxEntities.SHOPPING_ENTRY)
        assertEquals(rows.toSet(), queued.mapNotNull { it.serverId }.toSet())
        assertTrue(queued.all { it.op == OutboxOps.BULK_CHECK })
        assertEquals("one batch: one request", 1, queued.mapNotNull { it.batchKey }.distinct().size)
        assertTrue(entries().filter { it.food.name == "grädde" }.all { it.checked })

        online()
        drainAll()
        assertEquals(listOf("true" to rows.toSet()), bulks(this))
        assertTrue(rows.all { tandoor.entry(it)?.checked == true })

        // Unchecking the merged row takes all of them back, again in one call.
        ok(repo.setChecked(rows, false))
        drainAll()
        assertEquals(listOf("true" to rows.toSet(), "false" to rows.toSet()), bulks(this))
        assertTrue(rows.none { tandoor.entry(it)?.checked == true })
    }

    @Test
    fun checkingAndUncheckingBeforeSendingSendsNothing() = forEachVersion {
        val rows = listOf(tandoor.seed("mjölk", 2.0, "l"), tandoor.seed("mjölk", 1.0, "l"))
        read()
        offline()
        ok(repo.setChecked(rows, true))
        ok(repo.setChecked(rows, false))
        assertEquals(0, graph.real.outboxDao().getAllFor(ACCOUNT_ID, OutboxEntities.SHOPPING_ENTRY).size)
        online()
        drainAll()
        assertTrue(bulks(this).isEmpty())
    }

    @Test
    fun theConversionsAreCachedWithTheSmallTablesPerAccount() = forEachVersion {
        val repository = SyncStateUnitConversionRepository(graph.sources, graph.databases)
        assertEquals(UnitConversions.NONE, repository.observeConversions().first())

        graph.shoppingTables.sync(graph.remote, background = false)
        // fas1/unit-conversion-list: 1 dl = 100 ml for every food, 1 dl vetemjöl = 60 g.
        val cached = repository.observeConversions().first()
        assertEquals(2, cached.rules.size)
        val general = cached.rules.single { it.foodId == null }
        val flour = cached.rules.single { it.foodId != null }
        assertEquals(listOf(1.0, 100.0), listOf(general.baseAmount, general.convertedAmount))
        assertEquals(listOf(1.0, 60.0), listOf(flour.baseAmount, flour.convertedAmount))
        assertEquals("dl", cached.units.single { it.id == general.baseUnitId }.name)
        assertEquals("ml", cached.units.single { it.id == general.convertedUnitId }.baseUnit)
        assertEquals(1, graph.server.requestsTo("GET", "/api/unit-conversion/").size)

        // Another source (demo mode) has its own cache: nothing from this account.
        graph.sources.source.value = ActiveSource.Demo
        assertTrue(repository.observeConversions().first().rules.isEmpty())
        graph.sources.source.value = graph.remote
        assertEquals(cached, repository.observeConversions().first())
    }

    @Test
    fun aGuestNeverAsksForConversions() = forEachVersion {
        // Tandoor answers a guest 403 with a `detail` (FACTS "Fas 5"): the guard would log it out.
        graph.real.accountDao().setMembership(ACCOUNT_ID, groupLevel = "guest", householdId = null, householdName = null)
        graph.shoppingTables.sync(graph.remote, background = false)
        assertTrue(graph.server.requestsTo("GET", "/api/unit-conversion/").isEmpty())
        assertEquals(UnitConversions.NONE, SyncStateUnitConversionRepository(graph.sources, graph.databases).observeConversions().first())
    }

    @Test
    fun theCacheSurvivesBrokenTextAsNoConversions() {
        assertEquals(UnitConversions.NONE, SyncStateUnitConversionRepository.decode("{not json"))
        assertEquals(UnitConversions.NONE, SyncStateUnitConversionRepository.decode(null))
        val conversions = SyncStateUnitConversionRepository.of(emptyList())
        assertEquals(conversions, SyncStateUnitConversionRepository.decode(SyncStateUnitConversionRepository.encode(conversions)))
    }
}
