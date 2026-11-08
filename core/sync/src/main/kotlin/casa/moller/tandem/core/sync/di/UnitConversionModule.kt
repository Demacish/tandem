package casa.moller.tandem.core.sync.di

import casa.moller.tandem.core.data.shopping.ShoppingViewPreferences
import casa.moller.tandem.core.data.shopping.UnitConversionRepository
import casa.moller.tandem.core.sync.shopping.DefaultShoppingViewPreferences
import casa.moller.tandem.core.sync.shopping.SyncStateUnitConversionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Fas 5 (lane handla): merged rows and the grid on the shopping list (docs/BYGGE.md "Sammanslagning och rutnät"). */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class UnitConversionModule {
    @Binds
    abstract fun bindUnitConversionRepository(impl: SyncStateUnitConversionRepository): UnitConversionRepository

    @Binds
    abstract fun bindShoppingViewPreferences(impl: DefaultShoppingViewPreferences): ShoppingViewPreferences
}
