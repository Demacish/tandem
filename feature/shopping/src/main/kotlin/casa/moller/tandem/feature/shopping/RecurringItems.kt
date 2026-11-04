package casa.moller.tandem.feature.shopping

import casa.moller.tandem.core.model.shopping.ShoppingHistoryItem
import kotlin.time.Instant

// Fas 4 "Återkommande varor": the add panel's "Dags igen?" chips, from the local history. A
// local rule only: nothing is sent anywhere and nothing is stored beyond the history itself.

internal object RecurringItems {

    /** Fewer purchases than this say nothing about a rhythm. */
    const val MIN_PURCHASES: Int = 3

    /** A food bought more often than every other day is not "recurring", it is staple shopping. */
    const val MIN_INTERVAL_DAYS: Int = 2

    /** Rhythms longer than this are too vague to remind about. */
    const val MAX_INTERVAL_DAYS: Int = 60

    /** A food is due when this share of its usual interval has passed since the last purchase. */
    private const val DUE_SHARE = 0.8

    private const val MILLIS_PER_DAY = 86_400_000L

    /**
     * The foods bought regularly (at least [MIN_PURCHASES] times, on distinct days, with a typical
     * interval of [MIN_INTERVAL_DAYS]..[MAX_INTERVAL_DAYS] days) that are due again at [now]: at
     * least [DUE_SHARE] of the typical interval has passed since the last purchase. Foods in
     * [onList] (the list's entries, bought or not) are left out. The most overdue first, at most
     * [limit], named as they were last written.
     */
    fun due(history: List<ShoppingHistoryItem>, onList: Collection<String>, now: Instant, limit: Int): List<String> {
        val excluded = onList.map { it.trim().lowercase() }.toSet()
        val today = now.toEpochMilliseconds().floorDiv(MILLIS_PER_DAY)
        return history
            .filter { it.foodName.isNotBlank() && it.completedAt != null }
            .groupBy { it.foodName.trim().lowercase() }
            .filterKeys { it !in excluded }
            .mapNotNull { (_, items) -> rhythmOf(items, today) }
            .sortedByDescending { it.overdue }
            .take(limit)
            .map { it.name }
    }

    private class Rhythm(val name: String, val overdue: Double)

    private fun rhythmOf(items: List<ShoppingHistoryItem>, today: Long): Rhythm? {
        val days = items.mapNotNull { it.completedAt?.toEpochMilliseconds()?.floorDiv(MILLIS_PER_DAY) }.distinct().sorted()
        if (days.size < MIN_PURCHASES) return null
        val intervals = days.zipWithNext { a, b -> (b - a).toDouble() }.sorted()
        val typical = intervals[intervals.size / 2]
        if (typical < MIN_INTERVAL_DAYS || typical > MAX_INTERVAL_DAYS) return null
        val overdue = (today - days.last()) / typical
        if (overdue < DUE_SHARE) return null
        val latest = items.maxBy { it.completedAt!! }
        return Rhythm(latest.foodName.trim(), overdue)
    }
}
