package casa.moller.tandem.wear.ui

import app.cash.turbine.test
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.watchlink.MirroredTimer
import casa.moller.tandem.core.watchlink.MirroredTimerState
import casa.moller.tandem.core.watchlink.TimerAction
import casa.moller.tandem.core.watchlink.TimerActionKind
import casa.moller.tandem.wear.FakePhoneLink
import casa.moller.tandem.wear.FakeScheduler
import casa.moller.tandem.wear.FakeTandoor
import casa.moller.tandem.wear.InMemoryCredentialsStore
import casa.moller.tandem.wear.RecordingNotifier
import casa.moller.tandem.wear.credentials
import casa.moller.tandem.wear.data.WearAccount
import casa.moller.tandem.wear.data.WearStateStore
import casa.moller.tandem.wear.data.WearSupermarket
import casa.moller.tandem.wear.entry
import casa.moller.tandem.wear.sync.WearSync
import casa.moller.tandem.wear.timer.WearTimers
import java.io.File
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Fas 5 (wear lane): the watch screen's state and what a tap does. */
@OptIn(ExperimentalCoroutinesApi::class)
class WearViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val tandoor = FakeTandoor()
    private val scheduler = FakeScheduler()
    private val phone = FakePhoneLink()
    private var allowed = false

    private fun model(store: WearStateStore): WearViewModel {
        val sync = WearSync(InMemoryCredentialsStore(credentials()), store, tandoor.factory) { 1_000L }
        val timers = WearTimers(store, phone, RecordingNotifier()) {}
        return WearViewModel(store, sync, scheduler, timers, { 1_000L }, { allowed }, { Locale.ENGLISH })
    }

    private fun store() = WearStateStore(File(folder.root, "state.json"), main.dispatcher)

    @Test
    fun aTapGoesToTheOutboxAndAsksForASync() = runTest(main.dispatcher) {
        val store = store()
        store.update {
            it.copy(
                account = WearAccount("https://tandoor.example.com/", 42, Long.MAX_VALUE, "account-1"),
                entries = listOf(entry(1, "milk")),
            )
        }
        val model = model(store)

        model.state.test {
            var state = awaitItem()
            while (state.mode != WearMode.READY) state = awaitItem()
            val milk = state.list.aisles.single().rows.single()

            model.onAction(WearAction.Toggle(milk))

            assertEquals(mapOf(1L to true), store.load().pending)
            assertEquals(listOf(false), scheduler.now)
            val after = awaitItem()
            assertEquals(1, after.pending)
            assertTrue(after.list.checked.single().pending)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("the tap itself sends nothing", 0, tandoor.requests)
    }

    @Test
    fun theModesFollowThePairing() = runTest(main.dispatcher) {
        val store = store()
        val model = model(store)

        model.state.test {
            var state = awaitItem()
            while (state.mode == WearMode.LOADING) state = awaitItem()
            assertEquals(WearMode.NOT_PAIRED, state.mode)
            store.update { it.copy(account = WearAccount("https://tandoor.example.com/", 42, Long.MAX_VALUE, "account-1", "Anna")) }
            assertEquals(WearMode.READY, awaitItem().mode)
            store.update { it.copy(signedOut = true) }
            assertEquals(WearMode.SIGNED_OUT, awaitItem().mode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun refreshNextStoreAndTheTimersAlarm() = runTest(main.dispatcher) {
        val store = store()
        val ringing = MirroredTimer(3, "Pasta", null, MirroredTimerState.RINGING, endsAtEpochMillis = 900L, durationMillis = 1L)
        store.update {
            it.copy(
                account = WearAccount("https://tandoor.example.com/", 42, Long.MAX_VALUE, "account-1"),
                supermarkets = listOf(WearSupermarket(1, "Ica"), WearSupermarket(2, "Coop")),
                timers = listOf(ringing),
            )
        }
        val model = model(store)

        model.onAction(WearAction.Refresh)
        model.onAction(WearAction.NextStore)
        model.onAction(WearAction.StopTimer(3))

        assertEquals(listOf(true), scheduler.now)
        assertEquals(2L, store.load().supermarketId)
        assertEquals(listOf(TimerAction(3, TimerActionKind.STOP_RINGING)), phone.actions)
        model.state.test {
            var state = awaitItem()
            while (state.mode != WearMode.READY) state = awaitItem()
            assertEquals("Coop", state.storeName)
            assertTrue("notifications are asked next to the timers", state.askNotifications)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
