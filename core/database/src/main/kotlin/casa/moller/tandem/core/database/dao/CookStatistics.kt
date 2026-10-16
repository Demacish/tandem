package casa.moller.tandem.core.database.dao

import kotlin.math.floor

/**
 * One cook-log entry as the statistics see it.
 *
 * @property own whether the entry is the logged-in user's (only those ratings count, FACTS "Fas 1 d").
 */
data class CookLogFact(val recipeId: Long, val cookedAtEpochMillis: Long?, val rating: Int?, val own: Boolean)

/**
 * The one place that turns cook-log entries into "times cooked", "last cooked" and the user's own
 * rating (plan 3.3; FACTS "Fas 1 c/d"), used for the server's list (`SyncEngine`), the entries in
 * the `cook_log` table ([CookLogDao]) and the demo content, so every path rounds the same way.
 */
object CookStatistics {
    /** Statistics per recipe of [facts]; recipes without entries are not in the result. */
    fun of(facts: List<CookLogFact>): List<CookStatsRow> = facts
        .groupBy { it.recipeId }
        .map { (recipeId, entries) ->
            CookStatsRow(
                recipeId = recipeId,
                timesCooked = entries.size,
                lastCookedEpochMillis = entries.mapNotNull { it.cookedAtEpochMillis }.maxOrNull(),
                rating = ownRating(entries.filter { it.own }.mapNotNull { it.rating }),
            )
        }

    /**
     * The average of the user's own [ratings] rounded half up to whole stars, as the server
     * rounds the recipe's rating; null when there is none.
     */
    fun ownRating(ratings: List<Int>): Double? = ratings.takeIf { it.isNotEmpty() }?.let { floor(it.average() + HALF) }

    private const val HALF = 0.5
}
