package casa.moller.tandem.core.sync.data

import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.network.api.TandoorApiFactory
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.data.DefaultServerReachability.Companion.MIN_CHECK_SPACING_MILLIS
import casa.moller.tandem.core.sync.data.DefaultServerReachability.Companion.RECONNECT_DEBOUNCE_MILLIS
import casa.moller.tandem.core.sync.guard.SyncGuard
import casa.moller.tandem.core.sync.remote.RemoteCaller
import casa.moller.tandem.core.sync.testing.FakeClock
import casa.moller.tandem.core.sync.testing.FakeNetwork
import casa.moller.tandem.core.sync.testing.FakeSessionRepository
import casa.moller.tandem.core.sync.testing.FakeSources
import java.net.UnknownHostException
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.8.0 device test B19: the app's one offline state follows the network and the server's last
 * answer, a returning network sends one debounced check, and a flapping network sends no burst.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerReachabilityTest {
    private val remote = remote(ACCOUNT)
    private val sources = FakeSources(remote)
    private val network = FakeNetwork(online = true)
    private val contact = ServerContact()
    private val clock = FakeClock()
    private val guard = SyncGuard(clock)
    private val sessions = FakeSessionRepository(Session.LoggedIn(remote.account, "2.6.13", ServerCompatibility.SUPPORTED))

    private fun TestScope.reachability(scope: CoroutineScope = backgroundScope) = DefaultServerReachability(
        contact = contact,
        network = network,
        sources = sources,
        sessions = { sessions },
        guard = guard,
        scope = scope,
        elapsedMillis = { testScheduler.currentTime },
    ).also { runCurrent() }

    @Test
    fun withoutNetworkTheAppIsOfflineAndWithItAgainAtOnceWhenNothingFailed() = runTest {
        network.online.value = false
        val reachability = reachability()
        assertTrue(reachability.offline.value)

        network.reconnect()
        runCurrent()
        assertFalse("nothing failed: the network alone decides", reachability.offline.value)
        advanceTimeBy(RECONNECT_DEBOUNCE_MILLIS * 10)
        assertEquals("nothing to correct, so no check is sent", 0, sessions.refreshes.get())
    }

    @Test
    fun aConnectivityFailureSetsItAndAnyAnswerOfTheServerClearsIt() = runTest {
        val reachability = reachability()
        assertFalse(reachability.offline.value)

        reachability.report(TandemError.Timeout)
        runCurrent()
        assertTrue("a failed server check sets it", reachability.offline.value)

        contact.record(ACCOUNT, TandemError.ServerError(502))
        runCurrent()
        assertFalse("an HTTP error is still an answer", reachability.offline.value)

        contact.record(ACCOUNT, TandemError.UnknownHost)
        runCurrent()
        assertTrue(reachability.offline.value)
        contact.record(ACCOUNT, null)
        runCurrent()
        assertFalse("a successful request (a sync, the outbox) clears it", reachability.offline.value)
    }

    @Test
    fun theSyncsRequestsReachTheOfflineState() = runTest {
        val reachability = reachability()
        val caller = RemoteCaller(guard, sources, contact)

        caller.call(remote, background = true) { throw UnknownHostException("offline") }
        runCurrent()
        assertTrue(reachability.offline.value)

        val answer = caller.call(remote, background = true) { "ok" }
        runCurrent()
        assertEquals(Outcome.Success("ok"), answer)
        assertFalse("a completed request clears every notice", reachability.offline.value)
    }

    @Test
    fun whenTheNetworkComesBackOneCheckClearsItAfterTheDebounce() = runTest {
        network.online.value = false
        val reachability = reachability()
        reachability.report(TandemError.NetworkUnavailable)
        runCurrent()

        network.reconnect()
        runCurrent()
        advanceTimeBy(RECONNECT_DEBOUNCE_MILLIS - 1)
        assertTrue("still offline until the check answers", reachability.offline.value)
        assertEquals(0, sessions.refreshes.get())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, sessions.refreshes.get())
        assertFalse(reachability.offline.value)

        advanceTimeBy(MIN_CHECK_SPACING_MILLIS * 10)
        assertEquals("one check, not a loop", 1, sessions.refreshes.get())
    }

    @Test
    fun aFailedReconnectCheckKeepsItOffline() = runTest {
        network.online.value = false
        val reachability = reachability()
        reachability.report(TandemError.NetworkUnavailable)
        sessions.refreshError = TandemError.Timeout

        network.reconnect()
        advanceTimeBy(RECONNECT_DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertEquals(1, sessions.refreshes.get())
        assertTrue(reachability.offline.value)
    }

    @Test
    fun aFlappingNetworkSendsNoBurstOfChecks() = runTest {
        network.online.value = false
        val reachability = reachability()
        reachability.report(TandemError.NetworkUnavailable)
        sessions.refreshError = TandemError.Timeout

        // On and off every second for a minute: never steady long enough for a check.
        repeat(60) {
            network.reconnect()
            advanceTimeBy(500)
            network.online.value = false
            advanceTimeBy(500)
        }
        assertEquals(0, sessions.refreshes.get())

        // Steady for 4 s, off for 1 s, for two minutes, and every check fails: at most one check
        // per MIN_CHECK_SPACING_MILLIS.
        repeat(24) {
            network.reconnect()
            advanceTimeBy(4_000)
            network.online.value = false
            advanceTimeBy(1_000)
        }
        val minutes = 2
        val allowed = (minutes * 60_000 / MIN_CHECK_SPACING_MILLIS).toInt() + 1
        assertTrue("${sessions.refreshes.get()} checks", sessions.refreshes.get() in 1..allowed)
        assertTrue(reachability.offline.value)
    }

    @Test
    fun whileTheGuardHoldsRequestsBackNothingIsSentAndTheNetworkDecides() = runTest {
        network.online.value = false
        val reachability = reachability()
        reachability.report(TandemError.NetworkUnavailable)
        guard.record(ACCOUNT, sources.tokenFingerprint(ACCOUNT), TandemError.Blocked)

        network.reconnect()
        advanceTimeBy(RECONNECT_DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertEquals("a blocked server gets no request", 0, sessions.refreshes.get())
        assertFalse(reachability.offline.value)
    }

    @Test
    fun demoModeIsNeverOffline() = runTest {
        sources.source.value = ActiveSource.Demo
        network.online.value = false
        val reachability = reachability()
        reachability.report(TandemError.Timeout)
        runCurrent()
        assertFalse(reachability.offline.value)
    }

    @Test
    fun anotherAccountsFailureDoesNotCount() = runTest {
        val reachability = reachability()
        contact.record("account-other", TandemError.Timeout)
        runCurrent()
        assertFalse(reachability.offline.value)

        reachability.report(TandemError.Timeout)
        runCurrent()
        assertTrue(reachability.offline.value)
        sources.source.value = remote("account-other")
        runCurrent()
        assertFalse("the new account's server has not failed", reachability.offline.value)
    }

    private companion object {
        const val ACCOUNT = "account-1"

        fun remote(id: String): ActiveSource.Remote = ActiveSource.Remote(
            ServerAccount(
                id = id,
                baseUrl = "https://tandoor.example.com",
                username = "cook",
                userId = 1,
                spaceId = 1,
                serverVersion = "2.6.13",
                addedAt = Instant.fromEpochMilliseconds(0),
            ),
            // Never called: these tests send no request.
            TandoorApiFactory(OkHttpClient()).create("https://tandoor.example.com/", { null }),
        )
    }
}
