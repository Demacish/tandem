package casa.moller.tandem.core.sync.platform

import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.shopping.ShoppingChange
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.shopping.ShoppingChangeBus
import casa.moller.tandem.core.sync.shopping.ShoppingChangeNotifications
import casa.moller.tandem.core.sync.testing.FakeSettingsStore
import casa.moller.tandem.core.sync.testing.FakeSources
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fas 4 (integration): nothing the active source's notifications said stays when it is left. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountNotificationCleanupTest {
    private val sources = FakeSources(ActiveSource.Demo)
    private val bus = ShoppingChangeBus()
    private val posted = mutableListOf<ShoppingChangeSummary>()
    private var cancelled = 0

    private fun TestScope.start() {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val settings = FakeSettingsStore(AppSettings.Default.copy(shoppingChangeNotifications = true))
        val shopping = ShoppingChangeNotifications(bus, settings, { false }, { summary -> posted.add(summary) }, scope)
        shopping.start()
        AccountNotificationCleanup(sources, shopping, { cancelled++ }, scope).start()
        runCurrent()
    }

    @Test
    fun theFirstSourceOfAProcessIsNoChange() = runTest {
        start()
        assertEquals(0, cancelled)
    }

    @Test
    fun leavingTheSourceRemovesTheNotificationsAndWhatWasGathered() = runTest {
        start()
        bus.emit("acc", ShoppingChange.Added("mjölk", by = "Anna"))
        runCurrent()

        sources.source.value = ActiveSource.None
        runCurrent()
        advanceTimeBy(ShoppingChangeNotifications.BATCH_WINDOW + 1.seconds)
        runCurrent()

        assertEquals(1, cancelled)
        assertTrue("gathered changes of the old source are dropped: $posted", posted.isEmpty())
    }

    @Test
    fun loadingAgainIsNoChange() = runTest {
        start()
        sources.source.value = ActiveSource.Loading
        runCurrent()
        sources.source.value = ActiveSource.Demo
        runCurrent()
        assertEquals(0, cancelled)
    }
}
