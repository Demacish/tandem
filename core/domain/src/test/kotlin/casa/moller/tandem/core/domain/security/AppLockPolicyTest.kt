package casa.moller.tandem.core.domain.security

import casa.moller.tandem.core.model.settings.AppLockSettings
import casa.moller.tandem.core.model.settings.AppLockStatus
import casa.moller.tandem.core.model.settings.AppLockTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** Fas 5 (las lane, plan §5.5): the lock's timeouts, the cold start and the surfaces that never wait for it, on a fake clock. */
class AppLockPolicyTest {
    private var now = 1_000_000L

    private fun on(timeout: AppLockTimeout = AppLockTimeout.ONE_MINUTE) = AppLockSettings(enabled = true, timeout = timeout)

    /** A process that started, read [settings] and was unlocked by the user with the app in front. */
    private fun unlockedInFront(settings: AppLockSettings = on()): AppLockState {
        val read = AppLockPolicy.withSettings(AppLockPolicy.toForeground(AppLockPolicy.coldStart(), now), settings)
        return AppLockPolicy.unlocked(read)
    }

    @Test
    fun theStatusIsUnknownUntilTheSettingIsRead() {
        assertEquals(AppLockStatus.UNKNOWN, AppLockPolicy.coldStart().status)
        assertTrue(AppLockPolicy.blocks(AppSurface.APP, AppLockStatus.UNKNOWN))
        assertTrue(AppLockPolicy.blocks(AppSurface.APP_FUNCTION, AppLockStatus.UNKNOWN))
    }

    @Test
    fun aColdStartLocksWhenTheLockIsOn() {
        val state = AppLockPolicy.withSettings(AppLockPolicy.coldStart(), on(AppLockTimeout.FIFTEEN_MINUTES))
        assertEquals(AppLockStatus.LOCKED, state.status)
        // Also when the process started in the background (a sync, an alarm) and the app is opened later.
        val opened = AppLockPolicy.toForeground(state, now + 1)
        assertEquals(AppLockStatus.LOCKED, opened.status)
        assertEquals(AppLockStatus.UNLOCKED, AppLockPolicy.unlocked(opened).status)
    }

    @Test
    fun aColdStartWithTheLockOffShowsTheApp() {
        val state = AppLockPolicy.withSettings(AppLockPolicy.coldStart(), AppLockSettings())
        assertEquals(AppLockStatus.OFF, state.status)
        assertFalse(AppLockPolicy.blocks(AppSurface.APP, state.status))
    }

    @ParameterizedTest
    @EnumSource(AppLockTimeout::class)
    fun theAppLocksOnlyAfterTheTimeoutInTheBackground(timeout: AppLockTimeout) {
        val background = AppLockPolicy.toBackground(unlockedInFront(on(timeout)), now)
        if (timeout == AppLockTimeout.IMMEDIATELY) {
            assertEquals(AppLockStatus.LOCKED, background.status)
            return
        }
        val justBefore = AppLockPolicy.toForeground(background, now + timeout.millis - 1)
        assertEquals(AppLockStatus.UNLOCKED, justBefore.status)
        val atTimeout = AppLockPolicy.toForeground(background, now + timeout.millis)
        assertEquals(AppLockStatus.LOCKED, atTimeout.status)
    }

    @Test
    fun theTimeoutDefaultsToOneMinute() {
        assertEquals(AppLockTimeout.ONE_MINUTE, AppLockSettings().timeout)
        assertFalse(AppLockSettings().enabled)
        assertEquals(60_000L, AppLockTimeout.DEFAULT.millis)
    }

    @Test
    fun aTickInTheBackgroundLocksWithoutComingBack() {
        val background = AppLockPolicy.toBackground(unlockedInFront(on(AppLockTimeout.FIVE_MINUTES)), now)
        assertEquals(AppLockStatus.UNLOCKED, AppLockPolicy.tick(background, now + 60_000).status)
        assertEquals(AppLockStatus.LOCKED, AppLockPolicy.tick(background, now + 300_000).status)
    }

    @Test
    fun theAppInFrontNeverLocksHoweverLongItIsOpen() {
        // Cook mode or the kitchen display with the screen kept on: no background, no lock.
        val front = unlockedInFront(on(AppLockTimeout.IMMEDIATELY))
        assertEquals(AppLockStatus.UNLOCKED, AppLockPolicy.tick(front, now + 3 * 60 * 60 * 1000L).status)
        assertEquals(AppLockStatus.UNLOCKED, AppLockPolicy.toForeground(front, now + 3 * 60 * 60 * 1000L).status)
    }

    @Test
    fun theBackgroundTimeStartsAgainAtEveryTrip() {
        var state = AppLockPolicy.toBackground(unlockedInFront(), now)
        state = AppLockPolicy.toForeground(state, now + 50_000)
        state = AppLockPolicy.toBackground(state, now + 100_000)
        // 50 s + 50 s in two trips: each shorter than a minute.
        assertEquals(AppLockStatus.UNLOCKED, AppLockPolicy.toForeground(state, now + 150_000).status)
    }

