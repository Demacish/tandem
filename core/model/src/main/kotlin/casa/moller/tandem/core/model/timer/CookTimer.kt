package casa.moller.tandem.core.model.timer

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Where a timer is in its life (plan 8.1 "Timer-HUD"). */
enum class TimerState {
    /** Counting down; [CookTimer.endsAt] is when it rings. */
    RUNNING,

    /** Stopped by the user; [CookTimer.pausedRemaining] is what is left. */
    PAUSED,

    /** Reached zero and is ringing (alarm notification, sound) until stopped or extended. */
    RINGING,

    /** Rang and was stopped. Kept so cook mode can show "done" until it is cleared. */
    FINISHED,
}

/**
 * Who runs a cook-mode timer (plan 6.2 "Inställningar → Beteende": "timer i appen eller i
 * klockappen"). Stored in [casa.moller.tandem.core.model.settings.AppSettings.timerMode].
 */
enum class TimerMode {
    /** Tandem's own timers: several at once, notification, alarm on a locked screen (default). */
    IN_APP,

    /** Hand the timer to the system clock app (`AlarmClock.ACTION_SET_TIMER`); Tandem does not track it. */
    CLOCK_APP,
}

/**
 * One cook-mode timer (features #6/#7). Pure data; the state changes below are the only
 * transitions, so the real controller (core:timer) and the fake (core:testing) behave the same.
 *
 * **Clock (decided in Phase 1b grund):** [endsAt] is a **wall-clock** instant (epoch based,
 * `kotlin.time.Instant`), not a monotonic `elapsedRealtime` value, because it is persisted and
 * must survive process death **and reboot**: the monotonic clock restarts at 0 on every boot, so
 * a stored monotonic end time means nothing afterwards, and `AlarmManager.setAlarmClock` (the
 * exact alarm that rings on a locked screen) only takes wall-clock (RTC) times. Time zone changes
 * do not matter (the instant is UTC). The cost is a manual clock change by the user: the
 * implementation listens for `ACTION_TIME_CHANGED` and, while its process lives, recomputes the
 * end from an in-memory monotonic anchor and reschedules the alarm; after a reboot the stored
 * [endsAt] is used as is (`RECEIVE_BOOT_COMPLETED` reschedules, a timer whose end passed while
 * the phone was off rings at once).
 *
 * @property id unique per installation, assigned by the controller (also used to derive the
 *   notification id and the alarm's request code).
 * @property label what the user sees ("Koka pastan", "10 min"); never empty.
 * @property recipeId the recipe the timer was started from, or null for a free timer.
 * @property stepIndex the 0-based step of [recipeId] it belongs to (cook mode jumps back to it).
 * @property sourceKey where [recipeId] comes from ([TimerSource]: an account id or demo mode), so
 *   a timer started in demo mode or for another account never opens cook mode for the active
 *   account's recipe with the same id; null for a free timer or one stored before 0.2.0.
 * @property duration the total length, including every "+1 min".
 * @property state see [TimerState].
 * @property endsAt RUNNING: when it rings; RINGING/FINISHED: when it rang; PAUSED: null.
 * @property pausedRemaining PAUSED: what is left; null in every other state.
 * @property createdAt when it was started; timers are listed oldest first.
 */
