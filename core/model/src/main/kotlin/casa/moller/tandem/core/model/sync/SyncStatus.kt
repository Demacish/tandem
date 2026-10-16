package casa.moller.tandem.core.model.sync

import casa.moller.tandem.core.model.error.TandemError
import kotlin.time.Instant

/** Why a sync was asked for. Lets the sync manager throttle (plan 3.5: index at most once per hour on start). */
enum class SyncReason {
    /** The first sync after a login ("Fetching 312 recipes…"). */
    FIRST_SYNC,

    /** The app process started. */
    APP_START,

    /** The app came back to the foreground. */
    APP_RESUMED,

    /** The user pulled to refresh. Never throttled. */
    USER_REFRESH,

    /** WorkManager's periodic background sync. */
    PERIODIC,

    /** A login or account switch. */
    ACCOUNT_CHANGED,
}

/** The step a running sync is in, for progress texts. */
enum class SyncPhase {
    /** Checking the server version and capabilities. */
    SERVER_CHECK,

    /** Small tables: keywords, units, foods, meal types, user preferences. */
    REFERENCE_DATA,

    /** The recipe index (`recipe/flat`) and changed overviews. */
    RECIPE_INDEX,

    /** Details of pinned, planned and recently viewed recipes. */
    RECIPE_DETAILS,

    /** Images of recipes stored for offline use. */
    IMAGES,

    /** The meal plan window (-14 to +42 days). */
    MEAL_PLAN,

    /** Sending queued changes (cook log, view log in Phase 1). */
    OUTBOX,

    /** Finished successfully. */
    DONE,

    /** Stopped with an error ([SyncProgress.error]). */
    FAILED,
}

/**
 * Progress of a sync, e.g. "Fetching recipes 120 of 312".
 *
 * @property phase the current step.
 * @property done items finished in this step.
 * @property total items in this step, null when unknown.
 * @property error why the sync stopped; only set when [phase] is [SyncPhase.FAILED].
 */
data class SyncProgress(val phase: SyncPhase, val done: Int = 0, val total: Int? = null, val error: TandemError? = null) {
    /** 0.0..1.0 within the current step, null when [total] is unknown. */
    val fraction: Float?
        get() = total?.takeIf { it > 0 }?.let { (done.toFloat() / it).coerceIn(0f, 1f) }

    val isFinished: Boolean
        get() = phase == SyncPhase.DONE || phase == SyncPhase.FAILED
}

/** Why the sync is paused instead of running (plan 4.1, 5.4). Queued changes are always kept. */
enum class SyncPause {
    /** No network. */
    OFFLINE,

    /** The token was rejected (401 or a Tandoor 403 with `detail`): a new login is needed. */
    UNAUTHORIZED,

    /**
     * Held back for a while: a 403 without Tandoor's `detail` (a firewall in front of Tandoor) or
     * a 429 (rate limited; requests wait until `Retry-After`).
     */
    BLOCKED,

    /** Too many errors in a short time: paused for 5 minutes (circuit breaker). */
    CIRCUIT_BREAKER,

    /** The server version is untested: reading works, the outbox waits (plan 3.6). */
    SAFE_MODE,

    /** An import is running: background sync yields the server to it (plan 4.1). */
    IMPORT_RUNNING,
}

/**
 * The sync as the UI sees it (Settings, the sync badge, pull to refresh).
 *
 * @property running a sync is running now.
 * @property progress progress of the running sync, null when idle.
 * @property lastSuccess when the last sync finished successfully, null when never.
 * @property lastError why the last sync failed, cleared by the next success.
 * @property pendingChanges changes waiting in the outbox ("3 changes waiting").
 * @property pause why syncing is paused, null when it is not.
 */
data class SyncStatus(
    val running: Boolean = false,
    val progress: SyncProgress? = null,
    val lastSuccess: Instant? = null,
    val lastError: TandemError? = null,
    val pendingChanges: Int = 0,
    val pause: SyncPause? = null,
) {
    companion object {
        /** Nothing has happened yet. */
        val Idle: SyncStatus = SyncStatus()
    }
}
