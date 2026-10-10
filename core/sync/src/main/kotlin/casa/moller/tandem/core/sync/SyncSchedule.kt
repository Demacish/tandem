package casa.moller.tandem.core.sync

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Timing rules of the sync (plan 3.5 table "Synkstrategi", plan 4.1). Phase 1 foundation: the
 * sync lane builds SyncManager and the workers on these values.
 */
object SyncSchedule {
    /** WorkManager's periodic background sync (and the minimum WorkManager allows). */
    val PERIODIC: Duration = 15.minutes

    /** The recipe index is read again on app start at most this often. */
    val RECIPE_INDEX_MIN_INTERVAL: Duration = 1.hours

    /** Small reference tables (units, foods, keywords, meal types, ...). */
    val REFERENCE_DATA_INTERVAL: Duration = 6.hours

    /** Pinned and planned recipes are fetched again after this long. */
    val PINNED_DETAIL_MAX_AGE: Duration = 24.hours

    /** The server version is checked again on resume at most this often (plan 3.6 point 3). */
    val SERVER_CHECK_MIN_INTERVAL: Duration = 15.minutes

    /** Meal plan window around today, in days (plan 3.5: -14 to +42). */
    const val MEAL_PLAN_DAYS_BEFORE: Int = 14
    const val MEAL_PLAN_DAYS_AFTER: Int = 42

    /** At most this many detail fetches run at the same time (plan 3.5, 4.1). */
    const val MAX_PARALLEL_DETAIL_FETCHES: Int = 2

    /** Unique WorkManager names, so a request never runs twice. */
    const val WORK_PERIODIC: String = "tandem.sync.periodic"
    const val WORK_ONE_TIME: String = "tandem.sync.now"
    const val WORK_OUTBOX: String = "tandem.sync.outbox"
}