data class CookTimer(
    val id: Long,
    val label: String,
    val recipeId: Long? = null,
    val stepIndex: Int? = null,
    val sourceKey: String? = null,
    val duration: Duration,
    val state: TimerState,
    val endsAt: Instant?,
    val pausedRemaining: Duration? = null,
    val createdAt: Instant,
) {
    init {
        require(label.isNotBlank()) { "a timer needs a label" }
        require(duration.isPositive()) { "a timer needs a positive duration" }
        require(stepIndex == null || stepIndex >= 0) { "stepIndex is 0-based" }
        when (state) {
            TimerState.PAUSED -> require(endsAt == null && pausedRemaining != null) { "a paused timer has pausedRemaining only" }
            else -> require(endsAt != null && pausedRemaining == null) { "a $state timer has endsAt only" }
        }
    }

    /** What is left at [now]: never negative, zero once it rang. */
    fun remaining(now: Instant): Duration = when (state) {
        TimerState.RUNNING -> (endsAt!! - now).coerceAtLeast(Duration.ZERO)
        TimerState.PAUSED -> pausedRemaining!!
        TimerState.RINGING, TimerState.FINISHED -> Duration.ZERO
    }

    /** True when it is running and its end has come; the controller then calls [ring]. */
    fun isDue(now: Instant): Boolean = state == TimerState.RUNNING && endsAt!! <= now

    /** RUNNING → PAUSED with what is left at [now]; other states are unchanged. */
    fun pause(now: Instant): CookTimer =
        if (state != TimerState.RUNNING) this else copy(state = TimerState.PAUSED, endsAt = null, pausedRemaining = remaining(now))

    /** PAUSED → RUNNING, ending [pausedRemaining] after [now]; other states are unchanged. */
    fun resume(now: Instant): CookTimer =
        if (state != TimerState.PAUSED) this else copy(state = TimerState.RUNNING, endsAt = now + pausedRemaining!!, pausedRemaining = null)

    /**
     * "+1 min" (plan 8.1, also an action of the alarm notification): a running or paused timer
     * gets one more minute; a ringing or finished one runs again for one minute from [now].
     */
    fun plusOneMinute(now: Instant): CookTimer = when (state) {
        TimerState.RUNNING -> copy(duration = duration + ONE_MINUTE, endsAt = endsAt!! + ONE_MINUTE)

        TimerState.PAUSED -> copy(duration = duration + ONE_MINUTE, pausedRemaining = pausedRemaining!! + ONE_MINUTE)

        TimerState.RINGING, TimerState.FINISHED ->
            copy(duration = duration + ONE_MINUTE, state = TimerState.RUNNING, endsAt = now + ONE_MINUTE)
    }

    /** RUNNING and due → RINGING (keeps [endsAt] as the time it rang); other states are unchanged. */
    fun ring(now: Instant): CookTimer = if (isDue(now)) copy(state = TimerState.RINGING) else this

    /** RINGING → FINISHED ("Stopp"); other states are unchanged. */
    fun stopRinging(): CookTimer = if (state == TimerState.RINGING) copy(state = TimerState.FINISHED) else this

    companion object {
        /** How many timers may exist (not FINISHED) at the same time. Phase 1 needs at least two. */
        const val MAX_PARALLEL_TIMERS: Int = 8

        /** Shorter timers are refused (a "1 s" chip is a detection mistake, not a timer). */
        val MIN_DURATION: Duration = 1.seconds

        /** What "+1 min" adds. */
        val ONE_MINUTE: Duration = 1.minutes

        /** A new RUNNING timer started at [now]. */
        fun start(
            id: Long,
            label: String,
            duration: Duration,
            now: Instant,
            recipeId: Long? = null,
            stepIndex: Int? = null,
            sourceKey: String? = null,
        ): CookTimer = CookTimer(
            id = id,
            label = label,
            recipeId = recipeId,
            stepIndex = stepIndex,
            sourceKey = sourceKey,
            duration = duration,
            state = TimerState.RUNNING,
            endsAt = now + duration,
            createdAt = now,
        )
    }
}

/** What [casa.moller.tandem.core.data.timer.TimerController.start] did. */
sealed interface TimerStartResult {
    /** An in-app timer is running. */
    data class Started(val timer: CookTimer) : TimerStartResult

    /** [TimerMode.CLOCK_APP]: the clock app got the timer; Tandem does not track it. */
    data object HandedToClockApp : TimerStartResult

    /** [CookTimer.MAX_PARALLEL_TIMERS] timers exist already; nothing was started. */
    data class TooManyTimers(val max: Int) : TimerStartResult

    /** [TimerMode.CLOCK_APP] but no app handles `ACTION_SET_TIMER`; the UI offers an in-app timer. */
    data object ClockAppUnavailable : TimerStartResult

    /** The duration is shorter than [CookTimer.MIN_DURATION] or the label is blank. */
    data object Invalid : TimerStartResult
}

