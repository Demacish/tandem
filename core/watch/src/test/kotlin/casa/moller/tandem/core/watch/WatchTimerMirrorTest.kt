package casa.moller.tandem.core.watch

import casa.moller.tandem.core.model.timer.TimerStartResult
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.testing.data.FakeTimerController
import casa.moller.tandem.core.watchlink.MirroredTimerState
import casa.moller.tandem.core.watchlink.TimerAction
import casa.moller.tandem.core.watchlink.TimerActionKind
import casa.moller.tandem.core.watchlink.TimerMirror
import casa.moller.tandem.core.watchlink.WatchCodec
import casa.moller.tandem.core.watchlink.WatchLink
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fas 5 (wear lane): the phone's half of the timer mirror with fakes (a fake Data Layer, the fake
 * TimerController): every change goes to the paired watch, nothing without a pairing, and "Stoppa"
 * from the watch silences the phone's alarm.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchTimerMirrorTest {
    private val timers = FakeTimerController()
    private val layer = FakeWatchLayer()
    private val store = InMemoryPairingStore()
    private val mirror = WatchTimerMirror(timers, layer, store, timers.clock)

    private fun sent(): List<TimerMirror> = layer.messages
        .filter { it.first == WatchLink.PATH_TIMERS }
        .mapNotNull { WatchCodec.decode(TimerMirror.serializer(), it.second) }

    private suspend fun pair() = store.save(
        StoredWatchPairing(
            nodeId = "node-1",
            watchName = "Galaxy Watch",
            accountId = "account-1",
            tokenId = 1,
            expiresEpochMillis = Long.MAX_VALUE,
            pairedAtEpochMillis = 0,
        ),
    )

    @Test
    fun everyChangeGoesToThePairedWatch() = runTest {
        mirror.start(backgroundScope)
        timers.start("Pasta", 10.minutes, recipeName = "Krämig pasta")
        advanceTimeBy(1_000)
        assertTrue("nothing without a pairing", sent().isEmpty())

        pair()
        advanceTimeBy(1_000)
        val running = sent().last().timers.single()
        assertEquals("Pasta", running.label)
        assertEquals("Krämig pasta", running.recipeName)
        assertEquals(MirroredTimerState.RUNNING, running.state)
        assertEquals((timers.clock.now() + 10.minutes).toEpochMilliseconds(), running.endsAtEpochMillis)

        timers.advanceTimeBy(10.minutes)
        advanceTimeBy(1_000)
        assertEquals(MirroredTimerState.RINGING, sent().last().timers.single().state)
    }

    @Test
    fun stopOnTheWatchSilencesThePhone() = runTest {
        val id = (timers.start("Ugnen", 1.minutes) as TimerStartResult.Started).timer.id
        timers.advanceTimeBy(1.minutes)
        runCurrent()

        mirror.act(WatchCodec.encode(TimerAction.serializer(), TimerAction(id, TimerActionKind.STOP_RINGING)))
        assertEquals(TimerState.FINISHED, timers.timers.value.single().state)

        mirror.act(WatchCodec.encode(TimerAction.serializer(), TimerAction(id, TimerActionKind.CANCEL)))
        assertTrue(timers.timers.value.isEmpty())
        assertTrue("the watch asks when its app opens", mirror.mirror().timers.isEmpty())
    }
}
