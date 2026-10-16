package casa.moller.tandem.core.data.timer

import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerCapabilities
import casa.moller.tandem.core.model.timer.TimerStartResult
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

/**
 * Cook-mode timers (features #6/#7, plan 8.1). The ONLY way features reach timers: the real
 * implementation lives in core:timer (exact alarm, ongoing notification / Live Update, ringing
 * foreground service, boot receiver) and is bound by Hilt; features never depend on core:timer.
 *
 * - **Threading:** main-safe; every call returns quickly (persisting and scheduling happen
 *   inside, off the main thread).
 * - **Lifetime:** timers belong to the device, not to a screen, an account or demo mode: they
 *   survive leaving cook mode, rotation, process death and reboot ([CookTimer] explains the
 *   clock). Several run at once (at most [CookTimer.MAX_PARALLEL_TIMERS]).
 * - **Ringing:** a due timer becomes RINGING by itself (alarm), also with the app closed and the
 *   screen locked; [timers] shows it as soon as the process sees it.
 * - **Unknown id:** pause/resume/addOneMinute/cancel/stopRinging with an id that does not exist
 *   (any more) do nothing.
 * - **Permissions in context (plan 8.7):** before the FIRST [start] the UI checks
 *   [capabilities]: when [TimerCapabilities.shouldAskForNotifications] it launches the system
 *   dialog itself (`ActivityResultContracts.RequestPermission` for `POST_NOTIFICATIONS`, in the
 *   feature; no core:timer types needed), reports the answer with
 *   [onNotificationPermissionResult] and then starts the timer whatever the answer was. A timer
 *   without notifications still runs and rings while cook mode is open; the UI says so.
 */
interface TimerController {

    /** Every timer that is not cleared, oldest first. Starts empty. */
    val timers: StateFlow<List<CookTimer>>

    /** What the system allows right now; refreshed by [refreshCapabilities]. */
    val capabilities: StateFlow<TimerCapabilities>

    /**
     * Starts a timer, or hands it to the clock app when the setting says so
     * ([casa.moller.tandem.core.model.timer.TimerMode.CLOCK_APP]).
     *
     * @param label shown in cook mode, the notification and the alarm (e.g. "Koka pastan").
     * @param recipeId the recipe cook mode runs, so the notification can open it again.
     * @param stepIndex the 0-based step the timer belongs to.
     */
    suspend fun start(label: String, duration: Duration, recipeId: Long? = null, stepIndex: Int? = null): TimerStartResult

    suspend fun pause(id: Long)

    suspend fun resume(id: Long)

    /** "+1 min": also restarts a ringing or finished timer for one minute ([CookTimer.plusOneMinute]). */
    suspend fun addOneMinute(id: Long)

    /** Removes the timer whatever its state; its alarm and notifications go away. */
    suspend fun cancel(id: Long)

    /** "Stopp" on a ringing timer: silence it, it stays as FINISHED until [clearFinished]. */
    suspend fun stopRinging(id: Long)

    /** Removes every FINISHED timer (cook mode's "Klart!" page or the HUD's "clear"). */
    suspend fun clearFinished()

    /**
     * The answer of the system's notification dialog the UI showed: remembers that it was asked
     * ([casa.moller.tandem.core.model.settings.AppSettings.notificationPermissionAsked]) and
     * refreshes [capabilities].
     */
    suspend fun onNotificationPermissionResult(granted: Boolean)

    /** Reads the permissions and channel state again (call on resume: the user may have changed them in the system settings). */
    fun refreshCapabilities()
}
