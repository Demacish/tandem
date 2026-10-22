package casa.moller.tandem.core.data.di

import casa.moller.tandem.core.data.account.AccountRepository
import casa.moller.tandem.core.data.internal.DefaultAccountRepository
import casa.moller.tandem.core.data.internal.DefaultAppearanceRepository
import casa.moller.tandem.core.data.internal.DefaultSettingsRepository
import casa.moller.tandem.core.data.notification.NotificationChannelInstaller
import casa.moller.tandem.core.data.remote.NetworkTandoorRemote
import casa.moller.tandem.core.data.remote.TandoorRemote
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.settings.AppearanceRepository
import casa.moller.tandem.core.data.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

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

    // --- Phase 1b (grund lane): cook mode, timer and sync settings (plan 6.2 "Inställningar"). ---
    @Binds
    abstract fun bindSettingsRepository(impl: DefaultSettingsRepository): SettingsRepository

    @Binds
    abstract fun bindTandoorRemote(impl: NetworkTandoorRemote): TandoorRemote

    // --- Phase 1b (integration lane): the modules' notification channels, created at startup. ---
    @Multibinds
    abstract fun notificationChannelInstallers(): Set<NotificationChannelInstaller>
}
