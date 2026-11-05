package casa.moller.tandem.core.timer

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.notification.TandemNotificationIds
import casa.moller.tandem.core.model.timer.TimerStartResult
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.testing.data.FakeSettingsRepository
import casa.moller.tandem.core.testing.time.TestClock
import casa.moller.tandem.core.timer.alarm.AndroidAlarmScheduler
import casa.moller.tandem.core.timer.alarm.TimerAlarmReceiver
import casa.moller.tandem.core.timer.engine.DefaultTimerController
import casa.moller.tandem.core.timer.engine.TimerEngineConfig
import casa.moller.tandem.core.timer.notification.AndroidTimerNotifier
import casa.moller.tandem.core.timer.ring.AndroidRinger
import casa.moller.tandem.core.timer.ring.TimerRingingService
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Fas 4 (two recipes at once in cook mode, plan 8.1, "Klart när": timers from both are visible
 * and ring): the real engine with a timer of each recipe, under Robolectric like
 * [DefaultTimerControllerTest]. Each timer keeps its recipe's name, which the countdown and the
 * alarm show as their sub text and the ringing service after the timer's name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TwoRecipeTimersTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val clock = TestClock()
    private val system = FakeTimerSystem()
    private val store = MemoryTimerStore()
    private val notificationManager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        TimerRingingService.running = true
    }

    @After
    fun tearDown() {
        TimerRingingService.running = false
    }

    @Test
    fun timersOfTwoRecipesSayTheirRecipeAndBothRing() = runTest {
        val engine = newEngine()
        val pasta = (engine.start("Pastan · 10 min", 10.minutes, 7L, 1, "account-1", "Krämig svamppasta") as TimerStartResult.Started).timer
        val buns = (engine.start("Gräddning · 7 min", 7.minutes, 8L, 2, "account-1", "Kanelbullar") as TimerStartResult.Started).timer

        assertEquals(listOf("Krämig svamppasta", "Kanelbullar"), engine.timers.value.map { it.recipeName })
        assertEquals(listOf("Krämig svamppasta", "Kanelbullar"), store.stored.timers.map { it.recipeName })
        val countdown = notification(TandemNotificationIds.timerOngoing(buns.id))
        assertEquals("Kanelbullar", countdown.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        assertEquals("Gräddning · 7 min", countdown.extras.getCharSequence(Notification.EXTRA_TITLE).toString())

        clock.advanceBy(10.minutes)
        TimerAlarmReceiver.handle(engine, TimerIntents.ACTION_RING, buns.id)

        assertEquals(listOf(TimerState.RINGING, TimerState.RINGING), engine.timers.value.map { it.state })
        val pastaAlarm = notification(TandemNotificationIds.timerAlarm(pasta.id))
        assertEquals("Krämig svamppasta", pastaAlarm.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        assertNotNull(notification(TandemNotificationIds.timerAlarm(buns.id)))
        val service = AndroidTimerNotifier(context, system, clock).ringingServiceNotification(engine.timers.value)
        assertEquals(
            "Pastan · 10 min (Krämig svamppasta), Gräddning · 7 min (Kanelbullar)",
            service.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
    }

    private fun TestScope.newEngine(): DefaultTimerController {
        val engine = DefaultTimerController(
            clock = clock,
            monotonicClock = FakeMonotonicClock(),
            store = store,
            scheduler = AndroidAlarmScheduler(context),
            notifier = AndroidTimerNotifier(context, system, clock),
            ringer = AndroidRinger(context),
            system = system,
            settings = FakeSettingsRepository(),
            scope = backgroundScope,
            config = TimerEngineConfig(inProcessTicker = false),
        )
        runCurrent()
        return engine
    }

    private fun notification(id: Int): Notification =
        requireNotNull(shadowOf(notificationManager).getNotification(id)) { "no notification $id" }
}
