package casa.moller.tandem.core.sync

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Timing rules of the sync (plan 3.5 table "Synkstrategi", plan 4.1). The sync plan
 * (`SyncPlanner`), the workers and the repositories read every limit from here.
 */
object SyncSchedule {
    /** WorkManager's periodic background sync (and the minimum WorkManager allows). */
    val PERIODIC: Duration = 15.minutes

    /** The recipe index is read again on app start (and by the periodic sync) at most this often. */
    val RECIPE_INDEX_MIN_INTERVAL: Duration = 1.hours

    /** Small reference tables (units, foods, keywords, meal types, ...) in the periodic sync. */
    val REFERENCE_DATA_INTERVAL: Duration = 6.hours

    /** Small reference tables on app start, resume or pull to refresh (plan 3.5: "max 1/h"). */
    val REFERENCE_DATA_MIN_INTERVAL: Duration = 1.hours

    /** Pinned and planned recipes are fetched again after this long. */
    val PINNED_DETAIL_MAX_AGE: Duration = 24.hours

    /** The server version is checked again on resume at most this often (plan 3.6 point 3). */
    val SERVER_CHECK_MIN_INTERVAL: Duration = 15.minutes

    /** Meal plan window and Today's carousels in an automatic sync. */
    val HOME_INTERVAL: Duration = 15.minutes

    /** Opening Today refreshes the meal plan and the carousels at most this often. */
    val HOME_MIN_INTERVAL: Duration = 2.minutes

    /** Stale-while-revalidate: a stored detail younger than this is not fetched again when opened. */
    val DETAIL_FRESH_FOR: Duration = 5.minutes

    /**
     * A stored sync or fetch time further than this in the future means the device clock was
     * ahead when it was written: it then counts as due or stale (normal NTP nudges stay below it).
     */
    val CLOCK_SKEW_TOLERANCE: Duration = 5.minutes

    /** Pause after each background detail fetch (plan 3.5 "liten paus"). */
    val DETAIL_FETCH_PAUSE: Duration = 250.milliseconds

    /** Meal plan window around today, in days (plan 3.5: -14 to +42). */
    const val MEAL_PLAN_DAYS_BEFORE: Int = 14
    const val MEAL_PLAN_DAYS_AFTER: Int = 42

    /** "Planned" recipes for the prefetch: meals from yesterday to a week ahead. */
    const val PLANNED_DAYS_BEFORE: Int = 1
    const val PLANNED_DAYS_AFTER: Int = 7

    /** At most this many detail fetches run at the same time (plan 3.5, 4.1). */
    const val MAX_PARALLEL_DETAIL_FETCHES: Int = 2

    /** At most this many details are prefetched per sync run (the rest follow in the next run). */
    const val MAX_DETAIL_PREFETCH_PER_RUN: Int = 40

    /** At most this many images are prefetched per sync run. */
    const val MAX_IMAGE_PREFETCH_PER_RUN: Int = 60

    /**
     * "Download all recipes" (Settings → Sync, Phase 1b): at most this many further details (and
     * as many images) per run, on top of the usual ones, still 2 at a time with a pause. About
     * 300 recipes are done in one run; a larger collection continues in the next runs.
     */
    const val MAX_DOWNLOAD_ALL_PER_RUN: Int = 400

    /** Recipes per Today carousel. */
    const val HOME_SECTION_SIZE: Int = 20

    /** "Often cooked" candidates read from the server before they are sorted by the cook count. */
    const val OFTEN_COOKED_CANDIDATES: Int = 50

    /** "Recently viewed": `viewedon_gte = today - 14 days` (plan 3.3). */
    const val RECENTLY_VIEWED_DAYS: Int = 14

    /** "Not cooked lately": `cookedon_lte = today - 60 days` (plan 3.3). */
    const val NOT_COOKED_LATELY_DAYS: Int = 60

    /** Circuit breaker (plan 4.1): more than this many errors within [BREAKER_ERROR_WINDOW] ... */
    const val BREAKER_MAX_ERRORS: Int = 5
    val BREAKER_ERROR_WINDOW: Duration = 60.seconds

    /** ... or this many 404/400 answers within [BREAKER_CLIENT_ERROR_WINDOW] (half of CrowdSec's limit) ... */
    const val BREAKER_MAX_CLIENT_ERRORS: Int = 5
    val BREAKER_CLIENT_ERROR_WINDOW: Duration = 100.seconds

    /** ... pause the sync for this long. */
    val BREAKER_PAUSE: Duration = 5.minutes

    /** A 403 without a Tandoor `detail` (a firewall) pauses for this long first, doubling up to [BLOCKED_MAX_PAUSE]. */
    val BLOCKED_FIRST_PAUSE: Duration = 5.minutes
    val BLOCKED_MAX_PAUSE: Duration = 1.hours

    /**
     * A 429 stops every request of the account until its `Retry-After` (FACTS "Konsekvenser" 1,
     * plan 4.1: never retried automatically): this long without the header, at least
     * [THROTTLED_MIN_PAUSE] and at most [THROTTLED_MAX_PAUSE].
     */
    val THROTTLED_DEFAULT_PAUSE: Duration = 5.minutes
    val THROTTLED_MIN_PAUSE: Duration = 30.seconds
    val THROTTLED_MAX_PAUSE: Duration = 1.hours

    /** First retry delay of the outbox worker (exponential backoff, plan 3.5). */
    val OUTBOX_BACKOFF: Duration = 30.seconds

    /** Disk cache for recipe images, sized for the recipes kept offline. */
    const val IMAGE_DISK_CACHE_BYTES: Long = 250L * 1024 * 1024

    /** Unique WorkManager names, so a request never runs twice. */
    const val WORK_PERIODIC: String = "tandem.sync.periodic"
    const val WORK_ONE_TIME: String = "tandem.sync.now"
    const val WORK_OUTBOX: String = "tandem.sync.outbox"

    /** WorkManager tags: every sync run, and every outbox run. */
    const val TAG_SYNC: String = "tandem.sync"
    const val TAG_OUTBOX: String = "tandem.outbox"
}
