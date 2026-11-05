package casa.moller.tandem.core.sync.di

import casa.moller.tandem.core.data.shopping.StoreTripRepository
import casa.moller.tandem.core.sync.shopping.SyncStateStoreTripRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Fas 4 (fas4-kvar lane): the store-mode trips behind "Inlärd butiksordning" (docs/BYGGE.md "Smarta förslag"). */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class StoreTripModule {
    @Binds
    abstract fun bindStoreTripRepository(impl: SyncStateStoreTripRepository): StoreTripRepository
}
