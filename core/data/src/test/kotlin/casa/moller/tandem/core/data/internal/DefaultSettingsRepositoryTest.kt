package casa.moller.tandem.core.data.internal

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.errorOrNull
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.settings.SyncInterval
import casa.moller.tandem.core.model.timer.TimerMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSettingsRepositoryTest {

    private val store = FakeSettingsStore()
    private val repository = DefaultSettingsRepository(store)

    @Test
    fun storesEveryPhase1bSetting() = runTest {
        val ok = Outcome.Success(Unit)
        assertEquals(ok, repository.setDynamicColor(true))
        assertEquals(ok, repository.setCookTextScale(1.5f))
        assertEquals(ok, repository.setCookModeAlwaysDark(true))
        assertEquals(ok, repository.setTimerMode(TimerMode.CLOCK_APP))
        assertEquals(ok, repository.setSyncInterval(SyncInterval.MANUAL))
        assertEquals(ok, repository.setDownloadAllRecipes(true))
        assertEquals(ok, repository.setNotificationPermissionAsked(true))
        assertEquals(ok, repository.setExactAlarmPromptDismissed(true))

        val settings = repository.settings.first()
        assertTrue(settings.dynamicColor)
        assertEquals(1.5f, settings.cookTextScale)
        assertTrue(settings.cookModeAlwaysDark)
        assertEquals(TimerMode.CLOCK_APP, settings.timerMode)
        assertEquals(SyncInterval.MANUAL, settings.syncInterval)
        assertTrue(settings.downloadAllRecipes)
        assertTrue(settings.notificationPermissionAsked)
        assertTrue(settings.exactAlarmPromptDismissed)
        assertEquals(settings, store.state.value)
    }

    @Test
    fun cookTextScaleIsCoercedToAStep() = runTest {
        repository.setCookTextScale(7f)
        assertEquals(2.0f, store.state.value.cookTextScale)
        store.state.value = AppSettings(cookTextScale = 1.12f)
        assertEquals(1.15f, repository.settings.first().cookTextScale)
    }

    @Test
    fun writeFailureIsReported() = runTest {
        store.failWrites = true
        val result = repository.setDynamicColor(true)
        assertEquals(DataProblem.SETTINGS_NOT_SAVED, result.errorOrNull()?.dataProblem())
        assertFalse(repository.settings.first().dynamicColor)
    }
}
