package casa.moller.tandem.core.sync.demo

import casa.moller.tandem.core.data.demo.DemoContent
import casa.moller.tandem.core.data.demo.DemoLanguage
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.entity.FoodEntity
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.sync.account.DEMO_ACCOUNT_ID
import casa.moller.tandem.core.sync.shopping.relationEntities
import casa.moller.tandem.core.sync.shopping.toEntity
import java.util.Locale
import kotlinx.datetime.LocalDate

/**
 * Phase 2 (inköp-data lane): the demo shopping list (plan 6.2 "Demoläge"): two named lists, two
 * stores with their own aisle order, seven aisles and a realistic list (manual entries from two
 * household members, checked, delayed, and tomorrow's dinner from the meal plan), through the same
 * DTO → row mappers as a real server. Never the network.
 *
 * Seeded once per content version and language (`sync_state` [SEED_KEY]), not every day like the
 * recipes: what the user checks, adds or removes in demo mode stays until "Återställ demodata"
 * (which empties the database) or a language change.
 */
internal object DemoShopping {
    const val SEED_KEY: String = "demo_shopping_seed"

    suspend fun ensureSeeded(database: TandemDatabase, content: DemoContent, locale: Locale, today: LocalDate, now: Long) {
        val accountId = DEMO_ACCOUNT_ID
        val key = "v" + content.version + "|" + DemoLanguage.of(locale).tag
        val states = database.syncStateDao()
        if (states.get(accountId, SEED_KEY)?.hash == key) return
        val shopping = database.shoppingDao()
        val stores = content.supermarkets(locale)
        val entries = content.shoppingEntries(locale, today)
        shopping.inTransaction {
            shopping.deleteEntries(accountId, shopping.getEntries(accountId).map { it.localId })
            shopping.deleteListRecipes(accountId, shopping.getListRecipes(accountId).map { it.localId })
            shopping.replaceShoppingLists(accountId, content.shoppingLists(locale).mapNotNull { it.toEntity(accountId) })
            database.supermarketDao().replaceAll(
                accountId,
                supermarkets = stores.mapNotNull { it.toEntity(accountId) },
                categories = content.supermarketCategories(locale).mapNotNull { it.toEntity(accountId) },
                relations = stores.flatMap { it.relationEntities(accountId) },
            )
            shopping.upsertEntries(entries.mapNotNull { it.toEntity(accountId, now) })
            shopping.upsertListRecipes(entries.mapNotNull { it.listRecipeData?.toEntity(accountId) }.distinctBy { it.localId })
            // The demo has no food list of its own: the list's foods are its foods ("har hemma", aisles, suggestions).
            database.referenceDao().replaceFoods(
                accountId,
                entries.mapNotNull { entry ->
                    val food = entry.food ?: return@mapNotNull null
                    val id = food.id ?: return@mapNotNull null
                    FoodEntity(
                        accountId = accountId,
                        id = id,
                        name = food.name,
                        pluralName = food.pluralName,
                        supermarketCategoryId = food.supermarketCategory?.id,
                    )
                }.distinctBy { it.id },
            )
            states.upsert(SyncStateEntity(accountId = accountId, entity = SEED_KEY, hash = key, lastFullRelistEpochMillis = now))
        }
    }
}
