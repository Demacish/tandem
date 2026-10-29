package casa.moller.tandem.core.sync.di

import casa.moller.tandem.core.data.drink.DrinkRepository
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.sync.drink.AndroidDrinkImageStore
import casa.moller.tandem.core.sync.drink.DrinkImageStore
import casa.moller.tandem.core.sync.drink.DrinkOutboxHandler
import casa.moller.tandem.core.sync.drink.OfflineFirstDrinkRepository
import casa.moller.tandem.core.sync.engine.OutboxHandler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

/**
 * Fas S (drycker lane): drinks and "Passar med" (replaces the app's `TemporaryFasSDrinkModule`)
 * and the sender of the `drink` rows (CREATE and `RECIPE_IMAGE`); the pairings themselves go
 * through the recipe's `RECIPE_KEYWORDS` sender (BookModule). docs/BYGGE.md "Drycker".
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DrinkModule {
    @Binds
    abstract fun bindDrinkRepository(impl: OfflineFirstDrinkRepository): DrinkRepository

    @Binds
    abstract fun bindDrinkImageStore(impl: AndroidDrinkImageStore): DrinkImageStore

    @Binds
    @IntoMap
    @StringKey(OutboxEntities.DRINK)
    abstract fun bindDrinkHandler(impl: DrinkOutboxHandler): OutboxHandler
}
