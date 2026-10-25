package casa.moller.tandem.core.domain.shopping

import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The window rule of the shopping list (plan 3.5; FACTS "Fas 2", observed in 2.6.13 and 2.7.1):
 * Tandoor's list returns unchecked entries and entries checked since midnight (UTC) of the day
 * `min(shopping_recent_days, 14)` days ago, at most 1000. A full re-read therefore cannot tell
 * "deleted on another device" from "checked long ago and aged out" on its own. Pure; the sync's
 * full re-read asks [missingEntry] for every stored entry the response lacks.
 */
object ShoppingWindowRule {
    /** Tandoor caps `shopping_recent_days` at 14 for the list (`api.py`, ShoppingListEntryViewSet). */
    const val MAX_RECENT_DAYS: Int = 14

    /** The list returns at most this many entries; a response that reaches it detects no deletions. */
    const val SERVER_LIMIT: Int = 1000

    /** What a stored entry that is missing from a full re-read means. */
    enum class Verdict {
        /** Keep it as it is (not on the server yet, unsent changes, or the response was incomplete). */
        KEEP,

        /** Deleted elsewhere: remove it locally (and drop its unsent outbox rows, plan 3.5). */
        DELETED,

        /** Checked before the window: move it to `shopping_history` (plan 8.2), do not delete it. */
        AGED_OUT,
    }

    /** The oldest `completed_at` the list still returns: midnight UTC, [recentDays] (max 14) days back. */
    fun windowStart(now: Instant, recentDays: Int): Instant {
        val days = recentDays.coerceIn(0, MAX_RECENT_DAYS)
        val midnight = Instant.fromEpochSeconds(now.epochSeconds - now.epochSeconds.mod(SECONDS_PER_DAY))
        return midnight - days.days
    }

    /** Deletions can be detected only from a complete response below [SERVER_LIMIT] entries. */
    fun canDetectDeletions(returned: Int, complete: Boolean): Boolean = complete && returned < SERVER_LIMIT

    /**
     * A stored entry missing from a full re-read. [onServer] = it has a server id; [hasUnsentChanges]
     * = the outbox still holds a change of it. An unchecked entry was deleted; a checked one was
     * deleted only when it was completed at least one day inside the window (plan 3.5 "med en dags
     * marginal"), otherwise it aged out. A checked entry without `completed_at` counts as deleted.
     */
    fun missingEntry(
        onServer: Boolean,
        hasUnsentChanges: Boolean,
        checked: Boolean,
        completedAt: Instant?,
        now: Instant,
        recentDays: Int,
    ): Verdict = when {
        !onServer || hasUnsentChanges -> Verdict.KEEP
        !checked || completedAt == null -> Verdict.DELETED
        completedAt >= windowStart(now, recentDays) + 1.days -> Verdict.DELETED
        else -> Verdict.AGED_OUT
    }

    private const val SECONDS_PER_DAY = 86_400L
}
