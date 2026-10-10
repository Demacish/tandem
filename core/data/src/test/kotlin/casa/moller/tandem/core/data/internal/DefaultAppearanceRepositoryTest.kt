package casa.moller.tandem.core.data.internal

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.errorOrNull
import casa.moller.tandem.core.model.settings.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultAppearanceRepositoryTest {

    private val settings = FakeSettingsStore()
    private val repository = DefaultAppearanceRepository(settings)

    @Test
    fun themeFollowsTheSystemByDefault() = runTest {
        assertEquals(ThemeMode.SYSTEM, repository.themeMode.first())
    }

    @Test
    fun storesTheChosenTheme() = runTest {
        assertEquals(Outcome.Success(Unit), repository.setThemeMode(ThemeMode.DARK))
        assertEquals(ThemeMode.DARK, repository.themeMode.first())
        assertEquals(ThemeMode.DARK, settings.state.value.themeMode)
    }

    @Test
    fun writeFailureIsReported() = runTest {
        settings.failWrites = true
        val result = repository.setThemeMode(ThemeMode.LIGHT)
        assertEquals(DataProblem.SETTINGS_NOT_SAVED, result.errorOrNull()?.dataProblem())
        assertEquals(ThemeMode.SYSTEM, repository.themeMode.first())
    }
}
