package casa.moller.tandem.core.sync.security

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.settings.AppLockSettings
import casa.moller.tandem.core.model.settings.AppLockStatus
import casa.moller.tandem.core.model.settings.AppLockTimeout
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.sync.testing.FakeClock
import casa.moller.tandem.core.sync.testing.FakeSettingsStore
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fas 5 (las lane, plan §5.5): the process's lock on a fake clock: the cold start, the time in the
 * background (also when the phone sleeps and the timer is late), turning it on and off, and what
 * App Functions are told (docs/BYGGE.md "Lås appen").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAppLockTest {
    private val clock = FakeClock()
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private fun lock(settings: AppLockSettings): Pair<DefaultAppLock, FakeSettingsStore> {
        val store = FakeSettingsStore(AppSettings(appLock = settings))
        val lock = DefaultAppLock(store, clock, scope.backgroundScope)
        lock.followSettings()
        return lock to store
    }

    @Test
    fun theStatusIsUnknownAndLockedUntilTheSettingIsRead() = scope.runTest {
        val (lock, _) = lock(AppLockSettings())
        assertEquals(AppLockStatus.UNKNOWN, lock.status.value)
        assertTrue(lock.isLocked.value)
        runCurrent()
        assertEquals(AppLockStatus.OFF, lock.status.value)
        assertFalse(lock.isLocked.value)
        assertTrue(lock.unlockedWithinTimeout())
    }

    @Test
    fun aColdStartLocksAndTheUserUnlocks() = scope.runTest {
        val (lock, _) = lock(AppLockSettings(enabled = true))
        runCurrent()
        lock.onForeground()
        assertEquals(AppLockStatus.LOCKED, lock.status.value)
        assertFalse(lock.unlockedWithinTimeout())
        lock.onUnlocked()
        assertEquals(AppLockStatus.UNLOCKED, lock.status.value)
        assertTrue(lock.unlockedWithinTimeout())
    }

    @Test
    fun theTimerLocksInTheBackgroundSoTheAppComesBackLocked() = scope.runTest {
        val (lock, _) = lock(AppLockSettings(enabled = true, timeout = AppLockTimeout.FIVE_MINUTES))
        runCurrent()
        lock.onForeground()
        lock.onUnlocked()
        lock.onBackground()
        clock.advance(4.minutes)
        advanceTimeBy(4.minutes)
        assertEquals(AppLockStatus.UNLOCKED, lock.status.value)
        assertTrue(lock.unlockedWithinTimeout())
        clock.advance(1.minutes + 1.seconds)
        advanceTimeBy(1.minutes + 1.seconds)
        assertEquals(AppLockStatus.LOCKED, lock.status.value)
        assertTrue(lock.isLocked.value)
    }

    @Test
    fun aSleepingPhoneThatRunsTheTimerLateIsCaughtByTheClock() = scope.runTest {
        val (lock, _) = lock(AppLockSettings(enabled = true, timeout = AppLockTimeout.ONE_MINUTE))
        runCurrent()
        lock.onForeground()
        lock.onUnlocked()
        lock.onBackground()
        // Deep sleep: the clock moved two minutes, the coroutine timer did not fire yet.
        clock.advance(2.minutes)
        assertEquals(AppLockStatus.UNLOCKED, lock.status.value)
        // An App Function asking now is refused...
        assertFalse(lock.unlockedWithinTimeout())
        // ...and the app comes back locked.
        lock.onForeground()
        assertEquals(AppLockStatus.LOCKED, lock.status.value)
    }

    @Test
    fun comingBackWithinTheTimeoutStopsTheTimer() = scope.runTest {
        val (lock, _) = lock(AppLockSettings(enabled = true, timeout = AppLockTimeout.ONE_MINUTE))
        runCurrent()
        lock.onForeground()
        lock.onUnlocked()
        lock.onBackground()
        clock.advance(30.seconds)
        advanceTimeBy(30.seconds)
        lock.onForeground()
        // Cook mode stays open for an hour: the old timer must not lock it.
        clock.advance(60.minutes)
        advanceTimeBy(60.minutes)
        assertEquals(AppLockStatus.UNLOCKED, lock.status.value)
    }

    @Test
    fun immediatelyLocksAsSoonAsTheAppLeavesTheScreen() = scope.runTest {
        val (lock, _) = lock(AppLockSettings(enabled = true, timeout = AppLockTimeout.IMMEDIATELY))
        runCurrent()
        lock.onForeground()
        lock.onUnlocked()
        lock.onBackground()
        assertEquals(AppLockStatus.LOCKED, lock.status.value)
    }

    @Test
    fun turningTheLockOnInTheAppKeepsItOpenAndOffUnlocks() = scope.runTest {
        val (lock, store) = lock(AppLockSettings())
        runCurrent()
        lock.onForeground()
        assertEquals(Outcome.Success(Unit), lock.setEnabled(true))
        assertEquals(Outcome.Success(Unit), lock.setTimeout(AppLockTimeout.FIFTEEN_MINUTES))
        runCurrent()
        assertEquals(AppLockSettings(enabled = true, timeout = AppLockTimeout.FIFTEEN_MINUTES), store.state.value.appLock)
        assertEquals(AppLockStatus.UNLOCKED, lock.status.value)

        lock.onBackground()
        clock.advance(16.minutes)
        lock.onForeground()
        assertEquals(AppLockStatus.LOCKED, lock.status.value)
        lock.setEnabled(false)
        runCurrent()
        assertEquals(AppLockStatus.OFF, lock.status.value)
        assertFalse(lock.isLocked.value)
    }
}
