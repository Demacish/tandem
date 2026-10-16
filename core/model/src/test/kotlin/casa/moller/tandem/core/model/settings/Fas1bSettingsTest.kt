package casa.moller.tandem.core.model.settings

import casa.moller.tandem.core.model.timer.TimerMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Fas1bSettingsTest {
    @Test
    fun phase1bDefaults() {
        val settings = AppSettings.Default
        assertFalse(settings.dynamicColor)
        assertEquals(1.0f, settings.cookTextScale)
        assertFalse(settings.cookModeAlwaysDark)
        assertEquals(TimerMode.IN_APP, settings.timerMode)
        assertEquals(SyncInterval.FIFTEEN_MINUTES, settings.syncInterval)
        assertFalse(settings.downloadAllRecipes)
        assertFalse(settings.notificationPermissionAsked)
        assertFalse(settings.exactAlarmPromptDismissed)
    }

    @Test
    fun cookTextScaleStepsThroughFixedSizes() {
        assertEquals(1.15f, CookTextScale.larger(1.0f))
        assertEquals(0.85f, CookTextScale.smaller(1.0f))
        assertEquals(CookTextScale.MAX, CookTextScale.larger(CookTextScale.MAX))
        assertEquals(CookTextScale.MIN, CookTextScale.smaller(CookTextScale.MIN))
        assertEquals(1.3f, CookTextScale.coerce(1.31f))
        assertEquals(CookTextScale.MAX, CookTextScale.coerce(9f))
        assertEquals(CookTextScale.DEFAULT, CookTextScale.coerce(Float.NaN))
    }

    @Test
    fun syncIntervalsRespectWorkManagersMinimum() {
        SyncInterval.entries.mapNotNull { it.minutes }.forEach { assertTrue(it >= 15) }
    }
}
