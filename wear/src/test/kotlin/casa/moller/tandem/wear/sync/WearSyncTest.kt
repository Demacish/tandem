package casa.moller.tandem.wear.sync

import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.wear.FakeTandoor
import casa.moller.tandem.wear.InMemoryCredentialsStore
import casa.moller.tandem.wear.credentials
import casa.moller.tandem.wear.data.WearAccount
import casa.moller.tandem.wear.data.WearProblem
import casa.moller.tandem.wear.data.WearStateStore
import casa.moller.tandem.wear.data.WearSupermarket
import casa.moller.tandem.wear.entry
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fas 5 (wear lane), "Klart när" in CI: the watch checks off an item without a phone and without
 * network, and the server receives it when the watch has network; the watch's outbox and the
 * guard that keeps a revoked token, a 429 or a failing server from getting a burst of requests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearSyncTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val tandoor = FakeTandoor()
    private val pairing = InMemoryCredentialsStore(credentials())
    private var now = 1_000_000_000L

    private fun TestScope.paired(): Pair<WearSync, WearStateStore> {
        val store = WearStateStore(File(folder.root, "state.json"), UnconfinedTestDispatcher(testScheduler))
        val sync = WearSync(pairing, store, tandoor.factory) { now }
        return sync to store
    }

    private suspend fun WearStateStore.pairedWith(accountKey: String = "account-1") = update {
        it.copy(account = WearAccount("https://tandoor.example.com/", 42, Long.MAX_VALUE, accountKey))
    }

    @Test
    fun aCheckMadeOfflineReachesTheServerOnceTheWatchIsOnline() = runTest {
        val (sync, store) = paired()
        store.pairedWith()
        tandoor.entries += listOf(entry(1, "mjölk"), entry(2, "bröd"))
        assertEquals(WearSync.Result.Done, sync.sync())

        // The phone is off and the watch has no network: the tap stays on the watch.
        tandoor.offline = true
        sync.setChecked(listOf(1L), checked = true)
        assertEquals(WearSync.Result.Offline, sync.sync())
        assertEquals(mapOf(1L to true), store.load().pending)
        assertTrue(store.load().visibleEntries.first { it.id == 1L }.checked)
        assertEquals(WearProblem.OFFLINE, store.load().guard.problem)
        assertTrue(tandoor.bulkCalls.isEmpty())

        // Network again (WorkManager runs the job): one bulk call, the outbox is empty.
        tandoor.offline = false
        assertEquals(WearSync.Result.Done, sync.sync())
        assertEquals(listOf(listOf(1L) to true), tandoor.bulkCalls)
        assertTrue(tandoor.entries.first { it.id == 1L }.checked)
        assertTrue(store.load().pending.isEmpty())
        assertEquals(null, store.load().guard.problem)
    }

    @Test
    fun aMergedRowIsOneBulkCallAndTwoTapsThatCancelSendNothing() = runTest {
        val (sync, store) = paired()
        store.pairedWith()
        tandoor.entries += listOf(entry(1, "grädde", 3.0, "dl"), entry(2, "grädde", 2.0, "dl"), entry(3, "smör"))
        sync.sync()

        sync.setChecked(listOf(3L), true)
        sync.setChecked(listOf(3L), false)
        assertTrue(store.load().pending.isEmpty())

        sync.setChecked(listOf(1L, 2L), true)
        now += 1.minutes.inWholeMilliseconds
        sync.sync()

        assertEquals(listOf(listOf(1L, 2L) to true), tandoor.bulkCalls)
    }

    @Test
    fun theFirstRefusedTokenStopsEveryRequest() = runTest {
        val (sync, store) = paired()
        store.pairedWith()
        sync.sync()
        sync.setChecked(listOf(5L), true)
        tandoor.failNext += TandemError.Unauthorized(UnauthorizedSource.TANDOOR)

        assertEquals(WearSync.Result.SignedOut, sync.sync())
        val requests = tandoor.requests
        now += 3.hours.inWholeMilliseconds
        assertEquals(WearSync.Result.SignedOut, sync.sync(force = true))

        assertEquals("no second request with a refused token", requests, tandoor.requests)
        assertTrue(store.load().signedOut)
        assertEquals("the check waits for a new pairing", mapOf(5L to true), store.load().pending)
    }

    @Test
    fun aThrottledServerIsLeftAloneUntilRetryAfter() = runTest {
        val (sync, store) = paired()
        store.pairedWith()
        tandoor.failNext += TandemError.Throttled(10.minutes)

        val paused = sync.sync(force = true)

        assertEquals(WearSync.Result.Paused(now + 10.minutes.inWholeMilliseconds), paused)
        assertEquals(WearProblem.THROTTLED, store.load().guard.problem)
        val requests = tandoor.requests
        now += 9.minutes.inWholeMilliseconds
        assertEquals(paused, sync.sync(force = true))
        assertEquals(requests, tandoor.requests)
        now += 1.minutes.inWholeMilliseconds
        assertEquals(WearSync.Result.Done, sync.sync(force = true))
        assertEquals(null, store.load().guard.problem)
    }

    @Test
    fun serverErrorsDoubleThePauseUpToTwoHoursAndASuccessResetsIt() = runTest {
        val (sync, store) = paired()
        store.pairedWith()
        val pauses = mutableListOf<Long>()
        repeat(7) {
            tandoor.failNext += TandemError.ServerError(502)
            val result = sync.sync(force = true) as WearSync.Result.Paused
            pauses += result.untilMillis - now
            now = result.untilMillis
        }

        assertEquals(listOf(5L, 10L, 20L, 40L, 80L, 120L, 120L).map { it.minutes.inWholeMilliseconds }, pauses)
        assertEquals(WearProblem.SERVER, store.load().guard.problem)
        assertEquals(WearSync.Result.Done, sync.sync(force = true))
        assertEquals(0L, store.load().guard.pauseMillis)
    }

    @Test
    fun noNetworkIsNeverAPause() = runTest {
        val (sync, store) = paired()
        store.pairedWith()
        tandoor.offline = true

        assertEquals(WearSync.Result.Offline, sync.sync(force = true))
        tandoor.offline = false
        assertEquals(WearSync.Result.Done, sync.sync(force = true))
    }

    @Test
    fun theListIsReadAtMostEveryTwoMinutesUnlessAsked() = runTest {
        val (sync, store) = paired()
        store.pairedWith()

        sync.sync()
        assertEquals(WearSync.Result.Fresh, sync.sync())
        assertEquals(1, tandoor.entryReads)
        sync.sync(force = true)
        assertEquals(2, tandoor.entryReads)
        now += 2.minutes.inWholeMilliseconds
        sync.sync()
        assertEquals(3, tandoor.entryReads)
    }

    @Test
    fun theStoresAreReadEverySixHours() = runTest {
        val (sync, store) = paired()
        store.pairedWith()
        tandoor.supermarkets += WearSupermarket(7, "Ica", listOf(3, 1))

        sync.sync()
        now += 5.hours.inWholeMilliseconds
        sync.sync()
        assertEquals(1, tandoor.supermarketReads)
        now += 1.hours.inWholeMilliseconds
        sync.sync()

        assertEquals(2, tandoor.supermarketReads)
        assertEquals(listOf(WearSupermarket(7, "Ica", listOf(3, 1))), store.load().supermarkets)
    }

    @Test
    fun nothingIsSentWithoutAPairingOrForAnotherAccountsList() = runTest {
        val (sync, store) = paired()
        pairing.stored = null
        assertEquals(WearSync.Result.NotPaired, sync.sync())

        pairing.stored = credentials(accountKey = "account-2")
        store.pairedWith("account-1")
        assertEquals(WearSync.Result.NotPaired, sync.sync())
        assertEquals(0, tandoor.requests)
        assertFalse(store.load().signedOut)
    }
}
