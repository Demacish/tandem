package casa.moller.tandem.core.timer

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.notification.TandemNotificationChannel
import casa.moller.tandem.core.model.notification.TandemNotificationIds
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerStartResult
import casa.moller.tandem.core.testing.data.FakeSettingsRepository
import casa.moller.tandem.core.testing.time.TestClock
import casa.moller.tandem.core.timer.alarm.AndroidAlarmScheduler
import casa.moller.tandem.core.timer.alarm.TimerAlarmReceiver
import casa.moller.tandem.core.timer.engine.DefaultTimerController
import casa.moller.tandem.core.timer.engine.TimerEngineConfig
import casa.moller.tandem.core.timer.notification.AndroidTimerNotifier
import casa.moller.tandem.core.timer.ring.AndroidRinger
import casa.moller.tandem.core.timer.ring.TimerRingingService
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Device test 2026-11-14 (B9): with the phone unlocked and the pop-up style "Brief" (Samsung) the
 * heads-up lasts four seconds, and Stop took three taps because the alarm was folded into a group.
 * The real engine and notifier under Robolectric, like [DefaultTimerControllerTest]: the built
 * alarm notification has no group, category alarm, ongoing, the highest priority, alerts itself
 * and carries "+1 min" and "Stop"; the ringing service's notification is quiet and apart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AlarmNotificationTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val clock = TestClock()
    private val system = FakeTimerSystem()
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
    fun aRingingAlarmStandsOnItsOwnAboveTheServiceNotification() = runTest {
        // Device test 2026-11-14 (B9): unlocked phone, app in the background, pop-up style "Brief".
        val engine = newEngine()
        val other = engine.startTimer("Pastavattnet", 5.minutes)
        val timer = engine.startTimer("Svampen", 1.minutes)
        clock.advanceBy(1.minutes)

        TimerAlarmReceiver.handle(engine, TimerIntents.ACTION_RING, timer.id)

        val alarm = notification(TandemNotificationIds.timerAlarm(timer.id))
        assertNull("not in a group Android could fold away", alarm.group)
        assertEquals(0, alarm.flags and Notification.FLAG_GROUP_SUMMARY)
        assertEquals(Notification.CATEGORY_ALARM, alarm.category)
        assertTrue(alarm.flags and Notification.FLAG_ONGOING_EVENT != 0)
        @Suppress("DEPRECATION")
        assertEquals("sorts first in the shade", Notification.PRIORITY_MAX, alarm.priority)
        assertEquals("the alarm itself alerts", Notification.GROUP_ALERT_ALL, alarm.groupAlertBehavior)
        assertTrue(alarm.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals(listOf("+1 min", "Stop"), alarm.actions.map { it.title.toString() })
        assertEquals(Notification.VISIBILITY_PUBLIC, alarm.visibility)

        val service = AndroidTimerNotifier(context, system, clock).ringingServiceNotification(engine.timers.value)
        assertEquals("the quiet section, never bundled with the alarm", TandemNotificationChannel.TIMER_ONGOING.id, service.channelId)
        @Suppress("DEPRECATION")
        assertEquals(Notification.PRIORITY_LOW, service.priority)
        // NotificationCompat puts a silent notification without a group into its "silent" group.
        assertNotEquals(alarm.group, service.group)
        assertNotNull("the other timer still counts down", notificationOrNull(TandemNotificationIds.timerOngoing(other.id)))
    }

    @Test
    fun aQuietNoticeKeepsTheAlarmsGroupButARingingAlarmDoesNot() = runTest {
        val engine = newEngine()
        val timer = engine.startTimer("Ugnen", 20.minutes)
        clock.advanceBy(20.minutes)
        TimerAlarmReceiver.handle(engine, TimerIntents.ACTION_RING, timer.id)
        assertNull(notification(TandemNotificationIds.timerAlarm(timer.id)).group)

        clock.advanceBy(TimerEngineConfig.DEFAULT_RING_TIMEOUT)
        TimerAlarmReceiver.handle(engine, TimerIntents.ACTION_AUTO_SILENCE, timer.id)

        assertEquals(AndroidTimerNotifier.ALARM_GROUP, notification(TandemNotificationIds.timerAlarm(timer.id)).group)
    }

    private fun TestScope.newEngine(): DefaultTimerController {
        val engine = DefaultTimerController(
            clock = clock,
            monotonicClock = FakeMonotonicClock(),
            store = MemoryTimerStore(),
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

    private suspend fun DefaultTimerController.startTimer(label: String, duration: Duration): CookTimer =
        (start(label, duration, null, null) as TimerStartResult.Started).timer

    private fun notificationOrNull(id: Int): Notification? = shadowOf(notificationManager).getNotification(id)

    private fun notification(id: Int): Notification = requireNotNull(notificationOrNull(id)) { "no notification $id" }
}
