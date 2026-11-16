package casa.moller.tandem.core.sync.security

import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.settings.AppLockSettings
import casa.moller.tandem.core.model.settings.AppLockStatus
import casa.moller.tandem.core.model.settings.AppLockTimeout
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.sync.testing.FakeClock
import casa.moller.tandem.core.sync.testing.FakeSettingsStore
import java.io.IOException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
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

    private fun create(settings: AppLockSettings): Pair<DefaultAppLock, FakeSettingsStore> {
        val store = FakeSettingsStore(AppSettings(appLock = settings))
        val lock = DefaultAppLock(store, clock, scope.backgroundScope)
        lock.followSettings()
        return lock to store
    }

    @Test
    fun theStatusIsUnknownAndLockedUntilTheSettingIsRead() = scope.runTest {
        val (lock, _) = create(AppLockSettings())
        assertEquals(AppLockStatus.UNKNOWN, lock.status.value)
        assertTrue(lock.isLocked.value)
        runCurrent()
        assertEquals(AppLockStatus.OFF, lock.status.value)
        assertFalse(lock.isLocked.value)
        assertTrue(lock.unlockedWithinTimeout())
    }

    @Test
    fun aColdStartLocksAndTheUserUnlocks() = scope.runTest {
        val (lock, _) = create(AppLockSettings(enabled = true))
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
        val (lock, _) = create(AppLockSettings(enabled = true, timeout = AppLockTimeout.FIVE_MINUTES))
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
        val (lock, _) = create(AppLockSettings(enabled = true, timeout = AppLockTimeout.ONE_MINUTE))
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
        val (lock, _) = create(AppLockSettings(enabled = true, timeout = AppLockTimeout.ONE_MINUTE))
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
        val (lock, _) = create(AppLockSettings(enabled = true, timeout = AppLockTimeout.IMMEDIATELY))
        runCurrent()
        lock.onForeground()
        lock.onUnlocked()
        lock.onBackground()
        assertEquals(AppLockStatus.LOCKED, lock.status.value)
    }

    @Test
    fun turningTheLockOnInTheAppKeepsItOpenAndOffUnlocks() = scope.runTest {
        val (lock, store) = create(AppLockSettings())
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

    // --- fix-09: a read error must not open the lock ---

    @Test
    fun aReadErrorsDefaultsFollowedByTheStoredOnKeepsTheAppLocked() = scope.runTest {
        // A settings source that gives the defaults (off) for a read error, then the stored "on".
        val (lock, store) = create(AppLockSettings())
        runCurrent()
        lock.onForeground()
        store.state.value = AppSettings(appLock = AppLockSettings(enabled = true))
        runCurrent()
        assertEquals(AppLockStatus.LOCKED, lock.status.value)
        assertTrue(lock.isLocked.value)
        assertFalse(lock.unlockedWithinTimeout())
        lock.onUnlocked()
        assertEquals(AppLockStatus.UNLOCKED, lock.status.value)
    }

    @Test
    fun theLockFollowsTheStoredSettingsNotTheReadErrorsDefaults() = scope.runTest {
        // settings gives the defaults while the file cannot be read; storedSettings waits for the real value.
        val store = ReadErrorSettingsStore()
        val lock = DefaultAppLock(store, clock, scope.backgroundScope)
        lock.followSettings()
        lock.onForeground()
        runCurrent()
        assertEquals(AppLockStatus.UNKNOWN, lock.status.value)
        assertTrue(lock.isLocked.value)
        store.stored.value = AppSettings(appLock = AppLockSettings(enabled = true))
        runCurrent()
        assertEquals(AppLockStatus.LOCKED, lock.status.value)
        assertFalse(lock.unlockedWithinTimeout())
    }

    @Test
    fun aFailedTurnOnLeavesNoMarkForALaterOn() = scope.runTest {
        val store = FailingWriteSettingsStore()
        val lock = DefaultAppLock(store, clock, scope.backgroundScope)
        lock.followSettings()
        runCurrent()
        lock.onForeground()
        assertTrue(lock.setEnabled(true) is Outcome.Failure)
        store.state.value = AppSettings(appLock = AppLockSettings(enabled = true))
        runCurrent()
        assertEquals(AppLockStatus.LOCKED, lock.status.value)
    }

    /** [settings] is the defaults (a read error), [storedSettings] has nothing until [stored] is set. */
    private class ReadErrorSettingsStore : SettingsStore {
        val stored = MutableStateFlow<AppSettings?>(null)

        override val settings: Flow<AppSettings> = MutableStateFlow(AppSettings.Default)

        override val storedSettings: Flow<AppSettings> = stored.filterNotNull()

        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            stored.value = transform(stored.value ?: AppSettings.Default)
        }
    }

    /** Reads work, writes fail with an I/O error. */
    private class FailingWriteSettingsStore : SettingsStore {
        val state = MutableStateFlow(AppSettings.Default)

        override val settings: Flow<AppSettings> = state

        override suspend fun update(transform: (AppSettings) -> AppSettings): Unit = throw IOException("test: not written")
    }
}
