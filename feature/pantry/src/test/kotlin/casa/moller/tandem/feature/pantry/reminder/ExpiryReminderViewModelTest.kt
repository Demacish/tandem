package casa.moller.tandem.feature.pantry.reminder

import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.timer.TimerCapabilities
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakePantryRepository
import casa.moller.tandem.core.testing.data.FakeSettingsRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.FakeTimerController
import casa.moller.tandem.feature.pantry.PantrySamples
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Fas 4 (skafferinotis lane): Hemma's offer of "Notis när något går ut": when it is shown, "Slå på"
 * with and without the permission, "Inte nu", guests and demo mode.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpiryReminderViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settings = FakeSettingsRepository()
    private val timers = FakeTimerController(capabilities = TimerCapabilities.FreshInstall)
    private val permissions = FakeSpacePermissionsRepository()
    private val pantry = FakePantryRepository(locations = PantrySamples.locations, entries = PantrySamples.entries)

    private fun newViewModel() = ExpiryReminderViewModel(settings, timers, permissions, pantry)

    @Test
    fun theOfferNeedsDatedEntriesAndNeitherGuestNorDemo() {
        val member = SpacePermissions(SpaceRole.USER)
        val off = AppSettings.Default
        assertEquals(ExpiryReminderOffer(), ExpiryReminderViewModel.offerOf(off, false, member, hasExpiryDates = true))
        assertNull("nothing has a date", ExpiryReminderViewModel.offerOf(off, false, member, hasExpiryDates = false))
        assertNull("a guest", ExpiryReminderViewModel.offerOf(off, false, SpacePermissions(SpaceRole.GUEST), true))
        assertNull("demo mode", ExpiryReminderViewModel.offerOf(off, false, SpacePermissions(SpaceRole.USER, isDemo = true), true))
        assertNull("dismissed", ExpiryReminderViewModel.offerOf(off.copy(pantryExpiryOfferDismissed = true), false, member, true))
        val on = off.copy(pantryExpiryNotifications = true)
        assertNull("on and allowed", ExpiryReminderViewModel.offerOf(on, true, member, true))
        assertEquals("on but blocked", ExpiryReminderOffer(blocked = true), ExpiryReminderViewModel.offerOf(on, false, member, false))
    }

    @Test
    fun turningItOnAsksForThePermissionInContext() = runTest {
        val vm = newViewModel()
        val effects = mutableListOf<ExpiryReminderEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.offer.collect {} }
        assertEquals(ExpiryReminderOffer(), vm.offer.value)

        vm.onAction(ExpiryReminderAction.Enable)
        assertTrue(settings.state.value.pantryExpiryNotifications)
        assertEquals(listOf<ExpiryReminderEffect>(ExpiryReminderEffect.RequestPermission), effects)
        assertEquals("on, the permission still missing", ExpiryReminderOffer(blocked = true), vm.offer.value)

        timers.setCapabilities(TimerCapabilities.AllGranted)
        vm.onAction(ExpiryReminderAction.PermissionResult(granted = true))
        assertTrue(settings.state.value.notificationPermissionAsked)
        assertEquals(1, timers.refreshCount)
        assertEquals(ExpiryReminderEffect.Enabled, effects.last())
        assertNull("on and allowed: no card", vm.offer.value)
    }

    @Test
    fun withThePermissionItIsOnAtOnceAndNotNowHidesTheCard() = runTest {
        timers.setCapabilities(TimerCapabilities.AllGranted)
        val vm = newViewModel()
        val effects = mutableListOf<ExpiryReminderEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.offer.collect {} }

        vm.onAction(ExpiryReminderAction.Dismiss)
        assertTrue(settings.state.value.pantryExpiryOfferDismissed)
        assertFalse("not switched on", settings.state.value.pantryExpiryNotifications)
        assertNull(vm.offer.value)
        assertTrue(effects.isEmpty())

        vm.onAction(ExpiryReminderAction.Enable)
        assertEquals(listOf<ExpiryReminderEffect>(ExpiryReminderEffect.Enabled), effects)

        settings.failWrites = true
        vm.onAction(ExpiryReminderAction.Enable)
        assertEquals(ExpiryReminderEffect.NotSaved, effects.last())
    }

    @Test
    fun aGuestIsNeverOffered() = runTest {
        permissions.setGuest(true)
        val vm = newViewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.offer.collect {} }
        assertNull(vm.offer.value)
    }
}
