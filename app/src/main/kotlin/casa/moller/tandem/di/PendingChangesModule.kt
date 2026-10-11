package casa.moller.tandem.di

import casa.moller.tandem.core.data.sync.PendingChangesExporter
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The recovery screen's "Export pending changes" (feature #37) takes the outbox exporter as
 * `Optional<PendingChangesExporter>`. core:sync binds it (`RoomPendingChangesExporter`), so it
 * is present in the app; the optional binding keeps the recovery screen independent of it.
 */
@Module
@InstallIn(SingletonComponent::class)
interface PendingChangesModule {
    @BindsOptionalOf
    fun optionalPendingChangesExporter(): PendingChangesExporter
}
