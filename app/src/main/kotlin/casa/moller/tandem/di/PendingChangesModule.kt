package casa.moller.tandem.di

import casa.moller.tandem.core.data.sync.PendingChangesExporter
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The recovery screen's "Export pending changes" (feature #37) takes the outbox exporter as
 * `Optional<PendingChangesExporter>`: absent until the outbox implementation (sync lane) binds
 * one with `@Binds`, after which the export works without further changes here.
 */
@Module
@InstallIn(SingletonComponent::class)
interface PendingChangesModule {
    @BindsOptionalOf
    fun optionalPendingChangesExporter(): PendingChangesExporter
}
