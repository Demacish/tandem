package casa.moller.tandem.wear.sync

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.network.api.TandoorApi
import casa.moller.tandem.core.network.api.TandoorApiFactory
import casa.moller.tandem.core.network.dto.ShoppingBulkCheckDto
import casa.moller.tandem.core.network.dto.ShoppingListEntryDto
import casa.moller.tandem.core.network.dto.SupermarketDto
import casa.moller.tandem.core.network.error.apiCall
import casa.moller.tandem.core.network.http.TokenProvider
import casa.moller.tandem.core.network.json.TandoorDates
import casa.moller.tandem.core.network.paging.Paginator
import casa.moller.tandem.core.watchlink.WatchCredentials
import casa.moller.tandem.wear.data.WearEntry
import casa.moller.tandem.wear.data.WearSupermarket

/**
 * What the watch asks Tandoor (plan §7 #33): the same endpoints as the phone's Handla, with the
 * watch's own token. Every call is one [Outcome]; nothing is retried here (the [WearGuard] decides).
 */
interface ShoppingRemote {
    /** The whole list (`GET /api/shopping-list-entry/`, every page). An incomplete read is a failure. */
    suspend fun entries(): Outcome<List<WearEntry>>

    /** The stores with their aisle order (`GET /api/supermarket/`). */
    suspend fun supermarkets(): Outcome<List<WearSupermarket>>

    /** `POST /api/shopping-list-entry/bulk/` `{ids, checked}`: deleted ids are ignored by the server. */
    suspend fun setChecked(ids: List<Long>, checked: Boolean): Outcome<Unit>

    /** `DELETE /api/access-token/{id}/` with the token itself (a token may delete itself, FACTS c). */
    suspend fun revoke(tokenId: Long): Outcome<Unit>

    /** Builds a [ShoppingRemote] for the stored credentials. */
    fun interface Factory {
        fun create(credentials: WatchCredentials): ShoppingRemote
    }
}

/** [ShoppingRemote] on core:network's Retrofit services (HTTPS only, the shared rate limit). */
class TandoorShoppingRemote(private val api: TandoorApi) : ShoppingRemote {
    override suspend fun entries(): Outcome<List<WearEntry>> {
        val read = apiCall {
            Paginator.fetchAll(MAX_PAGES) { page -> api.shopping.entries(updatedAfter = null, page = page.takeIf { it > 1 }) }
        }
        return when (read) {
            is Outcome.Failure -> read
            is Outcome.Success ->
                if (read.value.complete) {
                    Outcome.Success(read.value.items.mapNotNull(::toEntry))
                } else {
                    Outcome.Failure(TandemError.Decode("shopping list: incomplete read"))
                }
        }
    }

    override suspend fun supermarkets(): Outcome<List<WearSupermarket>> {
        val read = apiCall {
            Paginator.fetchAll(MAX_PAGES) { page -> api.supermarkets.supermarkets(page = page.takeIf { it > 1 }) }
        }
        return when (read) {
            is Outcome.Failure -> read
            is Outcome.Success -> Outcome.Success(read.value.items.mapNotNull(::toSupermarket))
        }
    }

    override suspend fun setChecked(ids: List<Long>, checked: Boolean): Outcome<Unit> =
        apiCall { api.shopping.bulkCheck(ShoppingBulkCheckDto(ids = ids, checked = checked)) }.let { result ->
            when (result) {
                is Outcome.Failure -> result
                is Outcome.Success -> Outcome.Success(Unit)
            }
        }

    override suspend fun revoke(tokenId: Long): Outcome<Unit> = apiCall { api.auth.deleteAccessToken(tokenId) }

    companion object {
        /** The list holds at most 1000 entries (FACTS "Fas 2 b"), 100 per page. */
        private const val MAX_PAGES = 20

        /** A [ShoppingRemote.Factory] on [apiFactory]: one Retrofit set per pairing. */
        fun factory(apiFactory: TandoorApiFactory): ShoppingRemote.Factory = ShoppingRemote.Factory { credentials ->
            TandoorShoppingRemote(apiFactory.create(credentials.baseUrl, TokenProvider { credentials.token }))
        }

        internal fun toEntry(dto: ShoppingListEntryDto): WearEntry? {
            val id = dto.id ?: return null
            val food = dto.food ?: return null
            if (food.name.isBlank()) return null
            return WearEntry(
                id = id,
                food = food.name.trim(),
                foodPlural = food.pluralName?.takeIf { it.isNotBlank() },
                foodId = food.id,
                amount = dto.amount,
                unit = dto.unit?.name?.takeIf { it.isNotBlank() },
                unitPlural = dto.unit?.pluralName?.takeIf { it.isNotBlank() },
                unitId = dto.unit?.id,
                categoryId = food.supermarketCategory?.id,
                categoryName = food.supermarketCategory?.name?.takeIf { it.isNotBlank() },
                checked = dto.checked,
                order = dto.order,
                recipeName = dto.listRecipeData?.recipeData?.name?.takeIf { it.isNotBlank() }
                    ?: dto.listRecipeData?.name?.takeIf { it.isNotBlank() },
                delayedUntilMillis = TandoorDates.parseInstantOrNull(dto.delayUntil)?.toEpochMilli(),
            )
        }

        internal fun toSupermarket(dto: SupermarketDto): WearSupermarket? {
            val id = dto.id ?: return null
            val order = dto.categoryToSupermarket.sortedBy { it.order }.mapNotNull { it.category?.id }
            return WearSupermarket(id = id, name = dto.name.trim(), categoryOrder = order)
        }
    }
}
