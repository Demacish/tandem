package casa.moller.tandem.core.watch

import app.cash.turbine.test
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.core.model.watch.WatchAvailability
import casa.moller.tandem.core.model.watch.WatchBlock
import casa.moller.tandem.core.model.watch.WatchOutcome
import casa.moller.tandem.core.model.watch.WatchResult
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.time.TestClock
import casa.moller.tandem.core.watchlink.UnpairRequest
import casa.moller.tandem.core.watchlink.WatchTokenPolicy
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fas 5 (wear lane): the token exchange through a fake Data Layer whose watch answers like the
 * watch app. The watch gets its OWN token (never the phone's), a watch that does not take it
 * leaves no token behind, and unpairing deletes the token on the server and wipes the watch.
 */
class DefaultWatchRepositoryTest {
    private val anna = account()
    private val sessions = FakeSessions(FakeSessions.loggedIn(anna))
    private val accounts = FakeAccounts(anna)
    private val permissions = FakeSpacePermissionsRepository()
    private val tokens = FakeWatchTokens()
    private val layer = FakeWatchLayer()
    private val store = InMemoryPairingStore()
    private val clock = TestClock()
    private val repository = DefaultWatchRepository(sessions, accounts, permissions, tokens, layer, store, clock)

    @Test
    fun pairingSendsTheWatchItsOwnTokenAndKeepsOnlyItsId() = runTest {
        val result = repository.pair()

        assertEquals(WatchResult(WatchOutcome.PAIRED), result)
        val sent = requireNotNull(layer.stored)
        assertEquals("https://tandoor.example.com/", sent.baseUrl)
        assertEquals(listOf(100L), tokens.created)
        assertEquals(100L, sent.tokenId)
        assertFalse("never the phone's token", sent.token.contains("phone-"))
        assertEquals(anna.id, sent.accountKey)
        assertEquals("Anna", sent.accountLabel)
        assertEquals((clock.now() + WatchTokenPolicy.LIFETIME).toEpochMilliseconds(), sent.expiresEpochMillis)
        val pairing = requireNotNull(store.stored.value)
        assertEquals(100L, pairing.tokenId)
        assertEquals("node-1", pairing.nodeId)
        assertFalse(pairing.toString().contains("tda_"))
        assertEquals("Galaxy Watch", repository.state.first().paired?.watchName)
    }

    @Test
    fun aWatchThatDoesNotTakeTheTokenLeavesNoTokenBehind() = runTest {
        layer.refuses = true

        assertEquals(WatchOutcome.WATCH_REFUSED, repository.pair().outcome)

        assertEquals(listOf("phone-account-1" to 100L), tokens.deleted)
        assertNull(store.stored.value)
    }

    @Test
    fun onlyANearbyWatchWithTandemIsPaired() = runTest {
        layer.watch = WatchNode("node-1", "Galaxy Watch", nearby = false)
        assertEquals(WatchOutcome.NO_WATCH, repository.pair().outcome)
        assertTrue("no token is made for a watch that is away", tokens.created.isEmpty())

        layer.watch = WatchNode("node-1", "Galaxy Watch", nearby = true)
        layer.hasTandem = false
        repository.refresh()
        assertEquals(WatchAvailability.NO_APP, repository.state.first().availability)
        assertTrue(repository.state.first().showInMore)

        layer.available = false
        repository.refresh()
        val unavailable = repository.state.first()
        assertEquals(WatchAvailability.UNAVAILABLE, unavailable.availability)
        assertFalse("no Play services: Mer shows no watch row", unavailable.showInMore)
    }

    @Test
    fun demoGuestsAndLoggedOutCannotPair() = runTest {
        sessions.current.value = Session.Demo
        assertEquals(WatchBlock.DEMO, repository.state.first().block)
        assertEquals(WatchOutcome.NOT_ALLOWED, repository.pair().outcome)

        sessions.current.value = FakeSessions.loggedIn(anna)
        permissions.state.value = SpacePermissions(SpaceRole.GUEST)
        assertEquals(WatchBlock.GUEST, repository.state.first().block)
        assertEquals(WatchOutcome.NOT_ALLOWED, repository.pair().outcome)

        sessions.current.value = Session.NoAccount
        assertEquals(WatchBlock.NO_ACCOUNT, repository.state.first().block)
        assertTrue(tokens.created.isEmpty())
        assertNull(layer.stored)
    }

    @Test
    fun aServerErrorIsReportedAndNothingIsSent() = runTest {
        tokens.failCreate = TandemError.Unauthorized(UnauthorizedSource.TANDOOR)

        assertEquals(WatchResult(WatchOutcome.SERVER, TandemError.Unauthorized(UnauthorizedSource.TANDOOR)), repository.pair())
        assertNull(layer.stored)
    }

    @Test
    fun pairingAgainReplacesTheOldToken() = runTest {
        repository.pair()
        repository.pair()

        assertEquals(listOf(100L, 101L), tokens.created)
        assertEquals(listOf("phone-account-1" to 100L), tokens.deleted)
        assertEquals(101L, layer.stored?.tokenId)
    }

    @Test
    fun unpairingDeletesTheTokenOnTheServerAndWipesTheWatch() = runTest {
        repository.pair()

        assertEquals(WatchOutcome.UNPAIRED, repository.unpair().outcome)

        assertEquals(listOf("phone-account-1" to 100L), tokens.deleted)
        assertEquals(listOf(UnpairRequest(tokenId = 100, revoke = false)), layer.unpairs)
        assertNull(layer.stored)
        assertNull(store.stored.value)
    }

    @Test
    fun unpairingAWatchThatIsAwayStillDeletesTheToken() = runTest {
        repository.pair()
        layer.watch = null

        assertEquals(WatchOutcome.UNPAIRED_WATCH_AWAY, repository.unpair().outcome)
        assertEquals(listOf("phone-account-1" to 100L), tokens.deleted)
        assertNull(store.stored.value)
    }

    @Test
    fun withoutTheServerTheWatchDeletesItsOwnToken() = runTest {
        repository.pair()
        tokens.failDelete = TandemError.NetworkUnavailable

        assertEquals(WatchOutcome.UNPAIRED, repository.unpair().outcome)
        assertEquals(listOf(UnpairRequest(tokenId = 100, revoke = true)), layer.unpairs)
    }

    @Test
    fun aLoggedOutAccountsWatchDeletesItsOwnTokenWhenReachable() = runTest {
        repository.pair()
        accounts.remove(anna.id)
        layer.watch = null

        repository.maintain()
        assertEquals(true, store.stored.value?.revokeOnWatch)

        layer.watch = WatchNode("node-1", "Galaxy Watch", nearby = true)
        repository.maintain()
        assertEquals(listOf(UnpairRequest(tokenId = 100, revoke = true)), layer.unpairs)
        assertNull(store.stored.value)
    }

    @Test
    fun thePhoneRenewsTheTokenThirtyDaysBeforeItExpires() = runTest {
        repository.pair()
        clock.advanceBy(59.days)
        repository.maintain()
        assertEquals(listOf(100L), tokens.created)

        clock.advanceBy(1.days)
        repository.maintain()
        assertEquals(listOf(100L, 101L), tokens.created)
        assertEquals(listOf("phone-account-1" to 100L), tokens.deleted)
    }

    @Test
    fun theStateSaysWhenTheWatchBelongsToAnotherAccount() = runTest {
        repository.pair()
        val other = account("account-2")
        accounts.upsert(other, "unused")

        repository.state.test {
            assertFalse(awaitItem().pairedForAnotherAccount)
            sessions.current.value = FakeSessions.loggedIn(other)
            assertTrue(awaitItem().pairedForAnotherAccount)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
