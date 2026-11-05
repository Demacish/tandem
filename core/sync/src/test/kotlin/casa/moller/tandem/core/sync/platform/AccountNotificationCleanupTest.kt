package casa.moller.tandem.core.sync.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.shopping.ShoppingChange
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.shopping.ShoppingChangeBus
import casa.moller.tandem.core.sync.shopping.ShoppingChangeNotifications
import casa.moller.tandem.core.sync.testing.FakeSettingsStore
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Fas 4 (integration): nothing the active account's notifications said stays when it is left. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AccountNotificationCleanupTest {
    private val graph = SyncTestGraph()
    private val bus = ShoppingChangeBus()
    private val posted = mutableListOf<ShoppingChangeSummary>()
    private var cancelled = 0

    @After
    fun tearDown() {
        graph.close()
    }

    private fun TestScope.start() {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val settings = FakeSettingsStore(AppSettings.Default.copy(shoppingChangeNotifications = true))
        val shopping = ShoppingChangeNotifications(bus, settings, { false }, { summary -> posted.add(summary) }, scope)
        shopping.start()
        AccountNotificationCleanup(graph.sources, shopping, { cancelled++ }, scope).start()
        runCurrent()
    }

    @Test
    fun theFirstSourceOfAProcessIsNoChange() = runTest {
        start()
        assertEquals(0, cancelled)
    }

    @Test
    fun loggingOutRemovesTheNotificationsAndWhatWasGathered() = runTest {
        start()
        bus.emit("acc", ShoppingChange.Added("mjölk", by = "Anna"))
        runCurrent()

        graph.sources.source.value = ActiveSource.None
        runCurrent()
        advanceTimeBy(ShoppingChangeNotifications.BATCH_WINDOW + 1.seconds)
        runCurrent()

        assertEquals(1, cancelled)
        assertTrue("gathered changes of the old account are dropped: $posted", posted.isEmpty())
    }

    @Test
    fun demoModeIsAChangeButTheSameAccountIsNot() = runTest {
        start()
        graph.sources.source.value = graph.remote
        runCurrent()
        assertEquals(0, cancelled)

        graph.sources.source.value = ActiveSource.Demo
        runCurrent()
        assertEquals(1, cancelled)
    }
}