/**
 * What the system currently lets timers do (plan 8.1, 8.7, 4.4). The UI uses it to ask for
 * permissions **in context** (at the first timer, never at first start) and to warn when an alarm
 * could be late or silent. Never shows raw permission names (docs/design/README.md).
 *
 * @property notificationsAllowed `POST_NOTIFICATIONS` granted and the app's notifications on.
 * @property notificationPermissionAsked the app already showed the system dialog once
 *   ([casa.moller.tandem.core.model.settings.AppSettings.notificationPermissionAsked]); after a
 *   denial the UI links to the system settings instead of asking again.
 * @property alarmChannelEnabled the channel [casa.moller.tandem.core.model.notification.TandemNotificationChannel.TIMER_ALARM]
 *   can sound: not switched off, not lowered below "default" (the shade's "Silent") and with a
 *   sound. Otherwise the alarm notification is silent (the ringing service still plays the alarm).
 * @property exactAlarmsAllowed exact alarms can be scheduled (always in the sideload build with
 *   `USE_EXACT_ALARM`; in the play build only after the user allowed `SCHEDULE_EXACT_ALARM`).
 * @property exactAlarmsRequestable the user can allow exact alarms in the system settings (play build).
 * @property fullScreenAlarmAllowed the alarm may take over a locked screen (sideload build with
 *   `USE_FULL_SCREEN_INTENT` still granted); otherwise it is a heads-up notification.
 * @property clockAppAvailable an app handles `AlarmClock.ACTION_SET_TIMER` ([TimerMode.CLOCK_APP]).
 */
data class TimerCapabilities(
    val notificationsAllowed: Boolean,
    val notificationPermissionAsked: Boolean,
    val alarmChannelEnabled: Boolean,
    val exactAlarmsAllowed: Boolean,
    val exactAlarmsRequestable: Boolean,
    val fullScreenAlarmAllowed: Boolean,
    val clockAppAvailable: Boolean,
) {
    /** Show the system's notification dialog now (the user is starting a timer). */
    val shouldAskForNotifications: Boolean
        get() = !notificationsAllowed && !notificationPermissionAsked

    /** The alarm may not be seen or heard: warn and link to the system settings. */
    val alarmMayBeSilent: Boolean
        get() = !notificationsAllowed || !alarmChannelEnabled

    /** The alarm may ring late (inexact alarm, play build without the exact-alarm permission). */
    val alarmMayBeLate: Boolean
        get() = !exactAlarmsAllowed

    companion object {
        /** Everything allowed (sideload build after the notification permission was granted). */
        val AllGranted: TimerCapabilities = TimerCapabilities(
            notificationsAllowed = true,
            notificationPermissionAsked = true,
            alarmChannelEnabled = true,
            exactAlarmsAllowed = true,
            exactAlarmsRequestable = false,
            fullScreenAlarmAllowed = true,
            clockAppAvailable = true,
        )

        /** A fresh install of the sideload build before the first timer: only notifications are missing. */
        val FreshInstall: TimerCapabilities = AllGranted.copy(notificationsAllowed = false, notificationPermissionAsked = false)
    }
}

/**
 * Where a cook-mode timer's recipe id belongs ([CookTimer.sourceKey]): recipe ids are per server
 * (and demo mode has its own), so the shell opens cook mode for a timer only when its source is
 * the active one, and the timer list otherwise.
 */
object TimerSource {
    /** Demo mode's recipes (`tandem-demo.db`). */
    const val DEMO: String = "demo"

    /** The source of the app as it is now: [DEMO] in demo mode, else the active account's id (null: none). */
    fun of(demoMode: Boolean, accountId: String?): String? = if (demoMode) DEMO else accountId

    /**
     * True when [timer]'s recipe may be opened while [activeSource] is active: same source, or a
     * timer without a recorded source (free timers, timers stored before 0.2.0).
     */
    fun matches(timer: CookTimer, activeSource: String?): Boolean = timer.sourceKey == null || timer.sourceKey == activeSource
}
