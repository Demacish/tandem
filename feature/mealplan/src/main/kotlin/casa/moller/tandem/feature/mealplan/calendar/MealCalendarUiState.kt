package casa.moller.tandem.feature.mealplan.calendar

import androidx.annotation.StringRes
import casa.moller.tandem.core.model.mealplan.LocalMealCalendar
import casa.moller.tandem.core.model.mealplan.MealCalendarAvailability
import casa.moller.tandem.core.model.mealplan.MealCalendarLink

/** What is running right now (its buttons wait). */
enum class MealCalendarBusy { LOCAL, LINK }

/** The confirmation dialog that is open. */
enum class LinkConfirm { REPLACE, DISABLE }

/**
 * "Matsedeln i kalendern" (Fas 5, kalender lane, plan §7 #36).
 *
 * @property availability who may use it (demo mode and no account show why not).
 * @property link option a, the subscription link.
 * @property local option b, the phone's own calendar.
 * @property busy an action is running.
 * @property confirm "Ny länk" or "Stäng av" waits for a yes.
 * @property permissionDenied the user said no to the calendar permission (the card offers the system settings).
 * @property canOpenInCalendarApp an app on the phone opens `webcal://` links (set by the route).
 */
data class MealCalendarUiState(
    val loading: Boolean = true,
    val availability: MealCalendarAvailability = MealCalendarAvailability.LOADING,
    val link: MealCalendarLink = MealCalendarLink(),
    val local: LocalMealCalendar = LocalMealCalendar(),
    val busy: MealCalendarBusy? = null,
    val confirm: LinkConfirm? = null,
    val permissionDenied: Boolean = false,
    val canOpenInCalendarApp: Boolean = false,
) {
    val available: Boolean
        get() = availability == MealCalendarAvailability.AVAILABLE
}

/** What the screen can ask for. */
sealed interface MealCalendarAction {
    /** The switch "Visa i telefonens kalender". */
    data class SetLocal(val enabled: Boolean) : MealCalendarAction

    /** "Uppdatera nu". */
    data object RefreshLocal : MealCalendarAction

    /** "Tillåt" on the permission card: ask the system again. */
    data object AskPermission : MealCalendarAction

    /** The answer of the system's calendar permission dialog. */
    data class PermissionResult(val granted: Boolean) : MealCalendarAction

    /** "Öppna inställningar": the app's page in the system settings (handled by the route). */
    data object OpenAppSettings : MealCalendarAction

    data object CreateLink : MealCalendarAction

    /** Handled by the route: the clipboard. */
    data object CopyLink : MealCalendarAction

    /** Handled by the route: the share sheet. */
    data object ShareLink : MealCalendarAction

    /** Handled by the route: `webcal://` to the calendar app. */
    data object OpenInCalendarApp : MealCalendarAction

    data object AskReplace : MealCalendarAction

    data object AskDisable : MealCalendarAction

    /** "Ja" in the open confirmation. */
    data object Confirm : MealCalendarAction

    /** The confirmation was dismissed. */
    data object Dismiss : MealCalendarAction
}

/** One-off results. */
sealed interface MealCalendarEffect {
    /** A snackbar; [arg] fills `%1$s` when the text has one. */
    data class Message(@StringRes val text: Int, val arg: Int? = null) : MealCalendarEffect

    /** Ask for READ_CALENDAR and WRITE_CALENDAR in context (the switch was turned on). */
    data object RequestPermission : MealCalendarEffect
}
