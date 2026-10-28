package casa.moller.tandem.core.sync.shopping

import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingEntryDraft
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue

/**
 * The set-up [ShoppingSyncTest] and [ShoppingReviewSyncTest] share: one [SyncTestGraph] and one
 * stateful [FakeShoppingTandoor] per recorded version (2.6.13 and 2.7.1), with helpers for reads,
 * drains and the network.
 */
internal class ShoppingSyncEnv(val graph: SyncTestGraph, val tandoor: FakeShoppingTandoor, val version: String) {
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

    fun ok(outcome: Outcome<*>) = assertTrue("$outcome", outcome is Outcome.Success)

    companion object {
        val VERSIONS = listOf("2.6.13", "2.7.1")
        private const val MAX_DRAINS = 10

        /** Runs [block] once per recorded version, each with a fresh graph and fake. */
        fun forEachVersion(block: suspend ShoppingSyncEnv.() -> Unit) {
            for (version in VERSIONS) {
                val graph = SyncTestGraph(serverVersion = version)
                val tandoor = FakeShoppingTandoor(version, graph.clock).also { it.install(graph.server) }
                try {
                    runBlocking {
                        mirror(graph)
                        ShoppingSyncEnv(graph, tandoor, version).block()
                    }
                } finally {
                    graph.close()
                }
            }
        }

        /** The account's `server_account` row, as `AccountMirror` writes it after a login. */
        suspend fun mirror(graph: SyncTestGraph) {
            val account = graph.account
            val row = ServerAccountEntity(id = account.id, baseUrl = account.baseUrl, userId = account.userId, spaceId = account.spaceId)
            graph.real.accountDao().upsert(row)
        }
    }
}
