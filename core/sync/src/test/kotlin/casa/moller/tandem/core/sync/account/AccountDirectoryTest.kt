package casa.moller.tandem.core.sync.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.account.AccountRemoval
import casa.moller.tandem.core.data.account.AccountSessions
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.model.account.AccountSummary
import casa.moller.tandem.core.model.account.Household
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.errorOrNull
import casa.moller.tandem.core.model.error.getOrNull
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.shopping.ShoppingEntryDraft
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.sync.data.RoomPendingChangesExporter
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import casa.moller.tandem.core.testing.fixtures.TandoorFixtures
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Phase 2 (konto lane): several stored accounts with one active (B14, plan 6.2 "Konto/server"):
 * the switcher's list (household, role, unsent changes; demo mode is never an account),
 * switching keeps each account's rows and stops the old account's work, removing warns about and
 * exports unsent changes, and logging in again to the same account keeps them.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AccountDirectoryTest {
    private val graph = SyncTestGraph()
    private val accountA = graph.account
    private val accountB = ServerAccount(
        id = ACCOUNT_B,
        baseUrl = accountA.baseUrl,
        username = "bo",
        userId = 2,
        spaceId = 1,
        serverVersion = "2.7.1",
        addedAt = Instant.fromEpochMilliseconds(1),
    )
    private val accountSessions = TestAccountSessions()
    private val directory = DefaultAccountDirectory(
        accountSessions = accountSessions,
        sessions = graph.sessions,
        accountStore = graph.accountStore,
        databases = graph.databases,
        sources = graph.sources,
        roles = graph.roleSync,
        mirror = graph.mirror,
        exporter = RoomPendingChangesExporter(graph.databases),
        scheduler = graph.scheduler,
    )

    @After
    fun tearDown() {
        graph.close()
    }

    private suspend fun storeBothAccounts() {
        graph.accountStore.state.value = listOf(accountA, accountB)
        val dao = graph.real.accountDao()
        dao.upsert(mirrorRow(accountA).copy(householdId = 1, householdName = "Hemma", groupLevel = "admin"))
        dao.upsert(mirrorRow(accountB).copy(groupLevel = "guest"))
    }

    private fun mirrorRow(account: ServerAccount) = ServerAccountEntity(
        id = account.id,
        baseUrl = account.baseUrl,
        userId = account.userId,
        spaceId = account.spaceId,
        serverVersion = account.serverVersion,
    )

    private suspend fun queueChange(accountId: String, entryId: Long): Long = graph.real.outboxDao().insert(
        OutboxEntity(
            accountId = accountId,
            op = OutboxOps.PATCH,
            entity = OutboxEntities.SHOPPING_ENTRY,
            serverId = entryId,
            payload = """{"checked": true}""",
            createdAtEpochMillis = 0,
        ),
    )

    private suspend fun pending(accountId: String): Int = graph.real.outboxDao().observeCount(accountId).first()

    private suspend fun accounts(condition: (List<AccountSummary>) -> Boolean): List<AccountSummary> =
        directory.observeAccounts().first(condition)

    @Test
    fun listsEveryStoredAccountWithHouseholdRoleAndUnsentChanges() = runBlocking {
        storeBothAccounts()
        queueChange(ACCOUNT_B, entryId = 5)

        val list = accounts { list -> list.size == 2 && list.any { it.pendingChanges == 1 } }

        val a = list.single { it.account.id == ACCOUNT_ID }
        assertTrue(a.isActive)
        assertEquals(Household(1, "Hemma"), a.household)
        assertEquals(SpaceRole.ADMIN, a.role)
        assertEquals(0, a.pendingChanges)
        assertEquals(ServerCompatibility.SUPPORTED, a.compatibility)
        val b = list.single { it.account.id == ACCOUNT_B }
        assertFalse(b.isActive)
        assertNull(b.household)
        assertEquals(SpaceRole.GUEST, b.role)
        assertEquals(1, b.pendingChanges)
    }

    @Test
    fun demoModeIsNeverAnAccount() = runBlocking {
        storeBothAccounts()
        graph.sessions.sessionState.value = Session.Demo
        graph.sources.source.value = ActiveSource.Demo

        val list = accounts { list -> list.size == 2 && list.none { it.isActive } }

        assertEquals(setOf(ACCOUNT_ID, ACCOUNT_B), list.map { it.account.id }.toSet())
        assertNull(directory.observeHousehold().first())
        assertEquals(Outcome.Success(null), directory.refreshHousehold())
        assertTrue("no server is asked in demo mode", graph.server.requestsTo("GET", "/api/user-space/all_personal/").isEmpty())
    }

    @Test
    fun switchingKeepsEachAccountsRowsApart() = runBlocking {
        storeBothAccounts()
        graph.network.online.value = false
        assertTrue(graph.shopping.add(ShoppingEntryDraft(foodName = "grädde", amount = 2.0)) is Outcome.Success)
        assertEquals(listOf("grädde"), graph.shopping.observeEntries().first().map { it.food.name })

        assertTrue(directory.switchTo(ACCOUNT_B) is Outcome.Success)

        assertEquals(ACCOUNT_B, graph.settings.state.value.activeAccountId)
        assertTrue("B sees none of A's list", graph.shopping.observeEntries().first().isEmpty())
        assertEquals(1, pending(ACCOUNT_ID))
        assertEquals(0, pending(ACCOUNT_B))

        assertTrue(directory.switchTo(ACCOUNT_ID) is Outcome.Success)

        assertEquals(listOf("grädde"), graph.shopping.observeEntries().first().map { it.food.name })
        assertEquals("A's unsent change waits for A", 1, pending(ACCOUNT_ID))
    }

    @Test
    fun switchingStopsTheOldAccountsOutboxDrain() = runBlocking {
        storeBothAccounts()
        queueChange(ACCOUNT_ID, entryId = 5)
        val oldRemote = graph.sources.source.value as ActiveSource.Remote

        assertTrue(directory.switchTo(ACCOUNT_B) is Outcome.Success)

        assertEquals(DrainResult.STOPPED, graph.outbox.drain(oldRemote))
        assertTrue("nothing sent for A after the switch", graph.server.requestsTo("PATCH", "/api/shopping-list-entry/5/").isEmpty())
        assertEquals(1, pending(ACCOUNT_ID))
    }

    @Test
    fun switchingFromDemoModeAsksForTheAccountsSync() = runBlocking {
        storeBothAccounts()
        graph.sessions.sessionState.value = Session.Demo
        graph.sources.source.value = ActiveSource.Demo

        assertTrue(directory.switchTo(ACCOUNT_B) is Outcome.Success)

        assertFalse(graph.settings.state.value.demoMode)
        assertEquals(listOf(SyncReason.ACCOUNT_CHANGED), graph.scheduler.requested.toList())
    }

    @Test
    fun switchingToTheActiveOrAnUnknownAccountChangesNothing() = runBlocking {
        storeBothAccounts()

        assertTrue(directory.switchTo(ACCOUNT_ID) is Outcome.Success)
        assertEquals(DataProblem.NO_ACCOUNT, directory.switchTo("nobody").errorOrNull()?.dataProblem())

        assertTrue(accountSessions.activated.isEmpty())
        assertTrue(graph.scheduler.requested.isEmpty())
    }

    @Test
    fun removingWithUnsentChangesWarnsExportsAndOnlyThenRemoves() = runBlocking {
        storeBothAccounts()
        queueChange(ACCOUNT_B, entryId = 7)
        queueChange(ACCOUNT_ID, entryId = 5)

        val first = directory.remove(ACCOUNT_B, revokeToken = true).getOrNull()

        assertEquals(AccountRemoval.PendingChanges(1), first)
        assertTrue("nothing removed before the user confirms", accountSessions.removed.isEmpty())
        assertEquals(1, pending(ACCOUNT_B))

        val export = directory.exportPendingChanges(ACCOUNT_B).getOrNull()!!
        assertTrue(export, "\"format\": \"tandem-pending-changes\"" in export)
        assertTrue(export, "\"id\": 7" in export)
        assertFalse("only B's changes", "\"id\": 5" in export)
        assertFalse("no server address", accountA.baseUrl in export)

        val removed = directory.remove(ACCOUNT_B, revokeToken = true, discardPendingChanges = true).getOrNull()

        assertEquals(AccountRemoval.Removed(TokenRevocation.SKIPPED, accountA), removed)
        assertEquals(listOf(ACCOUNT_B to true), accountSessions.removed)
        assertFalse("B's rows are gone", ACCOUNT_B in graph.real.accountDao().getStoredAccountIds())
        assertEquals("A keeps its own", 1, pending(ACCOUNT_ID))
        assertEquals(listOf(ACCOUNT_ID), accounts { it.size == 1 }.map { it.account.id })
    }

    @Test
    fun removingWithoutUnsentChangesRemovesAtOnce() = runBlocking {
        storeBothAccounts()

        val removed = directory.remove(ACCOUNT_ID, revokeToken = false).getOrNull()

        assertEquals(AccountRemoval.Removed(TokenRevocation.SKIPPED, accountB), removed)
        assertFalse(ACCOUNT_ID in graph.real.accountDao().getStoredAccountIds())
        assertEquals(DataProblem.NO_ACCOUNT, directory.remove("nobody", revokeToken = false).errorOrNull()?.dataProblem())
    }

    @Test
    fun loggingInAgainToTheSameAccountKeepsItsUnsentChanges() = runBlocking {
        graph.mirror.onSession(Session.LoggedIn(accountA, accountA.serverVersion, ServerCompatibility.SUPPORTED))
        queueChange(ACCOUNT_ID, entryId = 5)

        // A new login to the same user, space and server keeps the local id (DefaultAccountRepository).
        val again = accountA.copy(tokenId = 99, serverVersion = "2.6.15")
        graph.accountStore.upsert(again, "new-token")
        graph.mirror.onSession(Session.LoggedIn(again, again.serverVersion, ServerCompatibility.SUPPORTED))

        assertEquals(1, pending(ACCOUNT_ID))
        assertEquals(99L, graph.real.accountDao().get(ACCOUNT_ID)?.tokenId)
    }

    @Test
    fun refreshHouseholdReadsTheMembershipAndKeepsItThroughTheMirror() = runBlocking {
        graph.mirror.onSession(Session.LoggedIn(accountA, accountA.serverVersion, ServerCompatibility.SUPPORTED))
        graph.server.respond("GET", "/api/user-space/all_personal/") {
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(TandoorFixtures.exchange("fas2/user-space-owner").body!!)
                .build()
        }

        val household = directory.refreshHousehold().getOrNull()

        assertEquals("Contract Household", household?.name)
        assertEquals(household, directory.observeHousehold().first())
        // A later mirror write (a new version) keeps the household.
        val updated = accountA.copy(serverVersion = "2.6.15")
        graph.mirror.onSession(Session.LoggedIn(updated, updated.serverVersion, ServerCompatibility.SUPPORTED))
        assertEquals("Contract Household", graph.real.accountDao().get(ACCOUNT_ID)?.householdName)
        assertEquals(SpaceRole.ADMIN, accounts { it.isNotEmpty() }.single().role)
    }

    /** [AccountSessions] over the graph's account store, session and source (core:data's is tested there). */
    private inner class TestAccountSessions : AccountSessions {
        val activated = mutableListOf<String>()
        val removed = mutableListOf<Pair<String, Boolean>>()

        override suspend fun activate(accountId: String): Outcome<Unit> {
            val account = graph.accountStore.state.value.first { it.id == accountId }
            activated += accountId
            graph.settings.update { it.copy(activeAccountId = accountId, demoMode = false) }
            graph.sessions.sessionState.value = Session.LoggedIn(account, account.serverVersion, ServerCompatibility.SUPPORTED)
            graph.sources.source.value = ActiveSource.Remote(account, graph.api)
            return Outcome.Success(Unit)
        }

        override suspend fun remove(accountId: String, revokeToken: Boolean): Outcome<TokenRevocation> {
            removed += accountId to revokeToken
            graph.accountStore.remove(accountId)
            val session = graph.sessions.sessionState.value
            if (session is Session.LoggedIn && session.account.id == accountId) {
                val next = graph.accountStore.state.value.firstOrNull()
                if (next != null) {
                    activate(next.id)
                } else {
                    graph.sessions.sessionState.value = Session.NoAccount
                    graph.sources.source.value = ActiveSource.None
                }
            }
            return Outcome.Success(TokenRevocation.SKIPPED)
        }
    }

    private companion object {
        const val ACCOUNT_B = "account-b"
    }
}
