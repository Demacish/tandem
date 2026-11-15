package casa.moller.tandem.core.data.security

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.settings.AppLockSettings
import casa.moller.tandem.core.model.settings.AppLockStatus
import casa.moller.tandem.core.model.settings.AppLockTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fas 5 (las lane, plan §5.5): Mer → Lås appen, for the whole process (docs/BYGGE.md "Lås appen").
 * Implemented in core:sync (`DefaultAppLock`, which follows the process lifecycle); the rules are
 * core:domain's `AppLockPolicy`.
 *
 * Who reads it:
 * - the app's lock screen ([status]; the content stays hidden while [isLocked]);
 * - the widgets and the quick settings tile ([settings]: a locked placeholder while the lock is on);
 * - App Functions ([unlockedWithinTimeout]: refuse to read or write while locked).
 *
 * Who never waits for it: the timers and the full-screen alarm, their notifications and actions,
 * WorkManager (sync, outbox, reminders), the reminder notifications and the watch.
 *
 * - **Threading:** main-safe.
 * - **Writing:** the setters fail with `DataProblem.SETTINGS_NOT_SAVED` when the settings file
 *   cannot be written; the old value stays.
 */
interface AppLock {
    /** The stored setting ([AppLockSettings] until changed: off, one minute). */
    val settings: Flow<AppLockSettings>

    /** Where the lock stands; [AppLockStatus.UNKNOWN] until the setting has been read at this process start. */
    val status: StateFlow<AppLockStatus>

    /**
     * True while the app's content must stay hidden: the lock is on and not unlocked, or the
     * setting is not read yet ([AppLockStatus.UNKNOWN] counts as locked).
     */
    val isLocked: StateFlow<Boolean>

    /**
     * For App Functions and other callers that act without the app in front: waits until the
     * setting is read, looks at the clock again (a phone asleep in the background does not run the
     * timeout's timer on time) and says whether the user's content may be read or written now:
     * the lock is off, or the user unlocked and the app has not been in the background longer
     * than the timeout since.
     */
    suspend fun unlockedWithinTimeout(): Boolean

    /** The user confirmed the phone's screen lock on the lock screen. */
    fun onUnlocked()

    /** Turns the lock on or off. Turning it on with the app open does not lock it. */
    suspend fun setEnabled(enabled: Boolean): Outcome<Unit>

    /** "Lås efter". */
    suspend fun setTimeout(timeout: AppLockTimeout): Outcome<Unit>
}
