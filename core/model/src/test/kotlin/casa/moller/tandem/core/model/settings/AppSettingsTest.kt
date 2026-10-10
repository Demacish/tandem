package casa.moller.tandem.core.model.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppSettingsTest {
    @Test
    fun defaultsMatchAFreshInstall() {
        val settings = AppSettings.Default
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertFalse(settings.onboardingCompleted)
        assertFalse(settings.demoMode)
        assertNull(settings.activeAccountId)
    }

    @Test
    fun storageHealthNeedsRecoveryOnlyWithReasons() {
        assertFalse(StorageHealth.Healthy.recoveryNeeded)
        assertTrue(StorageHealth(setOf(RecoveryReason.ACCOUNTS_UNREADABLE)).recoveryNeeded)
    }
}
