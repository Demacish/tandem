package casa.moller.tandem.feature.shell.push

import casa.moller.tandem.core.model.push.FastPushError
import casa.moller.tandem.core.model.push.FastPushResult
import casa.moller.tandem.core.model.push.FastPushStatus
import casa.moller.tandem.core.model.timer.TimerCapabilities
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeSettingsRepository
import casa.moller.tandem.core.testing.data.FakeTimerController
import casa.moller.tandem.feature.shell.R
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Fas 4 (push lane): Mer → Synk och lagring → Snabb push. */
class FastPushViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val push = FakeFastPushRepository()
    private val settings = FakeSettingsRepository()
    private val timers = FakeTimerController()

    private fun newViewModel() = FastPushViewModel(push, settings, timers)

    private fun <T> TestScope.collecting(flow: StateFlow<T>): StateFlow<T> {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { } }
        return flow
    }

    private fun TestScope.effectsOf(vm: FastPushViewModel): MutableList<FastPushEffect> {
        val effects = mutableListOf<FastPushEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effect.collect { effects += it } }
        return effects
    }

    @Test
    fun theOnlyDistributorIsPreselectedAndTurningOnNeedsAnAddress() = runTest {
        val vm = newViewModel()
        val state = collecting(vm.uiState)
        assertEquals(NTFY.packageName, state.value.selectedDistributor)
        assertFalse("no address yet", state.value.canEnable)

        vm.onAction(FastPushAction.UrlChanged("https://push.example.com"))
        assertTrue(state.value.canEnable)
    }

    @Test
    fun turningOnSwitchesOnTheListNotificationAndAsksForThePermission() = runTest {
        timers.setCapabilities(TimerCapabilities.AllGranted.copy(notificationsAllowed = false))
        val vm = newViewModel()
        val state = collecting(vm.uiState)
        val effects = effectsOf(vm)

        vm.onAction(FastPushAction.UrlChanged("https://push.example.com"))
        vm.onAction(FastPushAction.Enable)

        assertEquals(listOf("https://push.example.com" to NTFY.packageName), push.enabled)
        assertTrue("the wake-up's notification is the list notification", settings.state.value.shoppingChangeNotifications)
        assertEquals(
            listOf(FastPushEffect.Message(R.string.fast_push_message_enabling), FastPushEffect.RequestNotificationPermission),
            effects,
        )
        assertEquals(FastPushStatus.REGISTERING, state.value.status)
        assertTrue(state.value.isOn)
        assertFalse(state.value.busy)

        vm.onAction(FastPushAction.NotificationPermissionResult(granted = true))
        assertTrue(settings.state.value.notificationPermissionAsked)
    }

    @Test
    fun aRefusedAddressIsMarkedAndExplained() = runTest {
        push.enableResult = FastPushResult.Failure(FastPushError.INVALID_URL)
        val vm = newViewModel()
        val state = collecting(vm.uiState)
        val effects = effectsOf(vm)

        vm.onAction(FastPushAction.UrlChanged("http://push.example.com"))
        vm.onAction(FastPushAction.Enable)

        assertTrue(state.value.urlInvalid)
        assertEquals(listOf<FastPushEffect>(FastPushEffect.Message(R.string.fast_push_error_invalid_url)), effects)
        assertFalse("nothing switched on", settings.state.value.shoppingChangeNotifications)

        vm.onAction(FastPushAction.UrlChanged("https://push.example.com"))
        assertFalse("typing clears the mark", state.value.urlInvalid)
    }

    @Test
    fun withoutADistributorNothingCanBeTurnedOnUntilOneIsInstalled() = runTest {
        push.installed = emptyList()
        val vm = newViewModel()
        val state = collecting(vm.uiState)
        vm.onAction(FastPushAction.UrlChanged("https://push.example.com"))
        assertTrue(state.value.distributors.isEmpty())
        assertFalse(state.value.canEnable)

        push.installed = listOf(NTFY, NEXTPUSH)
        vm.onAction(FastPushAction.Refresh)
        assertEquals(2, state.value.distributors.size)
        assertEquals("two apps: the user picks", null, state.value.selectedDistributor)
        vm.onAction(FastPushAction.SelectDistributor(NEXTPUSH.packageName))
        assertTrue(state.value.canEnable)
    }

    @Test
    fun testAndTurningOffReportBack() = runTest {
        push.state.value = push.state.value.copy(
            status = FastPushStatus.ACTIVE,
            testSupported = true,
            serviceUrl = "https://push.example.com",
        )
        val vm = newViewModel()
        val state = collecting(vm.uiState)
        val effects = effectsOf(vm)
        assertTrue(state.value.canTest)

        vm.onAction(FastPushAction.SendTest)
        push.testResult = FastPushResult.Failure(FastPushError.RATE_LIMITED)
        vm.onAction(FastPushAction.SendTest)
        vm.onAction(FastPushAction.Disable)

        assertEquals(2, push.tests)
        assertEquals(1, push.disabled)
        assertEquals(
            listOf(
                FastPushEffect.Message(R.string.fast_push_message_test_sent),
                FastPushEffect.Message(R.string.fast_push_error_rate_limited),
                FastPushEffect.Message(R.string.fast_push_message_disabled),
            ),
            effects,
        )
        assertEquals(FastPushStatus.OFF, state.value.status)
        assertEquals("the address stays for next time", "https://push.example.com", state.value.url)
    }
}
