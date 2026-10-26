package casa.moller.tandem.core.sync.di

import casa.moller.tandem.core.data.account.AccountDirectory
import casa.moller.tandem.core.sync.account.DefaultAccountDirectory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Phase 2 (konto lane): several stored accounts with one active, and the household (replaces the
 * app's `TemporaryFas2AccountModule`; docs/BYGGE.md "Konton och servrar").
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AccountModule {
    @Binds
    abstract fun bindAccountDirectory(impl: DefaultAccountDirectory): AccountDirectory
}
