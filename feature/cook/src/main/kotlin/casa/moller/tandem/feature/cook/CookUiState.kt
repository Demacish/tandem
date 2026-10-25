package casa.moller.tandem.feature.cook

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.recipe.IngredientRow
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.settings.CookTextScale
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerCapabilities
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.model.timer.TimerSuggestion
import kotlin.time.Duration

/**
 * What cook mode shows (plan 6.2 "Kokläge", features #6–#8), built by [CookViewModel].
 *
 * Pages: one per step ([steps]), then the "Klart!" page ([donePage]).
 *
 * @property loading nothing has been read yet (first frame).
 * @property hasDetail the full recipe is stored; without it cook mode shows [error] (or loads).
 * @property page the page shown, 0-based; [donePage] is "Klart!". Survives rotation and process
 *   death (SavedStateHandle).
 * @property servings the servings the amounts are scaled to (the detail screen's choice, changeable here).
 * @property ingredientsExpanded the phone's ingredient section of the step is open.
 * @property timers every timer of the device (not only this recipe's), oldest first.
 * @property capabilities what the system allows timers to do (notifications, exact alarms).
 * @property textScale A−/A+ on top of the system font scale ([CookTextScale]).
 * @property alwaysDark the setting "Kokläge alltid mörkt".
 * @property notificationPrompt a timer waits for the in-context question about notifications
 *   (plan 8.7: asked at the first timer, never at first start).
 * @property notificationPromptDeclined "Not now" on that question in this cook mode: not asked again,
 *   and the alarm-may-be-silent warning shows although the system dialog was never answered.
 * @property done the "Klart!" page.
 * @property sendState whether a saved cook log goes to Tandoor now or waits, and why
 *   ([CookLogSendState], from the sync's pause).
 * @property demo demo mode: the cook log stays on the device.
 */
@Immutable
data class CookUiState(
    val recipeId: Long,
    val loading: Boolean = true,
    val recipeName: String = "",
    val imageUrl: String? = null,
    val hasDetail: Boolean = false,
    val error: TandemError? = null,
    val steps: List<CookStep> = emptyList(),
    val page: Int = 0,
    val servings: CookServings? = null,
    val ingredientsExpanded: Boolean = true,
    val struckIngredientIds: Set<Long> = emptySet(),
    val unitChoices: Map<Long, Int> = emptyMap(),
    val timers: List<CookTimer> = emptyList(),
    val capabilities: TimerCapabilities = TimerCapabilities.AllGranted,
    val textScale: Float = CookTextScale.DEFAULT,
    val alwaysDark: Boolean = false,
    val exactAlarmPromptDismissed: Boolean = false,
    val notificationPrompt: PendingTimer? = null,
    val notificationPromptDeclined: Boolean = false,
    val done: DoneState = DoneState(),
    val sendState: CookLogSendState = CookLogSendState.NOW,
    val demo: Boolean = false,
) {
    /** The device is offline: a cook log is saved and sent later. */
    val offline: Boolean
        get() = sendState == CookLogSendState.OFFLINE

    /** The "Klart!" page comes after the last step. */
    val donePage: Int
        get() = steps.size

    val pageCount: Int
        get() = steps.size + 1

    val isDonePage: Boolean
        get() = page >= donePage

    val currentStep: CookStep?
        get() = steps.getOrNull(page)

    /** Timers that are not finished (running, paused or ringing). */
    val activeTimers: List<CookTimer>
        get() = timers.filter { it.state != TimerState.FINISHED }

    val canIncreaseText: Boolean
        get() = textScale < CookTextScale.MAX

    val canDecreaseText: Boolean
        get() = textScale > CookTextScale.MIN

    /**
     * Warn under the timers that an alarm may come late (play build without exact alarms, plan
     * 8.1/4.4), with "Allow" when the system lets the user change it and the prompt was not dismissed.
     */
    val showLateAlarmWarning: Boolean
        get() = activeTimers.isNotEmpty() && capabilities.alarmMayBeLate

    /**
     * Why a running timer's alarm may not be seen or heard (core:timer's behaviour, plan 8.1/8.7),
     * null when nothing is wrong or the in-context question is still to come:
     * - [SilentAlarmCause.NOTIFICATIONS_OFF]: no notifications. The alarm still RINGS (the ringing
     *   service plays the sound itself), but there is no notification or lock-screen alarm to stop
     *   it, so it can only be stopped in Tandem (or stops itself after 5 minutes).
     * - [SilentAlarmCause.ALARM_CHANNEL_OFF]: notifications are allowed but the "Timers (alarm)"
     *   channel cannot sound (blocked, set to "Silent" or without a sound). The ringing service
     *   still plays the alarm, but no alarm is shown over the lock screen; the channel's own
     *   settings page fixes it.
     */
    val silentAlarmCause: SilentAlarmCause?
        get() = when {
            activeTimers.isEmpty() -> null

            !capabilities.notificationsAllowed ->
                SilentAlarmCause.NOTIFICATIONS_OFF.takeIf { !capabilities.shouldAskForNotifications || notificationPromptDeclined }

            !capabilities.alarmChannelEnabled -> SilentAlarmCause.ALARM_CHANNEL_OFF

            else -> null
        }

    /** A [silentAlarmCause] warning is shown under the timers. */
    val showSilentAlarmWarning: Boolean
        get() = silentAlarmCause != null
}

