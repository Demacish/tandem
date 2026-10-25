package casa.moller.tandem.core.data.shopping

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.shopping.Supermarket
import casa.moller.tandem.core.model.shopping.SupermarketCategory
import kotlinx.coroutines.flow.Flow

/**
 * Stores, aisles and foods' aisles (feature #10, plan 6.2 "Chips för listor och butiker"). Small
 * tables, re-read in full by the sync (plan 3.5). Same contract as [ShoppingRepository]
 * (main-safe, account-scoped, demo, read-only for guests, offline writes queued).
 */
interface SupermarketRepository {
    /** The stores with their aisle order, by name. */
    fun observeSupermarkets(): Flow<List<Supermarket>>

    /** Every aisle / category, by name. */
    fun observeCategories(): Flow<List<SupermarketCategory>>

    /**
     * The store the list is sorted by on this device (a per-device choice, remembered), null =
     * by category name.
     */
    fun observeSelectedSupermarket(): Flow<Long?>

    suspend fun selectSupermarket(id: Long?): Outcome<Unit>

    /**
     * Puts a food in an aisle ("Övrigt" → the right category; plan 6.2: the app remembers it):
     * `PATCH /api/food/{id}/` `supermarket_category`, for the whole space. Null removes it.
     */
    suspend fun setFoodCategory(foodId: Long, categoryId: Long?): Outcome<Unit>

    /** Marks a food "har hemma" or not (`food_onhand`, per household). */
    suspend fun setFoodOnHand(foodId: Long, onHand: Boolean): Outcome<Unit>

    /** A store's new aisle order (relation ids in walking order): `PATCH .../supermarket-category-relation/{id}/` `order`. */
    suspend fun reorderAisles(supermarketId: Long, relationIdsInOrder: List<Long>): Outcome<Unit>
}
