package casa.moller.tandem.core.data.internal

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.settings.SettingsRepository
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.settings.CookTextScale
import casa.moller.tandem.core.model.settings.SyncInterval
import casa.moller.tandem.core.model.timer.TimerMode
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** [SettingsRepository] on top of the non-secret [SettingsStore] (like [DefaultAppearanceRepository]). */
@Singleton
internal class DefaultSettingsRepository @Inject constructor(private val settingsStore: SettingsStore) : SettingsRepository {

    override val settings: Flow<AppSettings> = settingsStore.settings
        .map { it.copy(cookTextScale = CookTextScale.coerce(it.cookTextScale)) }
        .distinctUntilChanged()

    override suspend fun setDynamicColor(enabled: Boolean): Outcome<Unit> = write { it.copy(dynamicColor = enabled) }

    override suspend fun setCookTextScale(scale: Float): Outcome<Unit> = write { it.copy(cookTextScale = CookTextScale.coerce(scale)) }

    override suspend fun setCookModeAlwaysDark(enabled: Boolean): Outcome<Unit> = write { it.copy(cookModeAlwaysDark = enabled) }

    override suspend fun setTimerMode(mode: TimerMode): Outcome<Unit> = write { it.copy(timerMode = mode) }

    override suspend fun setSyncInterval(interval: SyncInterval): Outcome<Unit> = write { it.copy(syncInterval = interval) }

    override suspend fun setDownloadAllRecipes(enabled: Boolean): Outcome<Unit> = write { it.copy(downloadAllRecipes = enabled) }

    override suspend fun setNotificationPermissionAsked(asked: Boolean): Outcome<Unit> =
        write { it.copy(notificationPermissionAsked = asked) }

    override suspend fun setExactAlarmPromptDismissed(dismissed: Boolean): Outcome<Unit> =
        write { it.copy(exactAlarmPromptDismissed = dismissed) }

    private suspend fun write(transform: (AppSettings) -> AppSettings): Outcome<Unit> = try {
        settingsStore.update(transform)
        Outcome.Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Outcome.Failure(DataProblem.SETTINGS_NOT_SAVED.toError())
    }
}
