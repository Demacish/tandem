package casa.moller.tandem.core.data.mealplan

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.mealplan.MealCalendarState
import kotlinx.coroutines.flow.Flow

/**
 * Fas 5 (kalender lane, plan §3.1, §7 #36): "Matsedeln i kalendern" (Veckan's menu and Mer →
 * Inställningar). Implemented in core:sync (`DefaultMealCalendarRepository`); main-safe, flows never
 * throw, nothing ever happens in demo mode.
 *
 * - **The link** (option a) is a token of its OWN with the scope `mealplan` (never the account's
 *   token, plan §3.1/§5.5), created on the server by [enableLink] and revoked by [disableLink];
 *   [replaceLink] creates a new one and revokes the old. Anyone with the address can read the plan.
 *   Not for guests, and not where the server cannot take the token in the address.
 * - **The phone's calendar** (option b) is a local calendar "Tandem – matsedel" written from the
 *   stored plan (offline), refreshed at least daily and after every change of the plan; turning it
 *   off, logging out or demo mode removes it. The calendar permissions are asked by the screen
 *   before [setLocalCalendar] (`true`).
 */
interface MealCalendarRepository {
    /** What the screen shows. */
    val state: Flow<MealCalendarState>

    /** Creates the link's token. Fails offline, for guests, in demo mode or when the server refuses. */
    suspend fun enableLink(): Outcome<Unit>

    /** "Byt länk": a new token, then the old one is revoked; the old address stops working. */
    suspend fun replaceLink(): Outcome<Unit>

    /**
     * "Stäng av": revokes the token on the server and forgets the link. Offline the link is NOT
     * forgotten (it would stay valid on the server); the failure is returned.
     */
    suspend fun disableLink(): Outcome<Unit>

    /** Turns option b on (writes the calendar now) or off (removes the calendar and its events). */
    suspend fun setLocalCalendar(enabled: Boolean): Outcome<Unit>

    /** Writes the local calendar now ("Uppdatera nu"); the number of meals it holds. */
    suspend fun refreshLocalCalendar(): Outcome<Int>

    /** True when READ_CALENDAR and WRITE_CALENDAR are granted. */
    fun hasCalendarPermission(): Boolean
}
