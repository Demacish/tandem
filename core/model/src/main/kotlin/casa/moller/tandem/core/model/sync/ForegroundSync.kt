package casa.moller.tandem.core.model.sync

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/**
 * What a screen keeps fresh while it is visible (plan 3.5 "Var max(shopping_min_autosync_interval,
 * 15–30 s) när inköpsskärmen är öppen", 8.6 "Snabb polling medan listan är öppen").
 */
sealed interface ForegroundTarget {
    /** The shopping list (`updated_after` deltas, a full re-read every 10th time). */
    data object ShoppingList : ForegroundTarget

    /** The meal plan around [weekStart] (the window rule of plan 3.5: −14..+42 days). */
    data class MealPlan(val weekStart: LocalDate) : ForegroundTarget
}

/**
 * The state of foreground polling.
 *
 * @property active polling runs (online, logged in, not paused by the sync guard).
 * @property interval the time between polls now.
 * @property lastPollAt the last successful poll, if any.
 * @property paused why it does not poll (offline, safe mode, guard), if it does not.
 */
data class ForegroundSyncState(
    val active: Boolean = false,
    val interval: Duration = ForegroundPolling.DEFAULT_INTERVAL,
    val lastPollAt: Instant? = null,
    val paused: SyncPause? = null,
)

/** Polling intervals (plan 3.5). */
object ForegroundPolling {
    /** The interval when the server's floor is lower (Tandoor's default floor is 5 s). */
    val DEFAULT_INTERVAL: Duration = 20.seconds

    /** Never faster than this, whatever the server allows (plan 4.1: 2 workers, CrowdSec). */
    val MIN_INTERVAL: Duration = 15.seconds

    /**
     * `max(shopping_min_autosync_interval, 15–30 s)`: the server's floor (a STRING such as "5"), at
     * least [MIN_INTERVAL]. Phase 2 (integration lane): also at least the user's own
     * `shopping_auto_sync` ([userSeconds], the web's interval; plan 3.3 "follow the preferences"),
     * so a user who slowed the web's sync down gets the same in the app.
     */
    fun intervalFor(serverMinimumSeconds: Int?, userSeconds: Int? = null): Duration {
        val floor = (serverMinimumSeconds ?: 0).seconds
        val own = (userSeconds ?: 0).coerceAtLeast(0).seconds
        return maxOf(DEFAULT_INTERVAL, floor, own, MIN_INTERVAL)
    }

    /** Phase 2 (integration lane): `shopping_auto_sync` 0 turns the web's automatic sync off, and the app's polling too. */
    fun pollsShoppingList(userSeconds: Int?): Boolean = userSeconds != 0
}
