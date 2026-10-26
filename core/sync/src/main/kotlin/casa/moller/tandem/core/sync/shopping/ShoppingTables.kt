package casa.moller.tandem.core.sync.shopping

import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.network.api.TandoorApi
import casa.moller.tandem.core.network.dto.PageDto
import casa.moller.tandem.core.network.paging.PagedResult
import casa.moller.tandem.core.network.paging.Paginator
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.account.identity
import casa.moller.tandem.core.sync.remote.RemoteCaller
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The shopping list's small tables (plan 3.5 "Små tabeller": full re-read with the other small
 * tables, at start at most once an hour and every 6 hours): the named lists, the stores with their
 * aisle order, the aisles, and the server's `shopping_min_autosync_interval` (a STRING, FACTS
 * "Fas 2 a"), the floor of the list's polling. A list that was not read completely replaces
 * nothing. Changes still queued here (a food's aisle or "har hemma", a new aisle order) are applied
 * again on top of the fresh rows, so the screen does not jump back before they are sent.
 */
@Singleton
internal class ShoppingTables @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val sources: ActiveSources,
) {
    suspend fun sync(remote: ActiveSource.Remote, background: Boolean) {
        val database = databases.get(remote.kind)
        val accountId = remote.accountId
        val lists = fetchAll(remote, background) { page -> shopping.lists(page) }
        if (!sameSource(remote)) return
        if (lists is Outcome.Success && lists.value.complete) {
            database.shoppingDao().replaceShoppingLists(accountId, lists.value.items.mapNotNull { it.toEntity(accountId) })
        }
        val stores = fetchAll(remote, background) { page -> supermarkets.supermarkets(page) }
        val categories = fetchAll(remote, background) { page -> supermarkets.categories(page) }
        if (!sameSource(remote)) return
        val storeList = (stores as? Outcome.Success)?.value?.takeIf { it.complete }?.items
        val categoryList = (categories as? Outcome.Success)?.value?.takeIf { it.complete }?.items
        if (storeList != null && categoryList != null) {
            database.supermarketDao().replaceAll(
                accountId,
                supermarkets = storeList.mapNotNull { it.toEntity(accountId) },
                categories = categoryList.mapNotNull { it.toEntity(accountId) },
                relations = storeList.flatMap { it.relationEntities(accountId) },
            )
        }
        val settings = caller.call(remote, background) { server.serverSettings() }
        if (settings is Outcome.Success && sameSource(remote)) {
            database.shoppingDao().setMinAutosyncSeconds(accountId, settings.value.shoppingMinAutosyncIntervalOrNull())
        }
        reapplyQueued(database, accountId)
    }

    /**
     * Applies the queued `food` and `supermarket_category_relation` PATCHes to the stored rows again
     * (after the engine re-read the foods, or after the stores were replaced above).
     */
    suspend fun reapplyQueued(database: TandemDatabase, accountId: String) {
        val outbox = database.outboxDao()
        val stores = database.supermarketDao()
        val shopping = database.shoppingDao()
        shopping.inTransaction {
            reapplyQueuedFoods(database, accountId)
            outbox.getAllFor(accountId, OutboxEntities.SUPERMARKET_CATEGORY_RELATION).forEach { row ->
                val order = (parse(row.payload)?.get("order") as? JsonPrimitive)?.intOrNull
                val relationId = row.serverId
                if (relationId != null && order != null) stores.setRelationOrder(accountId, relationId, order)
            }
        }
    }

    private suspend fun <T> fetchAll(
        remote: ActiveSource.Remote,
        background: Boolean,
        fetch: suspend TandoorApi.(page: Int?) -> PageDto<T>,
    ): Outcome<PagedResult<T>> = caller.call(remote, background) {
        val api = this
        Paginator.fetchAll { page -> api.fetch(page.takeIf { it > 1 }) }
    }

    private fun sameSource(remote: ActiveSource.Remote): Boolean = sources.source.value.identity == remote.identity

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private fun parse(payload: String): JsonObject? = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject

        /**
         * The queued `food` PATCHes (aisle, "har hemma") on the stored food and its entries, inside
         * the caller's transaction. Also run after every list read (0.3.0 review, fix-data), whose
         * entries carry the server's old aisle until the change is sent.
         */
        suspend fun reapplyQueuedFoods(database: TandemDatabase, accountId: String) {
            val stores = database.supermarketDao()
            val shopping = database.shoppingDao()
            for (row in database.outboxDao().getAllFor(accountId, OutboxEntities.FOOD)) {
                val foodId = row.serverId
                val body = parse(row.payload)
                if (foodId == null || body == null) continue
                (body["food_onhand"] as? JsonPrimitive)?.booleanOrNull?.let { stores.setFoodOnHand(accountId, foodId, it) }
                categoryOf(body)?.let { categoryId ->
                    stores.setFoodCategory(accountId, foodId, categoryId.value)
                    shopping.setCategoryOfFood(accountId, foodId, categoryId.value)
                }
            }
        }

        /** The aisle a `food` PATCH body sets (`supermarket_category`: `{id, name}` or null), null when it sets none. */
        fun categoryOf(body: JsonObject): AisleId? {
            if ("supermarket_category" !in body) return null
            val category = body["supermarket_category"]
            return AisleId(((category as? JsonObject)?.get("id") as? JsonPrimitive)?.longOrNull)
        }
    }

    /** An aisle a food is moved to; [value] null = no aisle. */
    data class AisleId(val value: Long?)
}
