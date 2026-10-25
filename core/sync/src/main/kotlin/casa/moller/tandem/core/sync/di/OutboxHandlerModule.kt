package casa.moller.tandem.core.sync.di

import casa.moller.tandem.core.sync.engine.OutboxHandler
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Phase 2 (grund lane): the map of [OutboxHandler]s by outbox entity (`OutboxEntities`), empty until
 * the lanes bind theirs with `@IntoMap @StringKey(...)` in their own modules (docs/BYGGE.md "Fas 2 –
 * kontrakt").
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class OutboxHandlerModule {
    @Multibinds
    abstract fun outboxHandlers(): Map<String, OutboxHandler>
}
