package casa.moller.tandem.lock

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.settings.AppLockSettings
import casa.moller.tandem.core.model.settings.AppLockStatus
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeAppLock
import casa.moller.tandem.core.ui.security.DeviceAuthResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Fas 5 (las lane, plan §5.5): what the lock screen does with the system prompt's answers. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppLockViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val lock = FakeAppLock(AppLockSettings(enabled = true))
    private val model by lazy { AppLockViewModel(lock) }

    @Test
    fun aConfirmedScreenLockUnlocks() {
        model.onPromptShown()
        assertTrue(model.uiState.value.prompting)
        model.onAuthResult(DeviceAuthResult.Succeeded)
        assertEquals(AppLockStatus.UNLOCKED, lock.status.value)
        assertEquals(AppLockUiState(), model.uiState.value)
        assertEquals(true, model.lockEnabled.value)
    }

    @Test
    fun aClosedOrFailedPromptKeepsTheLock() {
        model.onPromptShown()
        model.onAuthResult(DeviceAuthResult.Cancelled)
        assertFalse(model.uiState.value.prompting)
        assertNull(model.uiState.value.failure)
        model.onPromptShown()
        model.onAuthResult(DeviceAuthResult.Failed("Too many attempts."))
        assertEquals("Too many attempts.", model.uiState.value.failure)
        model.onPromptShown()
        model.onAuthResult(DeviceAuthResult.Failed(null))
        assertTrue(model.uiState.value.failed)
        assertEquals(AppLockStatus.LOCKED, lock.status.value)
        assertEquals(0, lock.unlocks)
    }

    @Test
    fun goingOnWithoutTheLockOnlyWorksWithoutAScreenLock() {
        model.continueWithoutLock()
        assertEquals(AppLockStatus.LOCKED, lock.status.value)
        model.onAuthResult(DeviceAuthResult.NoScreenLock)
        assertTrue(model.uiState.value.noScreenLock)
        model.continueWithoutLock()
        assertEquals(AppLockStatus.UNLOCKED, lock.status.value)
    }

    @Test
    fun aScreenLockSetUpMeanwhileIsUsedAsUsual() {
        model.onAuthResult(DeviceAuthResult.NoScreenLock)
        model.onScreenLockAvailable()
        assertFalse(model.uiState.value.noScreenLock)
        model.continueWithoutLock()
        assertEquals(AppLockStatus.LOCKED, lock.status.value)
    }
}
