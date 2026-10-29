package casa.moller.tandem.core.sync.di

import casa.moller.tandem.core.data.pantry.MakeNowRepository
import casa.moller.tandem.core.data.pantry.PantryRepository
import casa.moller.tandem.core.data.pantry.StaplesRepository
import casa.moller.tandem.core.data.scan.BarcodeLookup
import casa.moller.tandem.core.data.scan.FoodAliasRepository
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.sync.engine.OutboxHandler
import casa.moller.tandem.core.sync.pantry.DefaultMakeNowRepository
import casa.moller.tandem.core.sync.pantry.DefaultStaplesRepository
import casa.moller.tandem.core.sync.pantry.InventoryEntryOutboxHandler
import casa.moller.tandem.core.sync.pantry.InventoryLocationOutboxHandler
import casa.moller.tandem.core.sync.pantry.OfflineFirstPantryRepository
import casa.moller.tandem.core.sync.pantry.PantryNames
import casa.moller.tandem.core.sync.pantry.ResourcePantryNames
import casa.moller.tandem.core.sync.scan.DefaultBarcodeLookup
import casa.moller.tandem.core.sync.scan.FoodAliasOutboxHandler
import casa.moller.tandem.core.sync.scan.OfflineFirstFoodAliasRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

/**
 * Fas S (hemma-data lane): the pantry's data layer (replaces the app's `TemporaryFasSPantryModule`
 * and `TemporaryFasSScanModule`): "Hemma", staples, "Kan laga nu", learned aliases and the Open
 * Food Facts lookup, and the outbox senders of `inventory_location`, `inventory_entry` and
 * `food_alias` (the `food` sender, `FoodOutboxHandler`, also sends `BATCH_ON_HAND`). docs/BYGGE.md
 * "Skafferiet – data och synk".
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class PantryModule {
    @Binds
    abstract fun bindPantryRepository(impl: OfflineFirstPantryRepository): PantryRepository

    @Binds
    abstract fun bindStaplesRepository(impl: DefaultStaplesRepository): StaplesRepository

    @Binds
    abstract fun bindMakeNowRepository(impl: DefaultMakeNowRepository): MakeNowRepository

    @Binds
    abstract fun bindFoodAliasRepository(impl: OfflineFirstFoodAliasRepository): FoodAliasRepository

    @Binds
    abstract fun bindBarcodeLookup(impl: DefaultBarcodeLookup): BarcodeLookup

    @Binds
    abstract fun bindPantryNames(impl: ResourcePantryNames): PantryNames

    @Binds
    @IntoMap
    @StringKey(OutboxEntities.INVENTORY_LOCATION)
    abstract fun bindInventoryLocationHandler(impl: InventoryLocationOutboxHandler): OutboxHandler

    @Binds
    @IntoMap
    @StringKey(OutboxEntities.INVENTORY_ENTRY)
    abstract fun bindInventoryEntryHandler(impl: InventoryEntryOutboxHandler): OutboxHandler

    @Binds
    @IntoMap
    @StringKey(OutboxEntities.FOOD_ALIAS)
    abstract fun bindFoodAliasHandler(impl: FoodAliasOutboxHandler): OutboxHandler
}
