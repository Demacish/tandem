package casa.moller.tandem.feature.shopping

import casa.moller.tandem.core.model.shopping.Supermarket

// Fas 4 "Inlärd butiksordning": store mode remembers the order the aisles were walked (the
// first check in each category) and offers to save it as the store's aisle order in Tandoor.

/**
 * A new aisle order for [supermarketId]: the aisles visited on this trip first, in the order they
 * were visited, then the rest as before.
 *
 * @property relationIds the `SupermarketCategoryRelation` ids in the new order (what
 *   [casa.moller.tandem.core.data.shopping.SupermarketRepository.reorderAisles] takes).
 * @property names the aisles' names in the new order, for the card.
 */
data class LearnedOrder(val supermarketId: Long, val relationIds: List<Long>, val names: List<String>)

internal object LearnedAisleOrder {

    /** Fewer visited aisles than this says nothing about the walking order. */
    const val MIN_VISITED: Int = 2

    /**
     * The order to offer after visiting the aisles [visitedCategoryIds] (first check first) in
     * [store], or null when there is no store, fewer than [MIN_VISITED] of its aisles were visited,
     * or the visited aisles already come first in the order they were visited.
     */
    fun of(store: Supermarket?, visitedCategoryIds: List<Long>): LearnedOrder? {
        if (store == null) return null
        val aisles = store.aisles.sortedBy { it.order }
        val byCategory = aisles.associateBy { it.category.id }
        val visited = visitedCategoryIds.distinct().mapNotNull { byCategory[it] }
        if (visited.size < MIN_VISITED) return null
        val reordered = visited + (aisles - visited.toSet())
        if (reordered.map { it.relationId } == aisles.map { it.relationId }) return null
        return LearnedOrder(store.id, reordered.map { it.relationId }, reordered.map { it.category.name })
    }
}