/** Why a timer's alarm may be silent or hard to stop ([CookUiState.silentAlarmCause]). */
enum class SilentAlarmCause { NOTIFICATIONS_OFF, ALARM_CHANNEL_OFF }

/**
 * What happens to a cook log saved now ("Klart!", plan 4.1 terms of 0.1.0), from the sync's pause.
 *
 * - [NOW]: sent to Tandoor right away.
 * - [OFFLINE]: no network; sent when the device is online again.
 * - [SAFE_MODE]: the server runs an untested version; the outbox waits (plan 3.6).
 * - [NEEDS_LOGIN]: Tandoor refused the token; sent after "Logga in igen".
 * - [HELD]: the sync is held back for a while (blocked, circuit breaker, an import); sent later
 *   by itself.
 */
enum class CookLogSendState { NOW, OFFLINE, SAFE_MODE, NEEDS_LOGIN, HELD }

/**
 * One step page.
 *
 * @property index 0-based, the page and the timers' `stepIndex`.
 * @property instruction Markdown with the templates rendered for the chosen servings (core:domain
 *   `RecipeDisplay`, web parity).
 * @property imageUrl the step's photo (Tandoor `Step.file`, an image), shown under the text; null
 *   when the step has none.
 * @property ingredients the step's ingredients, scaled.
 * @property suggestions the timer chips: `Step.time` first, then the durations in the text
 *   (core:domain `StepTimerSuggestions`). Detection reads the VISIBLE text, i.e. [instruction]
 *   without its Markdown markers ([stepPlainText]), so "**10** min" is found too; the text ranges
 *   index into that plain text.
 * @property stepRecipeName the name of a recipe the step uses (Tandoor `step_recipe`), if any.
 */
@Immutable
data class CookStep(
    val index: Int,
    val number: Int,
    val name: String,
    val instruction: String,
    val ingredients: List<IngredientRow>,
    val suggestions: List<TimerSuggestion>,
    val hasBrokenTemplate: Boolean = false,
    val stepRecipeName: String? = null,
    val imageUrl: String? = null,
)

/** Servings of cook mode, like the detail's servings picker. */
@Immutable
data class CookServings(val current: Double, val original: Int, val text: String, val amountText: String) {
    val canDecrease: Boolean
        get() = current > MIN_SERVINGS

    companion object {
        const val MIN_SERVINGS: Double = 1.0
    }
}

/**
 * The "Klart!" page (Stitch kokl_ge_klart, feature #8).
 *
 * @property servings whole servings for the cook log (Tandoor stores integers), from cook mode's
 *   servings rounded half up until the user changes them here.
 * @property rating 1–5 stars, null = no rating (the field is left out).
 * @property saving the log is being written.
 */
