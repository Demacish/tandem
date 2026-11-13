package casa.moller.tandem.wear.link

import casa.moller.tandem.core.watchlink.MirroredTimer
import casa.moller.tandem.core.watchlink.MirroredTimerState
import casa.moller.tandem.core.watchlink.PairError
import casa.moller.tandem.core.watchlink.PairReply
import casa.moller.tandem.core.watchlink.UnpairRequest
import casa.moller.tandem.core.watchlink.WatchCodec
import casa.moller.tandem.core.watchlink.WatchCredentials
import casa.moller.tandem.core.watchlink.WatchStatusReply
import casa.moller.tandem.wear.FakeTandoor
import casa.moller.tandem.wear.InMemoryCredentialsStore
import casa.moller.tandem.wear.credentials
import casa.moller.tandem.wear.data.GuardState
import casa.moller.tandem.wear.data.WearStateStore
import casa.moller.tandem.wear.entry
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fas 5 (wear lane): the watch's half of the token exchange, fed the phone's requests as the Data
 * Layer delivers them (bytes on a path), so a fake Data Layer is the whole transport.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchPairingTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val tandoor = FakeTandoor()
    private val stored = InMemoryCredentialsStore()
    private var paired = 0
    private var changed = 0
    private val timer = MirroredTimer(1, "Pasta", null, MirroredTimerState.RUNNING, endsAtEpochMillis = 5, durationMillis = 5)

    private fun TestScope.pairing(): Pair<WatchPairing, WearStateStore> {
        val store = WearStateStore(File(folder.root, "state.json"), UnconfinedTestDispatcher(testScheduler))
        val pairing = WatchPairing(stored, store, tandoor.factory, { 1_000L }, onPaired = { paired++ }, onChanged = { changed++ })
        return pairing to store
    }

    private fun request(credentials: WatchCredentials): ByteArray = WatchCodec.encode(WatchCredentials.serializer(), credentials)

    private fun reply(bytes: ByteArray): PairReply? = WatchCodec.decode(PairReply.serializer(), bytes)

    @Test
    fun aPairingStoresTheWatchTokenAndStartsASync() = runTest {
        val (pairing, store) = pairing()

        val answer = reply(pairing.pair(request(credentials())))

        assertEquals(PairReply(ok = true), answer)
        assertEquals(credentials(), stored.stored)
        val account = store.load().account
        assertEquals("account-1", account?.accountKey)
        assertEquals(42L, account?.tokenId)
        assertEquals("Anna", account?.label)
        assertFalse("the token never goes into the plain state file", File(folder.root, "state.json").readText().contains("tda_"))
        assertEquals(1, paired)
        assertEquals(1, changed)
    }

    @Test
    fun aBrokenOrUnsafePairingIsRefused() = runTest {
        val (pairing, store) = pairing()

        assertEquals(PairReply(false, PairError.BAD_PAYLOAD), reply(pairing.pair("{".encodeToByteArray())))
        val plainHttp = request(credentials(baseUrl = "http://tandoor.example.com/"))
        assertEquals(PairReply(false, PairError.BAD_PAYLOAD), reply(pairing.pair(plainHttp)))
        stored.failSave = true
        assertEquals(PairReply(false, PairError.STORAGE), reply(pairing.pair(request(credentials()))))

        assertNull(stored.stored)
        assertNull(store.load().account)
        assertEquals(0, paired)
    }

    @Test
    fun aRenewedTokenKeepsTheListAndAnotherAccountEmptiesIt() = runTest {
        val (pairing, store) = pairing()
        pairing.pair(request(credentials()))
        store.update { it.copy(entries = listOf(entry(1, "mjölk")), pending = mapOf(1L to true), signedOut = true, timers = listOf(timer)) }

        pairing.pair(request(credentials(tokenId = 43)))
        assertEquals(mapOf(1L to true), store.load().pending)
        assertEquals(1, store.load().entries.size)
        assertFalse("a new token lifts the log-in-again state", store.load().signedOut)
        assertEquals(43L, store.load().account?.tokenId)

        pairing.pair(request(credentials(accountKey = "account-2", tokenId = 44)))
        assertTrue(store.load().entries.isEmpty())
        assertTrue(store.load().pending.isEmpty())
        assertEquals("the phone's timers are not the account's", listOf(timer), store.load().timers)
    }

    @Test
    fun unpairingRevokesTheWatchTokenOnceAndWipesTheWatch() = runTest {
        val (pairing, store) = pairing()
        pairing.pair(request(credentials()))
        store.update { it.copy(entries = listOf(entry(1, "mjölk")), timers = listOf(timer)) }

        val answer = reply(pairing.unpair(WatchCodec.encode(UnpairRequest.serializer(), UnpairRequest(tokenId = 42, revoke = true))))

        assertEquals(PairReply(ok = true), answer)
        assertEquals(listOf(42L), tandoor.revoked)
        assertNull(stored.stored)
        assertNull(store.load().account)
        assertTrue(store.load().entries.isEmpty())
        assertEquals(listOf(timer), store.load().timers)
    }

    @Test
    fun unpairingSendsNothingWhenThePhoneAlreadyRevokedOrTheGuardHoldsBack() = runTest {
        val (pairing, store) = pairing()
        pairing.pair(request(credentials()))
        pairing.unpair(WatchCodec.encode(UnpairRequest.serializer(), UnpairRequest(tokenId = 42, revoke = false)))
        assertEquals(0, tandoor.requests)

        pairing.pair(request(credentials()))
        store.update { it.copy(guard = GuardState(pausedUntilMillis = 5_000L)) }
        pairing.unpair(WatchCodec.encode(UnpairRequest.serializer(), UnpairRequest(tokenId = 42, revoke = true)))
        assertEquals(0, tandoor.requests)
        assertNull(stored.stored)
    }

    @Test
    fun theStatusSaysWhatThePhonePageShows() = runTest {
        val (pairing, store) = pairing()
        assertEquals(WatchStatusReply(paired = false), WatchCodec.decode(WatchStatusReply.serializer(), pairing.status()))

        pairing.pair(request(credentials()))
        store.update { it.copy(pending = mapOf(1L to true, 2L to false), lastSyncMillis = 900L, signedOut = true) }

        assertEquals(
            WatchStatusReply(
                paired = true,
                tokenId = 42,
                accountKey = "account-1",
                signedOut = true,
                pending = 2,
                lastSyncEpochMillis = 900L,
            ),
            WatchCodec.decode(WatchStatusReply.serializer(), pairing.status()),
        )
    }
}
