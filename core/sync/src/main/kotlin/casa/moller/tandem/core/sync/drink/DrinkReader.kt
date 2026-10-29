package casa.moller.tandem.core.sync.drink

import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.dao.RecipeKeywordRow
import casa.moller.tandem.core.database.entity.DrinkDraftEntity
import casa.moller.tandem.core.database.entity.KeywordEntity
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.domain.drink.DrinkIndex
import casa.moller.tandem.core.domain.drink.DrinkKeywordNode
import casa.moller.tandem.core.domain.drink.PendingDrink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * Fas S (drycker lane): the [DrinkIndex] of one account from Room (plan §8.8): the cached keywords
 * and recipe-keyword links, the pairing changes still in the outbox (unsent and not failed; they
 * win until they are sent, like "Vill laga") and the drinks created offline (`drink_draft`).
 * [withOutbox] false (demo mode) reads the stored rows only.
 */
internal fun observeDrinkIndex(database: TandemDatabase, accountId: String, withOutbox: Boolean): Flow<DrinkIndex> {
    val books = database.recipeBookDao()
    val outbox = if (withOutbox) books.observeOutbox(accountId) else flowOf(emptyList())
    return combine(
        database.recipeDao().observeKeywords(accountId),
        books.observeRecipeKeywords(accountId),
        outbox,
        database.drinkDraftDao().observeDrafts(accountId),
    ) { keywords, links, queued, drafts -> drinkIndexOf(keywords, links, queued, drafts) }
}

/** The same index read once (a write checks the current state). */
internal suspend fun readDrinkIndex(database: TandemDatabase, accountId: String, withOutbox: Boolean): DrinkIndex {
    val drinks = database.drinkDraftDao()
    val queued = if (withOutbox) database.recipeBookDao().getOutbox(accountId) else emptyList()
    return drinkIndexOf(drinks.getKeywords(accountId), drinks.getRecipeKeywords(accountId), queued, drinks.getDrafts(accountId))
}

internal fun drinkIndexOf(
    keywords: List<KeywordEntity>,
    links: List<RecipeKeywordRow>,
    queued: List<OutboxEntity>,
    drafts: List<DrinkDraftEntity>,
): DrinkIndex = DrinkIndex.build(
    keywords = keywords.map { DrinkKeywordNode(it.id, it.name, it.parentId) },
    links = links.groupBy({ it.recipeId }, { it.keywordId }).mapValues { it.value.toSet() },
    changes = queued
        .filter { it.op == OutboxOps.RECIPE_KEYWORDS && it.state != OutboxState.FAILED }
        .flatMap { row -> row.serverId?.let { DrinkPayloads.pendingChangesOf(it, row.payload) }.orEmpty() },
    pendingDrinks = drafts.map { PendingDrink(it.localId, it.name, DrinkPayloads.decodeDraftKeywords(it.keywords)) },
)
