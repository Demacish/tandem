package casa.moller.tandem.core.sync.di

import casa.moller.tandem.core.data.security.AppLock
import casa.moller.tandem.core.sync.security.DefaultAppLock
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Fas 5 (las lane, plan §5.5): Mer → Lås appen (docs/BYGGE.md "Lås appen"). */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AppLockModule {
    @Binds
    abstract fun bindAppLock(impl: DefaultAppLock): AppLock
}
