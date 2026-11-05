package casa.moller.tandem.feature.shopping

import casa.moller.tandem.core.domain.shopping.StoreOrderLearning
import casa.moller.tandem.core.model.shopping.Supermarket

// Fas 4 "Inlärd butiksordning": store mode remembers the order the aisles were walked (the
// first check in each category) on every trip, and after three trips in the same store that
// agree (plan §10 Fas 4 "Klart när": at least 80 % of the categories in the order they were
// checked off, core:domain StoreOrderLearning) offers to save it as the store's aisle order in
// Tandoor, where the web shows it too.

/**
 * A new aisle order for [supermarketId]: the aisles walked on the recent trips first, in the
 * learned order, then the rest as before.
 *
 * @property relationIds the `SupermarketCategoryRelation` ids in the new order (what
 *   [casa.moller.tandem.core.data.shopping.SupermarketRepository.reorderAisles] takes).
 * @property names the aisles' names in the new order, for the card.
 * @property trips how many trips (this one included) the order is learned from.
 */
data class LearnedOrder(
    val supermarketId: Long,
    val relationIds: List<Long>,
    val names: List<String>,
    val trips: Int = StoreOrderLearning.MIN_TRIPS,
)

internal object LearnedAisleOrder {

    /**
     * The order to offer in [store] after [trips] (each the visited category ids, first check
     * first; newest trip first, this one included), or null when there is no store, fewer than
     * three trips walked at least two of its aisles, the trips disagree, or the learned order is
     * the store's already.
     */
    fun of(store: Supermarket?, trips: List<List<Long>>): LearnedOrder? {
        if (store == null) return null
        val aisles = store.aisles.sortedBy { it.order }
        val first = aisles.distinctBy { it.category.id }
        val proposal = StoreOrderLearning.propose(first.map { it.category.id }, trips) ?: return null
        val byCategory = first.associateBy { it.category.id }
        val learned = proposal.order.mapNotNull { byCategory[it] }
        val reordered = learned + (aisles - learned.toSet())
        if (reordered.map { it.relationId } == aisles.map { it.relationId }) return null
        return LearnedOrder(store.id, reordered.map { it.relationId }, reordered.map { it.category.name }, proposal.trips)
    }
}
