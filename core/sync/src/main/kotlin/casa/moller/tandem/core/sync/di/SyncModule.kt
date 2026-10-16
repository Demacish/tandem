package casa.moller.tandem.core.sync.di

import android.content.Context
import casa.moller.tandem.core.data.cooklog.CookLogRepository
import casa.moller.tandem.core.data.demo.DemoDataRepository
import casa.moller.tandem.core.data.diagnostics.DiagnosticsRepository
import casa.moller.tandem.core.data.home.HomeRepository
import casa.moller.tandem.core.data.preferences.PreferencesRepository
import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.data.server.ServerStatusRepository
import casa.moller.tandem.core.data.sync.ImportGate
import casa.moller.tandem.core.data.sync.OfflineStorageRepository
import casa.moller.tandem.core.data.sync.PendingChangesExporter
import casa.moller.tandem.core.data.sync.SyncRepository
import casa.moller.tandem.core.sync.account.ActiveSourceProvider
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.data.DefaultDiagnosticsRepository
import casa.moller.tandem.core.sync.data.DefaultImportGate
import casa.moller.tandem.core.sync.data.DefaultOfflineStorageRepository
import casa.moller.tandem.core.sync.data.DefaultPreferencesRepository
import casa.moller.tandem.core.sync.data.DefaultServerStatusRepository
import casa.moller.tandem.core.sync.data.DefaultSyncRepository
import casa.moller.tandem.core.sync.data.OfflineFirstCookLogRepository
import casa.moller.tandem.core.sync.data.OfflineFirstHomeRepository
import casa.moller.tandem.core.sync.data.OfflineFirstRecipeRepository
import casa.moller.tandem.core.sync.data.RoomPendingChangesExporter
import casa.moller.tandem.core.sync.demo.DemoSeeder
import casa.moller.tandem.core.sync.image.AccountImageCallFactory
import casa.moller.tandem.core.sync.image.CoilImageCache
import casa.moller.tandem.core.sync.image.CoilImagePrefetcher
import casa.moller.tandem.core.sync.image.ImageCache
import casa.moller.tandem.core.sync.image.ImagePrefetcher
import casa.moller.tandem.core.sync.image.TandemImageLoader
import casa.moller.tandem.core.sync.platform.AndroidVersionChangeNotifier
import casa.moller.tandem.core.sync.platform.ConnectivityMonitor
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.platform.SyncScheduler
import casa.moller.tandem.core.sync.platform.SystemSyncClock
import casa.moller.tandem.core.sync.platform.VersionChangeNotifier
import casa.moller.tandem.core.sync.work.WorkManagerSyncScheduler
import coil3.ImageLoader
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The real Phase 1 data layer (replaces the grund lane's temporary bindings): every repository
 * interface of core:data that needs Room or the network is implemented here, plus the sync's own
 * platform pieces. The app only adds `HiltWorkerFactory` and calls `SyncStarter.start()`.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class SyncModule {
    @Binds
    abstract fun bindRecipeRepository(impl: OfflineFirstRecipeRepository): RecipeRepository

    @Binds
    abstract fun bindHomeRepository(impl: OfflineFirstHomeRepository): HomeRepository

    @Binds
    abstract fun bindSyncRepository(impl: DefaultSyncRepository): SyncRepository

    @Binds
    abstract fun bindPreferencesRepository(impl: DefaultPreferencesRepository): PreferencesRepository

    @Binds
    abstract fun bindServerStatusRepository(impl: DefaultServerStatusRepository): ServerStatusRepository

    @Binds
    abstract fun bindCookLogRepository(impl: OfflineFirstCookLogRepository): CookLogRepository

    @Binds
    abstract fun bindPendingChangesExporter(impl: RoomPendingChangesExporter): PendingChangesExporter

    // --- Phase 1b (inställningar lane): Settings → Diagnostics and Sync and storage. ---
    @Binds
    abstract fun bindDiagnosticsRepository(impl: DefaultDiagnosticsRepository): DiagnosticsRepository

    @Binds
    abstract fun bindOfflineStorageRepository(impl: DefaultOfflineStorageRepository): OfflineStorageRepository

    @Binds
    abstract fun bindImportGate(impl: DefaultImportGate): ImportGate

    @Binds
    abstract fun bindDemoDataRepository(impl: DemoSeeder): DemoDataRepository

    @Binds
    abstract fun bindActiveSources(impl: ActiveSourceProvider): ActiveSources

    @Binds
    abstract fun bindSyncClock(impl: SystemSyncClock): SyncClock

    @Binds
    abstract fun bindNetworkStatus(impl: ConnectivityMonitor): NetworkStatus

    @Binds
    abstract fun bindVersionChangeNotifier(impl: AndroidVersionChangeNotifier): VersionChangeNotifier

    @Binds
    abstract fun bindSyncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler

    @Binds
    abstract fun bindImagePrefetcher(impl: CoilImagePrefetcher): ImagePrefetcher

    @Binds
    abstract fun bindImageCache(impl: CoilImageCache): ImageCache
}

/** The app's one Coil [ImageLoader] (installed as Coil's singleton by `SyncStarter`). */
@Module
@InstallIn(SingletonComponent::class)
internal object ImageLoaderModule {
    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context, callFactory: AccountImageCallFactory): ImageLoader =
        TandemImageLoader.build(context, callFactory)
}
