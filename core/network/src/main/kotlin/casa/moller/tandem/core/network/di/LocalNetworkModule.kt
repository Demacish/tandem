package casa.moller.tandem.core.network.di

import casa.moller.tandem.core.network.http.AndroidLocalNetworkGuard
import casa.moller.tandem.core.network.http.LocalNetworkGuard
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Fas 5 (sdk37 lane): Android 17's local network protection on the phone (docs/BYGGE.md "Target SDK 37"). */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class LocalNetworkModule {
    @Binds
    abstract fun bindLocalNetworkGuard(impl: AndroidLocalNetworkGuard): LocalNetworkGuard
}
