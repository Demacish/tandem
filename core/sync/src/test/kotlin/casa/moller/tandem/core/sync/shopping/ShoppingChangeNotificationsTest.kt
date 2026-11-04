package casa.moller.tandem.core.sync.shopping

import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.shopping.ShoppingChange
import casa.moller.tandem.core.sync.platform.AppVisibility
import casa.moller.tandem.core.sync.platform.ShoppingChangeNotifier
import casa.moller.tandem.core.sync.platform.ShoppingChangeSummary
import casa.moller.tandem.core.sync.testing.FakeSettingsStore
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

/** Fas 4: the changes a background read notices become one notification, off screen only. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingChangeNotificationsTest {

    private val bus = ShoppingChangeBus()
    private val settings = FakeSettingsStore(AppSettings.Default.copy(shoppingChangeNotifications = true))
    private var visible = false
    private val posted = mutableListOf<ShoppingChangeSummary>()
    private val notifier = ShoppingChangeNotifier { summary -> posted.add(summary) }

    private fun TestScope.start(): ShoppingChangeNotifications {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        return ShoppingChangeNotifications(bus, settings, AppVisibility { visible }, notifier, scope).also {
            it.start()
            runCurrent()
        }
    }

    @Test
    fun aReadsChangesAreGatheredIntoOneNotification() = runTest {
        start()
        bus.emit("acc", ShoppingChange.Added("mjölk", by = "Anna"))
        bus.emit("acc", ShoppingChange.Checked("ägg"))
        runCurrent()
        assertTrue("nothing before the window closes", posted.isEmpty())
        advanceTimeBy(ShoppingChangeNotifications.BATCH_WINDOW + 1.seconds)
        runCurrent()
        assertEquals(1, posted.size)
        assertEquals(listOf(ShoppingChange.Added("mjölk", by = "Anna")), posted.single().added)
        assertEquals(listOf("ägg"), posted.single().checked)

        // The next read later: a new notification.
        bus.emit("acc", ShoppingChange.Removed("smör"))
        advanceTimeBy(ShoppingChangeNotifications.BATCH_WINDOW + 1.seconds)
        runCurrent()
        assertEquals(2, posted.size)
        assertEquals(listOf("smör"), posted.last().removed)
    }

    @Test
    fun nothingWhileTheAppIsOnScreenOrTheSettingIsOff() = runTest {
        start()
        visible = true
        bus.emit("acc", ShoppingChange.Added("mjölk", by = "Anna"))
        advanceTimeBy(ShoppingChangeNotifications.BATCH_WINDOW + 1.seconds)
        runCurrent()
        assertTrue("the list's own snackbar tells", posted.isEmpty())

        visible = false
        settings.state.value = AppSettings.Default.copy(shoppingChangeNotifications = false)
        bus.emit("acc", ShoppingChange.Added("mjölk", by = "Anna"))
        advanceTimeBy(ShoppingChangeNotifications.BATCH_WINDOW + 1.seconds)
        runCurrent()
        assertTrue("off by default", posted.isEmpty())

        // Back on screen before the window closed: the list shows it, no notification.
        settings.state.value = AppSettings.Default.copy(shoppingChangeNotifications = true)
        bus.emit("acc", ShoppingChange.Added("mjölk", by = "Anna"))
        runCurrent()
        visible = true
        advanceTimeBy(ShoppingChangeNotifications.BATCH_WINDOW + 1.seconds)
        runCurrent()
        assertTrue(posted.isEmpty())
    }
}
