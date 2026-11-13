package casa.moller.tandem.wear.timer

import casa.moller.tandem.core.watchlink.MirroredTimer
import casa.moller.tandem.core.watchlink.MirroredTimerState
import casa.moller.tandem.core.watchlink.TimerAction
import casa.moller.tandem.core.watchlink.TimerActionKind
import casa.moller.tandem.core.watchlink.TimerMirror
import casa.moller.tandem.core.watchlink.WatchCodec
import casa.moller.tandem.wear.FakePhoneLink
import casa.moller.tandem.wear.RecordingNotifier
import casa.moller.tandem.wear.data.WearStateStore
import java.io.File
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
 * Fas 5 (wear lane): the phone's timers mirrored on the watch with a fake Data Layer (the phone's
 * messages as bytes, a fake phone for the actions). Stopping on either side stops both.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearTimersTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val phone = FakePhoneLink()
    private val notifier = RecordingNotifier()
    private var changes = 0
    private val pasta =
        MirroredTimer(1, "Pasta", "Krämig pasta", MirroredTimerState.RUNNING, endsAtEpochMillis = 600_000, durationMillis = 600_000)
    private val oven = MirroredTimer(2, "Ugnen", null, MirroredTimerState.RINGING, endsAtEpochMillis = 500_000, durationMillis = 300_000)

    private fun TestScope.timers(): Pair<WearTimers, WearStateStore> {
        val store = WearStateStore(File(folder.root, "state.json"), UnconfinedTestDispatcher(testScheduler))
        return WearTimers(store, phone, notifier) { changes++ } to store
    }

    private fun message(sentAt: Long, vararg timers: MirroredTimer) =
        WatchCodec.encode(TimerMirror.serializer(), TimerMirror(sentAtEpochMillis = sentAt, timers = timers.toList()))

    @Test
    fun thePhonesTimersBecomeTheWatchsNotifications() = runTest {
        val (timers, store) = timers()

        timers.onMirror(message(10, pasta, oven))

        assertEquals(listOf(pasta, oven), store.load().timers)
        assertEquals(listOf(pasta, oven), notifier.shown.last())
        assertEquals(1, changes)
    }

    @Test
    fun anOlderMessageNeverReplacesANewerOne() = runTest {
        val (timers, store) = timers()
        timers.onMirror(message(20, pasta))

        timers.onMirror(message(10, pasta, oven))
        timers.onMirror("broken".encodeToByteArray())

        assertEquals(listOf(pasta), store.load().timers)
        assertEquals(1, notifier.shown.size)
    }

    @Test
    fun stoppingTheAlarmOnTheWatchStopsItOnThePhone() = runTest {
        val (timers, store) = timers()
        timers.onMirror(message(10, pasta, oven))

        assertTrue(timers.act(2, TimerActionKind.STOP_RINGING))

        assertEquals(listOf(TimerAction(2, TimerActionKind.STOP_RINGING)), phone.actions)
        assertEquals(MirroredTimerState.FINISHED, store.load().timers.first { it.id == 2L }.state)
        assertEquals(listOf(pasta, oven.copy(state = MirroredTimerState.FINISHED)), notifier.shown.last())

        // The phone confirms (or stopped it itself first): its next picture wins.
        timers.onMirror(message(11, pasta))
        assertEquals(listOf(pasta), notifier.shown.last())
    }

    @Test
    fun cancellingRemovesTheTimerAndAMissingPhoneIsReported() = runTest {
        val (timers, store) = timers()
        timers.onMirror(message(10, pasta))
        phone.reachable = false

        assertFalse(timers.act(1, TimerActionKind.CANCEL))
        assertTrue(store.load().timers.isEmpty())
    }

    @Test
    fun openingTheAppAsksThePhone() = runTest {
        val (timers, store) = timers()
        phone.mirror = TimerMirror(sentAtEpochMillis = 30, timers = listOf(oven))

        assertTrue(timers.refresh())
        assertEquals(listOf(oven), store.load().timers)

        phone.reachable = false
        assertFalse(timers.refresh())
    }

    @Test
    fun theCountdownNeverShowsZeroBeforeTheAlarm() {
        assertEquals("10:00", TimerText.clock(600_000))
        assertEquals("0:01", TimerText.clock(1))
        assertEquals("1:02:03", TimerText.clock(3_723_000))
        assertEquals(100_000L, TimerText.remainingMillis(pasta, 500_000))
        assertEquals(0L, TimerText.remainingMillis(pasta, 700_000))
        assertEquals(0L, TimerText.remainingMillis(oven, 0))
        val paused = pasta.copy(state = MirroredTimerState.PAUSED, endsAtEpochMillis = null, remainingMillis = 42_000)
        assertEquals(42_000L, TimerText.remainingMillis(paused, 999_999))
    }
}
