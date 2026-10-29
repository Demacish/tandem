package casa.moller.tandem.di

import casa.moller.tandem.core.data.ai.AiSettingsRepository
import casa.moller.tandem.core.datastore.AiKeyStore
import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.core.model.ai.AiSettings
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// Fas S (grund lane, docs/BYGGE.md "Fas S – kontrakt"): TEMPORARY stand-ins so the app builds and
// runs before the lanes land. They are empty and every write fails with [NOT_BUILT]; no screen
// reaches them yet (the entry points come with the lanes). Each lane deletes its module here in
// the same commit that binds the real implementation in core:sync / core:datastore; the last one
// deletes the file. (core:testing's fakes cannot be used in a main configuration: checkModuleGraph
// rule 4.) FridgeVision is bound by core:ai itself (AiModule).

/** AI lane (ai-foto): settings and the encrypted key store. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class TemporaryFasSAiModule {
    @Binds
    abstract fun bindAiSettings(impl: NotBuiltAiSettingsRepository): AiSettingsRepository

    @Binds
    abstract fun bindAiKeys(impl: NotBuiltAiKeyStore): AiKeyStore
}

private val NOT_BUILT = TandemError.Unknown("tandem.fas-s.not-built-yet")

private fun <T> notBuilt(): Outcome<T> = Outcome.Failure(NOT_BUILT)

@Singleton
internal class NotBuiltAiSettingsRepository @Inject constructor() : AiSettingsRepository {
    override val settings: Flow<AiSettings> = flowOf(AiSettings())

    override suspend fun setProvider(provider: AiProvider?): Outcome<Unit> = notBuilt()

    override suspend fun setModel(model: String?): Outcome<Unit> = notBuilt()

    override suspend fun setCustomBaseUrl(url: String?): Outcome<Unit> = notBuilt()

    override suspend fun storeKey(provider: AiProvider, key: String): Outcome<Unit> = notBuilt()

    override suspend fun removeKey(provider: AiProvider): Outcome<Unit> = notBuilt()

    override suspend fun acknowledgeWarning(): Outcome<Unit> = notBuilt()
}

/** Stores nothing: until the ai-foto lane's encrypted store exists, no key can be entered at all. */
@Singleton
internal class NotBuiltAiKeyStore @Inject constructor() : AiKeyStore {
    override val providersWithKey: Flow<Set<AiProvider>> = flowOf(emptySet())

    override suspend fun store(provider: AiProvider, key: String) = Unit

    override suspend fun remove(provider: AiProvider) = Unit

    override suspend fun <T> useKey(provider: AiProvider, block: suspend (key: String) -> T): T? = null

    override suspend fun clearAll() = Unit
}
