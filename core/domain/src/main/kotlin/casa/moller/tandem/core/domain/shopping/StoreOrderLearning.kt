package casa.moller.tandem.core.domain.shopping

import java.util.Collections

/**
 * Fas 4 "Inlärd butiksordning" (plan §8.2, §10 Fas 4 "Klart när"): after [MIN_TRIPS] trips in the
 * same store, the order the categories were checked off in becomes a proposed aisle order, and it
 * is proposed only when at least [MIN_AGREEMENT] of the visited categories lie in that order.
 *
 * A trip is the list of category ids in the order their first entry was checked. The consensus
 * ranks the categories by pairwise majority (for every other category: the share of the trips
 * with both that walked this one first minus the share that walked it after, so a trip that
 * skipped a category does not move it), then by average relative position, then by the store's
 * current order; adjacent swaps that make it agree better with the trips are then applied.
 * [agreement] measures, over all trips, the share of visited categories that lie in the proposed
 * order: per trip the longest run of its categories that keeps the trip's order (a longest common
 * subsequence), summed and divided by the categories visited.
 */
object StoreOrderLearning {

    /** Trips needed before an order is proposed. */
    const val MIN_TRIPS: Int = 3

    /** Fewer categories than this in one trip say nothing about the walking order. */
    const val MIN_CATEGORIES_PER_TRIP: Int = 2

    /** The newest trips that count; older habits fade. */
    const val MAX_TRIPS: Int = 5

    /** The share of visited categories that must lie in the proposed order. */
    const val MIN_AGREEMENT: Double = 0.8

    /**
     * A proposal: [order] (the visited categories in the learned order, then the rest of the
     * store's in its order), [agreement] with the trips, and how many [trips] it is based on.
     */
    data class Proposal(val order: List<Long>, val agreement: Double, val trips: Int)

    /**
     * The order to propose for a store whose categories are [storeOrder] (current order), from
     * [trips] (newest first; only the newest [MAX_TRIPS] with at least [MIN_CATEGORIES_PER_TRIP]
     * of the store's categories count). Null with fewer than [MIN_TRIPS] such trips, when the
     * trips disagree (agreement below [MIN_AGREEMENT]) or when the order is the store's already.
     */
    fun propose(storeOrder: List<Long>, trips: List<List<Long>>): Proposal? {
        val known = storeOrder.toSet()
        val used = trips.asSequence()
            .map { trip -> trip.distinct().filter { it in known } }
            .filter { it.size >= MIN_CATEGORIES_PER_TRIP }
            .take(MAX_TRIPS)
            .toList()
        if (used.size < MIN_TRIPS) return null
        val learned = improved(consensus(used, storeOrder), used)
        val agreement = agreement(learned, used)
        if (agreement < MIN_AGREEMENT) return null
        val order = learned + storeOrder.filterNot { it in learned.toSet() }
        if (order == storeOrder) return null
        return Proposal(order, agreement, used.size)
    }

    /** The categories visited in [trips], in the consensus order (see the class). */
    fun consensus(trips: List<List<Long>>, storeOrder: List<Long>): List<Long> {
        val visited = trips.flatten().distinct()
        val positions = trips.map { trip -> trip.withIndex().associate { (index, id) -> id to index } }
        val score = visited.associateWith { a -> visited.sumOf { b -> if (a == b) 0.0 else preference(a, b, positions) } }
        val averagePosition = visited.associateWith { id ->
            trips.filter { id in it }.map { trip -> if (trip.size == 1) 0.5 else trip.indexOf(id).toDouble() / (trip.size - 1) }.average()
        }
        val storeIndex = storeOrder.withIndex().associate { (index, id) -> id to index }
        return visited.sortedWith(
            compareByDescending<Long> { score.getValue(it) }
                .thenBy { averagePosition.getValue(it) }
                .thenBy { storeIndex[it] ?: Int.MAX_VALUE },
        )
    }

    /**
     * Among the trips that visited both: the share that walked [a] before [b] minus the share that
     * walked [b] before [a] (-1..1; 0 when no trip visited both).
     */
    private fun preference(a: Long, b: Long, positions: List<Map<Long, Int>>): Double {
        val both = positions.filter { a in it && b in it }
        if (both.isEmpty()) return 0.0
        return both.sumOf { pos -> if (pos.getValue(a) < pos.getValue(b)) 1.0 else -1.0 } / both.size
    }

    /** [order] with adjacent swaps applied while each makes it agree better with [trips]. */
    fun improved(order: List<Long>, trips: List<List<Long>>): List<Long> {
        val current = order.toMutableList()
        var best = agreement(current, trips)
        var changed = true
        while (changed) {
            changed = false
            for (i in 0 until current.size - 1) {
                Collections.swap(current, i, i + 1)
                val swapped = agreement(current, trips)
                if (swapped > best) {
                    best = swapped
                    changed = true
                } else {
                    Collections.swap(current, i, i + 1)
                }
            }
        }
        return current
    }

    /** The share (0..1) of the categories visited in [trips] that lie in [order] (see the class). */
    fun agreement(order: List<Long>, trips: List<List<Long>>): Double {
        val rank = order.withIndex().associate { (index, id) -> id to index }
        var inOrder = 0
        var total = 0
        for (trip in trips) {
            val ranks = trip.distinct().mapNotNull { rank[it] }
            total += ranks.size
            inOrder += longestIncreasing(ranks)
        }
        return if (total == 0) 0.0 else inOrder.toDouble() / total
    }

    /** The length of the longest strictly increasing subsequence (patience sorting). */
    private fun longestIncreasing(values: List<Int>): Int {
        val tails = ArrayList<Int>()
        for (value in values) {
            var low = 0
            var high = tails.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (tails[mid] < value) low = mid + 1 else high = mid
            }
            if (low == tails.size) tails.add(value) else tails[low] = value
        }
        return tails.size
    }
}
