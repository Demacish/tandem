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
 * @property done the "Klart!" page.
 * @property offline the device is offline: a cook log is saved and sent later.
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
    val dynamicColor: Boolean = false,
    val exactAlarmPromptDismissed: Boolean = false,
    val notificationPrompt: PendingTimer? = null,
    val done: DoneState = DoneState(),
    val offline: Boolean = false,
    val demo: Boolean = false,
) {
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

    /** Notifications or the alarm channel are off: the alarm only rings while the app is open. */
    val showSilentAlarmWarning: Boolean
        get() = activeTimers.isNotEmpty() && capabilities.alarmMayBeSilent && !capabilities.shouldAskForNotifications
}

/**
 * One step page.
 *
 * @property index 0-based, the page and the timers' `stepIndex`.
 * @property instruction Markdown with the templates rendered for the chosen servings (core:domain
 *   `RecipeDisplay`, web parity); the timer suggestions' text ranges index into it.
 * @property ingredients the step's ingredients, scaled.
 * @property suggestions the timer chips: `Step.time` first, then the durations in the text
 *   (core:domain `StepTimerSuggestions`).
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

    /** A timer chip or a duration in the text was tapped. */
    data class StartTimer(val stepIndex: Int, val suggestion: TimerSuggestion) : CookAction

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
