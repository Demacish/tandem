package casa.moller.tandem.core.data.internal

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.settings.AppearanceRepository
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.settings.ThemeMode
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** [AppearanceRepository] on top of the non-secret [SettingsStore]. */
@Singleton
internal class DefaultAppearanceRepository @Inject constructor(private val settingsStore: SettingsStore) : AppearanceRepository {

    override val themeMode: Flow<ThemeMode> = settingsStore.settings.map { it.themeMode }.distinctUntilChanged()

    override suspend fun setThemeMode(mode: ThemeMode): Outcome<Unit> = try {
        settingsStore.setThemeMode(mode)
        Outcome.Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Outcome.Failure(DataProblem.SETTINGS_NOT_SAVED.toError())
    }
}