@Immutable
data class DoneState(val servings: Int = 1, val rating: Int? = null, val comment: String = "", val saving: Boolean = false) {
    val canDecreaseServings: Boolean
        get() = servings > 1
}

/**
 * A timer the user started while cook mode first asks about notifications. Kept in the
 * SavedStateHandle as one string ([encode]), so it survives the system dialog even if the process dies.
 */
@Immutable
data class PendingTimer(val stepIndex: Int, val label: String, val durationSeconds: Long) {
    fun encode(): String = "$stepIndex|$durationSeconds|$label"

    companion object {
        /** The inverse of [encode]; null for anything else. */
        fun decode(text: String): PendingTimer? {
            val parts = text.split('|', limit = 3)
            if (parts.size != 3) return null
            val step = parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val seconds = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return parts[2].takeIf { it.isNotBlank() }?.let { PendingTimer(step, it, seconds) }
        }
    }
}

/** What cook mode can do. */
sealed interface CookAction {
    data object Next : CookAction

    data object Previous : CookAction

    /** The pager settled on [page] (swipe), or a timer jumps to its step. */
    data class GoToPage(val page: Int) : CookAction

    data object IncreaseServings : CookAction

    data object DecreaseServings : CookAction

    data object ToggleIngredients : CookAction

    data class ToggleIngredient(val id: Long) : CookAction

    data class SwitchUnit(val id: Long) : CookAction

    data object LargerText : CookAction

    data object SmallerText : CookAction

    /**
     * Start a timer from [suggestion]. Cook mode's screen first asks for the time (the dialog
     * "Justera tiden") and then sends [duration], the chosen time, with [durationText] for the
     * label; without them the suggestion starts as it is.
     */
    data class StartTimer(
        val stepIndex: Int,
        val suggestion: TimerSuggestion,
        val duration: Duration? = null,
        val durationText: String? = null,
    ) : CookAction

    data class PauseTimer(val id: Long) : CookAction

    data class ResumeTimer(val id: Long) : CookAction

    data class AddMinute(val id: Long) : CookAction

    data class CancelTimer(val id: Long) : CookAction

    data class StopRinging(val id: Long) : CookAction

    /** "Continue" in the notification question: the system dialog comes next. */
    data object AcceptNotificationPrompt : CookAction

    /** "Not now": the timer starts without asking (asked again at a later cook session). */
    data object DeclineNotificationPrompt : CookAction

    /** The answer of the system's notification dialog. */
    data class NotificationPermissionResult(val granted: Boolean) : CookAction

    /** Play build: "Not now" on "allow exact alarms". */
    data object DismissExactAlarmPrompt : CookAction

    /** Back from the system settings (or the app came back): read the permissions again. */
    data object RefreshCapabilities : CookAction

    /** A star on the "Klart!" page; the same star again clears the rating. */
    data class SetRating(val stars: Int) : CookAction

    data class SetComment(val text: String) : CookAction

    data object IncreaseLogServings : CookAction

    data object DecreaseLogServings : CookAction

    /** "Spara i kokloggen". */
    data object SaveCookLog : CookAction

    data object Retry : CookAction

    /**
     * How numbers are written in the UI's language and which language the step texts are read in
     * (timer detection ties), from the screen's locale.
     */
    data class SetLocale(val style: NumberStyle, val languageTag: String) : CookAction
}

/** One-off events of cook mode. */
sealed interface CookEffect {
    /** Show the system's notification dialog (the user accepted the in-context question). */
    data object RequestNotificationPermission : CookEffect

    /** A short message (snackbar). */
    data class Message(@StringRes val text: Int) : CookEffect

    /** The cook log was saved: say [message] and leave cook mode. */
    data class Finished(@StringRes val message: Int) : CookEffect
}

/**
 * Something TalkBack should say without moving focus and without a visible message
 * ([CookViewModel.announcements]): [text] with [argument], e.g. "Timer started: Pastan · 10 min".
 * Its own stream, so it never waits behind a snackbar or gets in the way of other effects.
 */
data class CookAnnouncement(@StringRes val text: Int, val argument: String)
