package casa.moller.tandem.core.watch.di

import casa.moller.tandem.core.data.account.AccountRemovalHook
import casa.moller.tandem.core.data.watch.WatchRepository
import casa.moller.tandem.core.watch.DataLayer
import casa.moller.tandem.core.watch.DataStoreWatchPairingStore
import casa.moller.tandem.core.watch.DefaultWatchRepository
import casa.moller.tandem.core.watch.PlayDataLayer
import casa.moller.tandem.core.watch.TandoorWatchTokens
import casa.moller.tandem.core.watch.WatchPairingStore
import casa.moller.tandem.core.watch.WatchTokens
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Fas 5 (wear lane): the phone's side of Tandem on a watch. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class WatchModule {
    @Binds
    abstract fun bindWatchRepository(implementation: DefaultWatchRepository): WatchRepository

    @Binds
    abstract fun bindDataLayer(implementation: PlayDataLayer): DataLayer

    @Binds
    abstract fun bindPairingStore(implementation: DataStoreWatchPairingStore): WatchPairingStore

    @Binds
    abstract fun bindWatchTokens(implementation: TandoorWatchTokens): WatchTokens

    // Fas 5 (integration-08): the watch token is deleted on the server when its account leaves the phone.
    @Binds
    @IntoSet
    abstract fun bindWatchRemoval(implementation: DefaultWatchRepository): AccountRemovalHook
}
