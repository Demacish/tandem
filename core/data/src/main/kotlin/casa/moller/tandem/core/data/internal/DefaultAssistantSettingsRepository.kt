package casa.moller.tandem.core.data.internal

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.assistant.AssistantSettingsRepository
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.model.assistant.AssistantSettings
import casa.moller.tandem.core.model.error.Outcome
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Fas 5 (funktioner lane): [AssistantSettingsRepository] on the non-secret [SettingsStore] (like [DefaultSettingsRepository]). */
@Singleton
internal class DefaultAssistantSettingsRepository @Inject constructor(private val settingsStore: SettingsStore) :
    AssistantSettingsRepository {

    override val settings: Flow<AssistantSettings> = settingsStore.settings.map { it.assistant }.distinctUntilChanged()

    override suspend fun setEnabled(enabled: Boolean): Outcome<Unit> = write { it.copy(enabled = enabled) }

    override suspend fun setAllowWhenLocked(allowed: Boolean): Outcome<Unit> = write { it.copy(allowWhenLocked = allowed) }

    private suspend fun write(transform: (AssistantSettings) -> AssistantSettings): Outcome<Unit> = try {
        settingsStore.update { it.copy(assistant = transform(it.assistant)) }
        Outcome.Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Outcome.Failure(DataProblem.SETTINGS_NOT_SAVED.toError())
    }
}
