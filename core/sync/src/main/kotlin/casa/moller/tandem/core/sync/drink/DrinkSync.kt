package casa.moller.tandem.core.sync.drink

import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.model.drink.DrinkKeywords
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.network.api.RecipeListQuery
import casa.moller.tandem.core.network.api.list
import casa.moller.tandem.core.network.paging.Paginator
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.remote.ImageResolver
import casa.moller.tandem.core.sync.remote.RemoteCaller
import casa.moller.tandem.core.sync.remote.toEntity
import casa.moller.tandem.core.sync.remote.toOverviewWrite
import javax.inject.Inject

/**
 * Fas S (drycker lane): "Drycker" pulled to refresh (the regular sync reads the same rows: the
 * keyword list with the small tables, the recipes with the index). Reads every keyword (`GET
 * /api/keyword/`, the tree with "Dryck" and "Passar med") and then the drinks themselves
 * (`GET /api/recipe/?keywords_or=<Dryck>`, children included by default, FACTS "Fas S c"), whose
 * list rows carry their keywords. A user action: not a background call.
 */
internal class DrinkSync @Inject constructor(private val databases: TandemDatabaseFactory, private val caller: RemoteCaller) {
    suspend fun refresh(remote: ActiveSource.Remote): Outcome<Unit> {
        val database = databases.get(remote.kind)
        val keywords = caller.call(remote, background = false) {
            val api = this
            Paginator.fetchAll { page -> api.taxonomy.keywords(page.takeIf { it > 1 }) }
        }
        val read = when (keywords) {
            is Outcome.Failure -> return keywords
            is Outcome.Success -> keywords.value
        }
        if (!caller.isCurrent(remote)) return Outcome.Success(Unit)
        if (read.complete) database.recipeDao().replaceKeywords(remote.accountId, read.items.mapNotNull { it.toEntity(remote.accountId) })
        val roots = read.items.filter { it.parent == null && DrinkKeywords.isDrinkRoot(it.name.ifBlank { it.label }) }.mapNotNull { it.id }
        if (roots.isEmpty()) return Outcome.Success(Unit)
        val drinks = caller.call(remote, background = false) {
            val api = this
            Paginator.fetchAll { page -> api.recipes.list(RecipeListQuery(keywordsOr = roots.toSet()), page) }
        }
        val listed = when (drinks) {
            is Outcome.Failure -> return drinks
            is Outcome.Success -> drinks.value.items
        }
        if (!caller.isCurrent(remote)) return Outcome.Success(Unit)
        val images = ImageResolver.forBaseUrl(remote.baseUrl)
        database.recipeDao().upsertOverviews(remote.accountId, listed.mapNotNull { it.toOverviewWrite(remote.accountId, images) })
        return Outcome.Success(Unit)
    }
}
