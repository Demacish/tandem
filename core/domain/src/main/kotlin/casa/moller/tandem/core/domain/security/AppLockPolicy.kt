package casa.moller.tandem.core.domain.security

import casa.moller.tandem.core.model.settings.AppLockSettings
import casa.moller.tandem.core.model.settings.AppLockStatus

/**
 * Fas 5 (las lane, plan §5.5): the app lock as pure state (docs/BYGGE.md "Lås appen").
 *
 * @property settings the stored setting, null until it has been read at this process start.
 * @property locked the user has not unlocked since the last lock (a cold start begins locked).
 * @property inForeground some screen of the app is visible (the process lifecycle's ON_START).
 * @property backgroundSinceMillis when the app went to the background, on a monotonic clock that
 *   also runs in deep sleep (`SystemClock.elapsedRealtime`), so changing the phone's time does not
 *   move it; null in the foreground.
 */
data class AppLockState(
    val settings: AppLockSettings? = null,
    val locked: Boolean = true,
    val inForeground: Boolean = false,
    val backgroundSinceMillis: Long? = null,
) {
    /** Where the lock stands (call [AppLockPolicy.tick] first for a time that has passed). */
    val status: AppLockStatus
        get() = when {
            settings == null -> AppLockStatus.UNKNOWN
            !settings.enabled -> AppLockStatus.OFF
            locked -> AppLockStatus.LOCKED
            else -> AppLockStatus.UNLOCKED
        }
}

/**
 * The lock's rules, one function per event:
 *
 * - **Cold start:** [coldStart] is locked, so a lock that is on asks for the screen lock at every
 *   process start, also when the process was started in the background (a sync, an alarm).
 * - **Background:** the time in [AppLockSettings.timeout] only runs while no screen of the app is
 *   visible ([toBackground] → [tick] → [toForeground]); an app in front (cook mode, the kitchen
 *   display with the screen kept on) never locks. "Direkt" locks as soon as the app is in the
 *   background.
 * - **Settings:** turning the lock on in the app does not lock it (the user has just confirmed the
 *   screen lock); turning it off unlocks. The lock turning on any other way (the settings file
 *   readable again after a read error, a restored backup) locks: only the caller knows that the
 *   user switched it on here, so it says so ([withSettings]' `enabledInApp`), the policy never
 *   guesses it from an off-to-on change (fix-09).
 * - **Surfaces:** [blocks] and [showsContent] say what each way into the app does while the lock
 *   is on; the alarm, timer notifications, background work, reminders and the watch never wait for it.
 */
object AppLockPolicy {
    /** A new process: locked until the settings say the lock is off or the user unlocks. */
    fun coldStart(): AppLockState = AppLockState()

    /**
     * The stored setting was read or changed. [enabledInApp] is true only when this change is the
     * user switching the lock on in this process (Mer → Lås appen, after confirming the screen lock).
     */
    fun withSettings(state: AppLockState, settings: AppLockSettings, enabledInApp: Boolean = false): AppLockState {
        val previous = state.settings
        return when {
            !settings.enabled -> state.copy(settings = settings, locked = false)

            // The first read of this process keeps the cold start's lock.
            previous == null -> state.copy(settings = settings)

            // Turned on: unlocked only when the user just did it in the app (and confirmed the
            // screen lock); turned on any other way it locks, so nothing opens without the screen lock.
            !previous.enabled -> state.copy(settings = settings, locked = !enabledInApp)

            else -> state.copy(settings = settings)
        }
    }

    /** No screen of the app is visible any more (ON_STOP of the process lifecycle, the screen turned off). */
    fun toBackground(state: AppLockState, nowMillis: Long): AppLockState =
        tick(state.copy(inForeground = false, backgroundSinceMillis = state.backgroundSinceMillis ?: nowMillis), nowMillis)

    /** A screen of the app is visible again (ON_START): locked when the background lasted at least the timeout. */
    fun toForeground(state: AppLockState, nowMillis: Long): AppLockState =
        tick(state, nowMillis).copy(inForeground = true, backgroundSinceMillis = null)

    /** Time has passed: locks when the app has been in the background at least the timeout. */
    fun tick(state: AppLockState, nowMillis: Long): AppLockState {
        val settings = state.settings ?: return state
        val since = state.backgroundSinceMillis ?: return state
        if (!settings.enabled || state.locked) return state
        val elapsed = nowMillis - since
        // A clock that went backwards (never for elapsedRealtime) counts as "long enough".
        return if (elapsed < 0 || elapsed >= settings.timeout.millis) state.copy(locked = true) else state
    }

    /** The user confirmed the screen lock. */
    fun unlocked(state: AppLockState): AppLockState = state.copy(locked = false, backgroundSinceMillis = null)

    /**
     * Whether [surface] must wait for the user to unlock (true) at [status]. [AppLockStatus.UNKNOWN]
     * counts as locked for what reads or writes the user's data, so nothing slips through while
     * the setting is being read.
     */
    fun blocks(surface: AppSurface, status: AppLockStatus): Boolean = when (surface) {
        AppSurface.APP, AppSurface.APP_FUNCTION -> !status.allowsContent

        AppSurface.ALARM_SCREEN,
        AppSurface.TIMER_NOTIFICATION,
        AppSurface.BACKGROUND_WORK,
        AppSurface.REMINDER_NOTIFICATION,
        AppSurface.WATCH,
        AppSurface.WIDGET,
        AppSurface.QUICK_SETTINGS_TILE,
        -> false
    }

    /**
     * Whether [surface] may show the user's content (list rows, meals, counts) with [settings].
     * The widgets and the tile are seen without unlocking (the home screen, the lock screen's
     * quick settings), so they show a locked placeholder whenever the lock is on, unlocked or not.
     */
    fun showsContent(surface: AppSurface, settings: AppLockSettings?): Boolean = when (surface) {
        AppSurface.WIDGET, AppSurface.QUICK_SETTINGS_TILE -> settings?.enabled != true
        else -> true
    }
}

/** The ways into the app the lock has a rule for ([AppLockPolicy.blocks], [AppLockPolicy.showsContent]). */
enum class AppSurface {
    /** The app's screens (MainActivity), whatever opened them: the launcher, a deep link, a share, a shortcut, a notification tap. */
    APP,

    /** core:timer's full-screen alarm on the lock screen (Stop, +1 min): never behind the lock. */
    ALARM_SCREEN,

    /** The timer notifications and their actions. */
    TIMER_NOTIFICATION,

    /** WorkManager: the sync, the outbox, the reminders' checks. */
    BACKGROUND_WORK,

    /** The list, expiry, meal and store reminders. */
    REMINDER_NOTIFICATION,

    /** The Wear app with its own token. */
    WATCH,

    /** The home-screen widgets (shopping list, today's meals). */
    WIDGET,

    /** The quick settings tile "Handla". */
    QUICK_SETTINGS_TILE,

    /** App Functions (an assistant reads or writes): refused while locked. */
    APP_FUNCTION,
}
