package casa.moller.tandem.core.sync.di

import casa.moller.tandem.core.data.shopping.IngredientParser
import casa.moller.tandem.core.data.shopping.ShoppingRepository
import casa.moller.tandem.core.data.shopping.SupermarketRepository
import casa.moller.tandem.core.data.sync.ForegroundSync
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.sync.engine.OutboxHandler
import casa.moller.tandem.core.sync.shopping.AisleOrderOutboxHandler
import casa.moller.tandem.core.sync.shopping.DefaultForegroundSync
import casa.moller.tandem.core.sync.shopping.DefaultIngredientParser
import casa.moller.tandem.core.sync.shopping.DefaultSupermarketRepository
import casa.moller.tandem.core.sync.shopping.FoodOutboxHandler
import casa.moller.tandem.core.sync.shopping.OfflineFirstShoppingRepository
import casa.moller.tandem.core.sync.shopping.ShoppingEntryOutboxHandler
import casa.moller.tandem.core.sync.shopping.ShoppingListRecipeOutboxHandler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

/**
 * Phase 2 (inköp-data lane): the shopping list's data layer (replaces the app's
 * `TemporaryFas2ShoppingModule` and `TemporaryFas2SyncModule`): the repositories, the parser, the
 * foreground polling and the outbox senders of `shopping_entry`, `shopping_list_recipe`, `food`
 * and `supermarket_category_relation` (docs/BYGGE.md "Inköpslistan – data och synk").
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ShoppingModule {
    @Binds
    abstract fun bindShoppingRepository(impl: OfflineFirstShoppingRepository): ShoppingRepository

    @Binds
    abstract fun bindSupermarketRepository(impl: DefaultSupermarketRepository): SupermarketRepository

    @Binds
    abstract fun bindIngredientParser(impl: DefaultIngredientParser): IngredientParser

    @Binds
    abstract fun bindForegroundSync(impl: DefaultForegroundSync): ForegroundSync

    @Binds
    @IntoMap
    @StringKey(OutboxEntities.SHOPPING_ENTRY)
    abstract fun bindShoppingEntryHandler(impl: ShoppingEntryOutboxHandler): OutboxHandler

    @Binds
    @IntoMap
    @StringKey(OutboxEntities.SHOPPING_LIST_RECIPE)
    abstract fun bindShoppingListRecipeHandler(impl: ShoppingListRecipeOutboxHandler): OutboxHandler

    @Binds
    @IntoMap
    @StringKey(OutboxEntities.FOOD)
    abstract fun bindFoodHandler(impl: FoodOutboxHandler): OutboxHandler

    @Binds
    @IntoMap
    @StringKey(OutboxEntities.SUPERMARKET_CATEGORY_RELATION)
    abstract fun bindAisleOrderHandler(impl: AisleOrderOutboxHandler): OutboxHandler
}
