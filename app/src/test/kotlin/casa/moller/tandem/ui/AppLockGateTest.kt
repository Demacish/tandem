package casa.moller.tandem.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.R
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.settings.AppLockSettings
import casa.moller.tandem.core.model.settings.AppLockStatus
import casa.moller.tandem.core.testing.data.FakeAppLock
import casa.moller.tandem.core.ui.security.DeviceAuthResult
import casa.moller.tandem.core.ui.security.DeviceAuthenticator
import casa.moller.tandem.core.ui.security.DeviceLockAvailability
import casa.moller.tandem.core.ui.security.LocalDeviceAuthenticator
import casa.moller.tandem.feature.shell.navigation.ShareImportRequest
import casa.moller.tandem.feature.shell.navigation.ShellOpenRequest
import casa.moller.tandem.feature.shell.navigation.ShellTab
import casa.moller.tandem.feature.shell.navigation.TimerOpenRequest
import casa.moller.tandem.lock.AppLockViewModel
import casa.moller.tandem.navigation.AppDestination
import casa.moller.tandem.navigation.AppUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Fas 5 (las lane, plan §5.5): the lock in front of the app with a fake authenticator. The
 * content is hidden while locked; a notification tap, a deep link, a share or a shortcut that
 * opened the app waits and reaches its destination after the unlock; without a screen lock the
 * screen offers the system settings or to continue.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-port-xhdpi")
class AppLockGateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val authenticator = FakeAuthenticator()
    private val delivered = mutableListOf<Any>()

    private val waiting = AppUiState(
        destination = AppDestination.Shell("account"),
        timerRequest = TimerOpenRequest(recipeId = 12, stepIndex = 2),
        importRequest = ShareImportRequest(text = "https://example.com/recept"),
        openRequest = ShellOpenRequest.Tab(ShellTab.SHOPPING),
    )

    private fun show(lock: FakeAppLock) {
        val viewModel = AppLockViewModel(lock)
        composeRule.setContent {
            CompositionLocalProvider(LocalDeviceAuthenticator provides authenticator) {
                TandemTheme {
                    val status by lock.status.collectAsState()
                    AppLockGate(status = status, lockScreen = { AppLockRoute(viewModel) }, onLeave = {}) { unlocked ->
                        // A stand-in for the shell: it opens whatever request it is handed, once.
                        val state = waiting.forLock(unlocked)
                        LaunchedEffect(state.timerRequest, state.importRequest, state.openRequest) {
                            listOfNotNull(state.timerRequest, state.importRequest, state.openRequest).forEach { delivered += it }
                        }
                        Text(CONTENT)
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun withTheLockOffTheAppAndItsRequestsGoThroughAtOnce() {
        show(FakeAppLock(AppLockSettings(enabled = false)))
        composeRule.onNodeWithText(CONTENT).assertExists()
        composeRule.onNodeWithText(string(R.string.app_lock_title)).assertDoesNotExist()
        assertEquals(3, delivered.size)
        assertEquals(0, authenticator.prompts)
    }

    @Test
    fun aLockedAppHidesItsContentAndAsksForTheScreenLockByItself() {
        show(FakeAppLock(AppLockSettings(enabled = true)))
        composeRule.onNodeWithText(string(R.string.app_lock_title)).assertExists()
        // Not drawn and not read by TalkBack.
        composeRule.onNodeWithText(CONTENT).assertDoesNotExist()
        assertEquals(1, authenticator.prompts)
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun aDeepLinkWaitsForTheUnlockAndThenReachesItsDestination() {
        val lock = FakeAppLock(AppLockSettings(enabled = true))
        show(lock)
        assertTrue(delivered.isEmpty())
        authenticator.answer(DeviceAuthResult.Succeeded)
        composeRule.waitForIdle()
        assertEquals(AppLockStatus.UNLOCKED, lock.status.value)
        composeRule.onNodeWithText(CONTENT).assertExists()
        composeRule.onNodeWithText(string(R.string.app_lock_title)).assertDoesNotExist()
        assertEquals(
            listOf(
                TimerOpenRequest(recipeId = 12, stepIndex = 2),
                ShareImportRequest(text = "https://example.com/recept"),
                ShellOpenRequest.Tab(ShellTab.SHOPPING),
            ),
            delivered,
        )
    }

    @Test
    fun aClosedPromptKeepsTheLockAndUnlockAsksAgain() {
        val lock = FakeAppLock(AppLockSettings(enabled = true))
        show(lock)
        authenticator.answer(DeviceAuthResult.Cancelled)
        composeRule.waitForIdle()
        assertEquals(AppLockStatus.LOCKED, lock.status.value)
        composeRule.onNodeWithText(string(R.string.app_lock_unlock)).performClick()
        assertEquals(2, authenticator.prompts)
        authenticator.answer(DeviceAuthResult.Failed("För många försök."))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("För många försök.").assertExists()
        composeRule.onNodeWithText(CONTENT).assertDoesNotExist()
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun withoutAScreenLockTheUserCanContinueAndTheRequestsFollow() {
        authenticator.availability = DeviceLockAvailability.NO_SCREEN_LOCK
        val lock = FakeAppLock(AppLockSettings(enabled = true))
        show(lock)
        assertEquals(0, authenticator.prompts)
        composeRule.onNodeWithText(string(R.string.app_lock_no_screen_lock_title)).assertExists()
        composeRule.onNodeWithText(string(R.string.app_lock_continue_without)).performClick()
        composeRule.waitForIdle()
        assertEquals(1, lock.unlocks)
        composeRule.onNodeWithText(CONTENT).assertExists()
        assertEquals(3, delivered.size)
    }

    @Test
    fun theRequestsAreHeldOnlyWhileLocked() {
        val held = waiting.forLock(unlocked = false)
        assertNull(held.timerRequest)
        assertNull(held.importRequest)
        assertNull(held.openRequest)
        assertEquals(waiting.destination, held.destination)
        assertEquals(waiting, waiting.forLock(unlocked = true))
    }

    private fun string(id: Int): String = context.getString(id)

    /** Answers the prompt when the test says so. */
    private class FakeAuthenticator : DeviceAuthenticator {
        var availability = DeviceLockAvailability.AVAILABLE
        var prompts = 0
        private var pending: ((DeviceAuthResult) -> Unit)? = null

        override fun availability(): DeviceLockAvailability = availability

        override fun authenticate(title: String, subtitle: String?, onResult: (DeviceAuthResult) -> Unit): () -> Unit {
            prompts++
            pending = onResult
            return { pending = null }
        }

        fun answer(result: DeviceAuthResult) {
            val callback = checkNotNull(pending) { "no prompt open" }
            pending = null
            callback(result)
        }
    }

    private companion object {
        const val CONTENT = "Inköpslistan: mjölk"
    }
}
