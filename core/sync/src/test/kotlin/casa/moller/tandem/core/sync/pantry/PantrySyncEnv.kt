package casa.moller.tandem.core.sync.pantry

import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue

/**
 * Fas S (hemma-data lane): one [SyncTestGraph] and one stateful [FakePantryTandoor] per recorded
 * version (2.6.13 and 2.7.1), the account a member of household 1 ("Contract Household", as in the
 * contract), with helpers for drains and the network.
 */
internal class PantrySyncEnv(val graph: SyncTestGraph, val tandoor: FakePantryTandoor, val version: String) {
    val pantry = graph.pantry

    /** Sends the whole outbox (several drains of at most 50 rows). */
    suspend fun drainAll() {
        repeat(MAX_DRAINS) {
            val result = graph.outbox.drain(graph.remote)
            if (result == DrainResult.DONE && graph.real.outboxDao().observeCount(ACCOUNT_ID).first() == 0) return
        }
        throw AssertionError("[$version] the outbox did not drain: ${graph.real.outboxDao().observeAll(ACCOUNT_ID).first()}")
    }

    suspend fun read() = ok(graph.pantryReader.sync(graph.remote, background = false))

    suspend fun outboxCount(): Int = graph.real.outboxDao().observeCount(ACCOUNT_ID).first()

    fun offline() {
        graph.network.online.value = false
    }

    fun online() {
        graph.network.online.value = true
    }

    fun ok(outcome: Outcome<*>) = assertTrue("[$version] $outcome", outcome is Outcome.Success)

    @Suppress("UNCHECKED_CAST")
    fun <T> value(outcome: Outcome<T>): T = (outcome as? Outcome.Success<T>)?.value ?: throw AssertionError("[$version] $outcome")

    companion object {
        val VERSIONS = listOf("2.6.13", "2.7.1")
        private const val MAX_DRAINS = 10

        /** Runs [block] once per recorded version, each with a fresh graph and fake. */
        fun forEachVersion(block: suspend PantrySyncEnv.() -> Unit) {
            for (version in VERSIONS) {
                val graph = SyncTestGraph(serverVersion = version)
                val tandoor = FakePantryTandoor(version).also { it.install(graph.server) }
                try {
                    runBlocking {
                        mirror(graph)
                        PantrySyncEnv(graph, tandoor, version).block()
                    }
                } finally {
                    graph.close()
                }
            }
        }

        /** The account's `server_account` row as `AccountMirror` and the role read write it: a member of household 1. */
        suspend fun mirror(graph: SyncTestGraph, groupLevel: String = "user") {
            val account = graph.account
            val row = ServerAccountEntity(
                id = account.id,
                baseUrl = account.baseUrl,
                userId = account.userId,
                spaceId = account.spaceId,
                householdId = FakePantryTandoor.OWN_HOUSEHOLD,
                householdName = FakePantryTandoor.OWN_HOUSEHOLD_NAME,
                groupLevel = groupLevel,
            )
            graph.real.accountDao().upsert(row)
        }
    }
}
