package casa.moller.tandem.core.data.di

import casa.moller.tandem.core.data.account.AccountRepository
import casa.moller.tandem.core.data.internal.DefaultAccountRepository
import casa.moller.tandem.core.data.internal.DefaultAppearanceRepository
import casa.moller.tandem.core.data.remote.NetworkTandoorRemote
import casa.moller.tandem.core.data.remote.TandoorRemote
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.settings.AppearanceRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Both repository interfaces are served by the same @Singleton [DefaultAccountRepository], so the
 * session and the login flow share one write lock.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataModule {
    @Binds
    abstract fun bindAccountRepository(impl: DefaultAccountRepository): AccountRepository

    @Binds
    abstract fun bindSessionRepository(impl: DefaultAccountRepository): SessionRepository

    @Binds
    abstract fun bindAppearanceRepository(impl: DefaultAppearanceRepository): AppearanceRepository

    @Binds
    abstract fun bindTandoorRemote(impl: NetworkTandoorRemote): TandoorRemote
}
