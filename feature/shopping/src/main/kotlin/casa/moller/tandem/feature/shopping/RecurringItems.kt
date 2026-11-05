package casa.moller.tandem.feature.shopping

import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingHistoryItem
import kotlin.time.Instant

// Fas 4 "Återkommande varor": the add panel's "Dags igen?" chips, from the local history. A
// local rule only: nothing is sent anywhere and nothing is stored beyond the history itself.
// Plan §10 Fas 4 "Klart när": foods bought at least 3 times in 6 weeks are proposed with the right
// interval ±3 days, also after the server's 14-day window has passed (the local history, §8.2).

/**
 * A food bought regularly.
 *
 * @property intervalDays the typical number of days between two purchases (the median interval).
 * @property lastBoughtDay the last purchase, in days since the epoch (UTC).
 * @property overdue the days since the last purchase divided by [intervalDays].
 */
internal data class Rhythm(val name: String, val intervalDays: Double, val lastBoughtDay: Long, val overdue: Double)

internal object RecurringItems {

    /** Fewer purchases than this say nothing about a rhythm. */
    const val MIN_PURCHASES: Int = 3

    /** A food bought more often than every other day is not "recurring", it is staple shopping. */
    const val MIN_INTERVAL_DAYS: Int = 2

    /** Rhythms longer than this are too vague to remind about. */
    const val MAX_INTERVAL_DAYS: Int = 60

    /** A food is due when this share of its usual interval has passed since the last purchase. */
    private const val DUE_SHARE = 0.8

    /** A rhythm missed this many times over has stopped (the food is no longer bought). */
    private const val STOPPED_SHARE = 3.0

    private const val MILLIS_PER_DAY = 86_400_000L

    /**
     * Every purchase the phone knows of: the archived history (aged out of Tandoor's window, plan
     * §3.5) plus the entries still on the list that are checked (inside the window, not archived
     * yet). Without the second part the newest purchases would be missing and the rhythm wrong.
     */
    fun purchases(history: List<ShoppingHistoryItem>, entries: List<ShoppingEntry>): List<ShoppingHistoryItem> {
        val archived = history.mapTo(HashSet()) { it.entryId }
        val onList = entries.filter { it.checked && it.completedAt != null && it.id !in archived }
            .map { ShoppingHistoryItem(entryId = it.id, foodName = it.food.name, foodId = it.food.id, completedAt = it.completedAt) }
        return history + onList
    }

    /**
     * The foods bought regularly (at least [MIN_PURCHASES] times, on distinct days, with a typical
     * interval of [MIN_INTERVAL_DAYS]..[MAX_INTERVAL_DAYS] days) that are due again at [now]: at
     * least [DUE_SHARE] of the typical interval has passed since the last purchase, and fewer than
     * [STOPPED_SHARE] intervals. Foods in [onList] (the list's unchecked entries) are left out. The
     * most overdue first, at most [limit], named as they were last written.
     */
    fun due(history: List<ShoppingHistoryItem>, onList: Collection<String>, now: Instant, limit: Int): List<String> {
        val excluded = onList.map { it.trim().lowercase() }.toSet()
        return rhythms(history, now)
            .filter { it.name.lowercase() !in excluded && it.overdue >= DUE_SHARE && it.overdue < STOPPED_SHARE }
            .sortedByDescending { it.overdue }
            .take(limit)
            .map { it.name }
    }

    /** "Dags igen?" from the history and the list: [purchases], without the unchecked entries' foods. */
    fun dueFor(history: List<ShoppingHistoryItem>, entries: List<ShoppingEntry>, now: Instant, limit: Int): List<String> =
        due(purchases(history, entries), entries.filterNot { it.checked }.map { it.food.name }, now, limit)

    /** The rhythm of every food with one (see [due]), in no particular order. */
    fun rhythms(history: List<ShoppingHistoryItem>, now: Instant): List<Rhythm> {
        val today = now.toEpochMilliseconds().floorDiv(MILLIS_PER_DAY)
        return history
            .filter { it.foodName.isNotBlank() && it.completedAt != null }
            .groupBy { it.foodName.trim().lowercase() }
            .mapNotNull { (_, items) -> rhythmOf(items, today) }
    }

    private fun rhythmOf(items: List<ShoppingHistoryItem>, today: Long): Rhythm? {
        val days = items.mapNotNull { it.completedAt?.toEpochMilliseconds()?.floorDiv(MILLIS_PER_DAY) }.distinct().sorted()
        if (days.size < MIN_PURCHASES) return null
        val intervals = days.zipWithNext { a, b -> (b - a).toDouble() }.sorted()
        val middle = intervals.size / 2
        val typical = if (intervals.size % 2 == 1) intervals[middle] else (intervals[middle - 1] + intervals[middle]) / 2
        if (typical < MIN_INTERVAL_DAYS || typical > MAX_INTERVAL_DAYS) return null
        val latest = items.maxBy { it.completedAt!! }
        return Rhythm(latest.foodName.trim(), typical, days.last(), (today - days.last()) / typical)
    }
}