    @Test
    fun aSecondBackgroundEventKeepsTheFirstTime() {
        var state = AppLockPolicy.toBackground(unlockedInFront(), now)
        state = AppLockPolicy.toBackground(state, now + 50_000)
        assertEquals(AppLockStatus.LOCKED, AppLockPolicy.toForeground(state, now + 60_000).status)
    }

    @Test
    fun turningTheLockOnInTheAppDoesNotLockIt() {
        val off = AppLockPolicy.toForeground(AppLockPolicy.withSettings(AppLockPolicy.coldStart(), AppLockSettings()), now)
        val on = AppLockPolicy.withSettings(off, on())
        assertEquals(AppLockStatus.UNLOCKED, on.status)
        // ...but leaving the app afterwards does.
        val background = AppLockPolicy.toBackground(on, now)
        assertEquals(AppLockStatus.LOCKED, AppLockPolicy.toForeground(background, now + 61_000).status)
    }

    @Test
    fun turningTheLockOffUnlocksAndChangingTheTimeoutKeepsTheLock() {
        val locked = AppLockPolicy.withSettings(AppLockPolicy.coldStart(), on())
        assertEquals(AppLockStatus.LOCKED, AppLockPolicy.withSettings(locked, on(AppLockTimeout.FIFTEEN_MINUTES)).status)
        val off = AppLockPolicy.withSettings(locked, AppLockSettings(enabled = false))
        assertEquals(AppLockStatus.OFF, off.status)
        assertFalse(off.locked)
    }

    @Test
    fun aShorterTimeoutChosenWhileAwayAppliesWhenComingBack() {
        val background = AppLockPolicy.toBackground(unlockedInFront(on(AppLockTimeout.FIFTEEN_MINUTES)), now)
        val shorter = AppLockPolicy.withSettings(background, on(AppLockTimeout.ONE_MINUTE))
        assertEquals(AppLockStatus.LOCKED, AppLockPolicy.toForeground(shorter, now + 120_000).status)
    }

    @Test
    fun unlockingWhileTheSystemPromptCoversTheAppIsNotUndoneByTheReturn() {
        // The screen lock is asked in a system window; whatever order the events come in, the app ends unlocked.
        val read = AppLockPolicy.withSettings(AppLockPolicy.coldStart(), on(AppLockTimeout.IMMEDIATELY))
        val locked = AppLockPolicy.toForeground(read, now)
        val covered = AppLockPolicy.toBackground(locked, now + 1_000)
        val unlocked = AppLockPolicy.unlocked(covered)
        assertEquals(AppLockStatus.UNLOCKED, AppLockPolicy.toForeground(unlocked, now + 20_000).status)
        val returnedFirst = AppLockPolicy.unlocked(AppLockPolicy.toForeground(covered, now + 20_000))
        assertEquals(AppLockStatus.UNLOCKED, returnedFirst.status)
    }

    @Test
    fun aClockThatWentBackwardsLocks() {
        val background = AppLockPolicy.toBackground(unlockedInFront(on(AppLockTimeout.FIFTEEN_MINUTES)), now)
        assertEquals(AppLockStatus.LOCKED, AppLockPolicy.toForeground(background, now - 10).status)
    }

    @ParameterizedTest
    @EnumSource(
        value = AppSurface::class,
        names = ["ALARM_SCREEN", "TIMER_NOTIFICATION", "BACKGROUND_WORK", "REMINDER_NOTIFICATION", "WATCH"],
    )
    fun timersAlarmsWorkRemindersAndTheWatchNeverWait(surface: AppSurface) {
        AppLockStatus.entries.forEach { status -> assertFalse(AppLockPolicy.blocks(surface, status), "$surface at $status") }
        assertTrue(AppLockPolicy.showsContent(surface, on()))
    }

    @ParameterizedTest
    @EnumSource(value = AppSurface::class, names = ["APP", "APP_FUNCTION"])
    fun theAppAndAppFunctionsWaitForTheUnlock(surface: AppSurface) {
        assertTrue(AppLockPolicy.blocks(surface, AppLockStatus.LOCKED))
        assertTrue(AppLockPolicy.blocks(surface, AppLockStatus.UNKNOWN))
        assertFalse(AppLockPolicy.blocks(surface, AppLockStatus.UNLOCKED))
        assertFalse(AppLockPolicy.blocks(surface, AppLockStatus.OFF))
    }

    @ParameterizedTest
    @EnumSource(value = AppSurface::class, names = ["WIDGET", "QUICK_SETTINGS_TILE"])
    fun widgetsAndTheTileHideTheContentWheneverTheLockIsOn(surface: AppSurface) {
        assertFalse(AppLockPolicy.showsContent(surface, on()))
        assertTrue(AppLockPolicy.showsContent(surface, AppLockSettings()))
        // Before the setting is read: the content (a widget redrawn later follows the setting).
        assertTrue(AppLockPolicy.showsContent(surface, null))
        AppLockStatus.entries.forEach { status -> assertFalse(AppLockPolicy.blocks(surface, status)) }
    }
}
