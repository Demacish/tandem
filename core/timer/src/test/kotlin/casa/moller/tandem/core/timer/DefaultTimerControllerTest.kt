package casa.moller.tandem.core.timer

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.media.AudioAttributes
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.notification.TandemNotificationChannel
import casa.moller.tandem.core.model.notification.TandemNotificationIds
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerMode
import casa.moller.tandem.core.model.timer.TimerStartResult
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.testing.data.FakeSettingsRepository
import casa.moller.tandem.core.testing.time.TestClock
import casa.moller.tandem.core.timer.alarm.AndroidAlarmScheduler
import casa.moller.tandem.core.timer.alarm.TimerAlarmReceiver
import casa.moller.tandem.core.timer.engine.DefaultTimerController
import casa.moller.tandem.core.timer.engine.SystemEvent
import casa.moller.tandem.core.timer.engine.TimerEngineConfig
import casa.moller.tandem.core.timer.notification.AndroidTimerNotifier
import casa.moller.tandem.core.timer.notification.TimerChannelInstaller
import casa.moller.tandem.core.timer.ring.AndroidRinger
import casa.moller.tandem.core.timer.ring.TimerRingingService
import casa.moller.tandem.core.timer.store.DataStoreTimerStore
import casa.moller.tandem.core.timer.store.StoredTimers
import casa.moller.tandem.core.timer.store.TimerStore
import casa.moller.tandem.core.timer.store.timerDataStore
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The real engine with the real Android alarm scheduler, notifier and ringer under Robolectric
 * (ShadowAlarmManager, ShadowNotificationManager), a fake wall clock and a fake monotonic clock.
 * Alarms are "fired" by calling the receiver's handler, as the system would deliver them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DefaultTimerControllerTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val clock = TestClock()
    private val monotonic = FakeMonotonicClock()
    private val settings = FakeSettingsRepository()
    private val system = FakeTimerSystem()
    private val store = MemoryTimerStore()
    private val alarmManager: AlarmManager get() = context.getSystemService(AlarmManager::class.java)
    private val notificationManager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        // The sideload build: USE_EXACT_ALARM is granted, notifications were allowed at the first timer.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    // --- start, countdown notification, scheduling ---

    @Test
    fun startSchedulesAnExactAlarmClockAndShowsTheCountdown() = runTest {
        val engine = newEngine()
        val timer = engine.startTimer("Koka pastan", 10.minutes, recipeId = 7L, stepIndex = 2)

        assertEquals(listOf(timer), engine.timers.value)
        assertEquals(clock.now() + 10.minutes, timer.endsAt)
        val alarm = ringAlarms().single()
        assertEquals((clock.now() + 10.minutes).toEpochMilliseconds(), alarm.triggerAtMs)
        assertNotNull("an exact alarm clock (never deferred by Doze)", alarm.alarmClockInfo)
        assertEquals(timer.id, shadowOf(alarm.operationIntent()).savedIntent.getLongExtra(TimerIntents.EXTRA_TIMER_ID, -1))

        val countdown = notification(TandemNotificationIds.timerOngoing(timer.id))
        assertEquals(TandemNotificationChannel.TIMER_ONGOING.id, countdown.channelId)
        assertEquals("Koka pastan", countdown.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(countdown.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(countdown.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(countdown.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertEquals(timer.endsAt!!.toEpochMilliseconds(), countdown.`when`)
        assertTrue("asks to be a Live Update", NotificationCompat.isRequestPromotedOngoing(countdown))
        assertEquals(listOf("Pause", "+1 min"), countdown.actions.map { it.title.toString() })
        assertEquals(
            listOf(TimerIntents.ACTION_PAUSE, TimerIntents.ACTION_PLUS_ONE_MINUTE),
            countdown.actions.map { shadowOf(it.actionIntent).savedIntent.action },
        )
        assertNull("no alarm notification while counting down", notificationOrNull(TandemNotificationIds.timerAlarm(timer.id)))
        assertNull("no service during the countdown", shadowOf(context).peekNextStartedService())
        assertEquals(1, store.stored.timers.size)
    }

    @Test
    fun theChannelsAreCreatedWithTheirFixedIdsAndAlarmSound() = runTest {
        val engine = newEngine()
        engine.startTimer("Ägg", 6.minutes)

        val alarm = notificationManager.getNotificationChannel(TandemNotificationChannel.TIMER_ALARM.id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, alarm.importance)
        assertEquals(AudioAttributes.USAGE_ALARM, alarm.audioAttributes.usage)
        assertNotNull(alarm.sound)
        assertTrue(alarm.shouldVibrate())
        assertEquals("Timers (alarm)", alarm.name.toString())

        val ongoing = notificationManager.getNotificationChannel(TandemNotificationChannel.TIMER_ONGOING.id)
        assertEquals(NotificationManager.IMPORTANCE_LOW, ongoing.importance)
        assertNull("silent", ongoing.sound)
        assertFalse(ongoing.shouldVibrate())
    }

    @Test
    fun playWithoutExactAlarmsSchedulesAnInexactAlarm() = runTest {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        system.exactAlarms = false
        val engine = newEngine()
        engine.startTimer("Bröd", 40.minutes)

        val alarm = ringAlarms().single()
        assertNull("no alarm clock without the permission", alarm.alarmClockInfo)
        assertTrue(alarm.isAllowWhileIdle)
        assertTrue("inexact", alarm.windowLengthMs != 0L)
        assertTrue("the UI warns", engine.capabilities.value.alarmMayBeLate)
        assertTrue(engine.capabilities.value.exactAlarmsRequestable)
    }

    @Test
    fun startRefusesBadInputAndMoreThanTheMaximum() = runTest {
        val engine = newEngine()
        assertEquals(TimerStartResult.Invalid, engine.start(" ", 5.minutes))
        assertEquals(TimerStartResult.Invalid, engine.start("Kort", Duration.ZERO))
        repeat(CookTimer.MAX_PARALLEL_TIMERS) { engine.startTimer("Timer $it", (it + 1).minutes) }
        assertEquals(TimerStartResult.TooManyTimers(CookTimer.MAX_PARALLEL_TIMERS), engine.start("En till", 1.minutes))
        assertEquals(CookTimer.MAX_PARALLEL_TIMERS, engine.timers.value.size)
        assertEquals("ids are unique", CookTimer.MAX_PARALLEL_TIMERS, engine.timers.value.map { it.id }.toSet().size)
    }

    // --- pause / resume / +1 min ---

    @Test
    fun pauseAndResumeKeepTheTimeLeft() = runTest {
        val engine = newEngine()
        val timer = engine.startTimer("Risotto", 10.minutes)
        clock.advanceBy(3.minutes)

        engine.pause(timer.id)
        val paused = engine.timers.value.single()
        assertEquals(TimerState.PAUSED, paused.state)
        assertEquals(7.minutes, paused.remaining(clock.now()))
        assertTrue("no alarm while paused", ringAlarms().isEmpty())
        val countdown = notification(TandemNotificationIds.timerOngoing(timer.id))
        assertFalse(countdown.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals("Paused · 07:00 left", countdown.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("Resume", "Cancel"), countdown.actions.map { it.title.toString() })

        clock.advanceBy(5.minutes)
        assertEquals("time does not run while paused", 7.minutes, engine.timers.value.single().remaining(clock.now()))
        engine.resume(timer.id)
        val resumed = engine.timers.value.single()
        assertEquals(TimerState.RUNNING, resumed.state)
        assertEquals(clock.now() + 7.minutes, resumed.endsAt)
        assertEquals((clock.now() + 7.minutes).toEpochMilliseconds(), ringAlarms().single().triggerAtMs)
    }

    @Test
    fun plusOneMinuteMovesARunningAlarm() = runTest {
        val engine = newEngine()
        val timer = engine.startTimer("Lax", 12.minutes)
        clock.advanceBy(2.minutes)

        TimerAlarmReceiver.handle(engine, TimerIntents.ACTION_PLUS_ONE_MINUTE, timer.id)

        val longer = engine.timers.value.single()
        assertEquals(13.minutes, longer.duration)
        assertEquals(timer.endsAt!! + 1.minutes, longer.endsAt)
        assertEquals(longer.endsAt!!.toEpochMilliseconds(), ringAlarms().single().triggerAtMs)
    }

    // --- ringing ---

    @Test
    fun aDueTimerRingsWithAnAlarmNotificationAndTheRingingService() = runTest {
        val engine = newEngine()
        val timer = engine.startTimer("Koka pastan", 1.minutes, recipeId = 7L, stepIndex = 2)
        clock.advanceBy(1.minutes)

        TimerAlarmReceiver.handle(engine, TimerIntents.ACTION_RING, timer.id)

        val ringing = engine.timers.value.single()
        assertEquals(TimerState.RINGING, ringing.state)
        assertNull("the countdown is replaced by the alarm", notificationOrNull(TandemNotificationIds.timerOngoing(timer.id)))
        val alarm = notification(TandemNotificationIds.timerAlarm(timer.id))
        assertEquals(TandemNotificationChannel.TIMER_ALARM.id, alarm.channelId)
        assertEquals(Notification.CATEGORY_ALARM, alarm.category)
        assertTrue("the sound repeats until stopped", alarm.flags and Notification.FLAG_INSISTENT != 0)
        assertNotNull("full screen on a locked screen (sideload)", alarm.fullScreenIntent)
        assertEquals(listOf("+1 min", "Stop"), alarm.actions.map { it.title.toString() })
        assertEquals(
            listOf(TimerIntents.ACTION_PLUS_ONE_MINUTE, TimerIntents.ACTION_STOP),
            alarm.actions.map { shadowOf(it.actionIntent).savedIntent.action },
        )
        assertEquals(TimerRingingService::class.java.name, shadowOf(context).nextStartedService.component?.className)
        assertTrue("no ring alarm left", ringAlarms().isEmpty())
        val silence = autoSilenceAlarms().single()
        assertEquals((ringing.endsAt!! + TimerEngineConfig.DEFAULT_RING_TIMEOUT).toEpochMilliseconds(), silence.triggerAtMs)
    }

    @Test
    fun playHasNoFullScreenAlarm() = runTest {
        system.fullScreen = false
        val engine = newEngine()
        val timer = engine.startTimer("Ägg", 30.seconds)
        clock.advanceBy(30.seconds)
        engine.onAlarm()

        val alarm = notification(TandemNotificationIds.timerAlarm(timer.id))
        assertNull("a heads-up notification instead", alarm.fullScreenIntent)
        assertTrue(alarm.flags and Notification.FLAG_INSISTENT != 0)
    }

    @Test
    fun twoTimersRingAtTheSameTimeAndStopOneByOne() = runTest {
        val engine = newEngine()
        val pasta = engine.startTimer("Pasta", 8.minutes)
        val sauce = engine.startTimer("Sås", 8.minutes)
        assertEquals(2, ringAlarms().size)
        clock.advanceBy(8.minutes)

        // Both alarms are delivered; the first already rings both, the second changes nothing.
        TimerAlarmReceiver.handle(engine, TimerIntents.ACTION_RING, pasta.id)
        TimerAlarmReceiver.handle(engine, TimerIntents.ACTION_RING, sauce.id)

        assertEquals(listOf(TimerState.RINGING, TimerState.RINGING), engine.timers.value.map { it.state })
        assertNotNull(notification(TandemNotificationIds.timerAlarm(pasta.id)))
        assertNotNull(notification(TandemNotificationIds.timerAlarm(sauce.id)))
        assertEquals(TimerRingingService::class.java.name, shadowOf(context).nextStartedService.component?.className)

        TimerAlarmReceiver.handle(engine, TimerIntents.ACTION_STOP, pasta.id)
        assertEquals(listOf(TimerState.FINISHED, TimerState.RINGING), engine.timers.value.map { it.state })
        assertNull(notificationOrNull(TandemNotificationIds.timerAlarm(pasta.id)))
        assertNotNull("the other one still rings", notificationOrNull(TandemNotificationIds.timerAlarm(sauce.id)))
        assertNull("the service keeps running", shadowOf(context).nextStoppedService)

        engine.stopRinging(sauce.id)
        assertNull(notificationOrNull(TandemNotificationIds.timerAlarm(sauce.id)))
        assertEquals(TimerRingingService::class.java.name, shadowOf(context).nextStoppedService?.component?.className)

        engine.clearFinished()
        assertEquals(emptyList<CookTimer>(), engine.timers.value)
    }

    @Test
    fun stopAllSilencesEveryRingingTimer() = runTest {
        val engine = newEngine()
        engine.startTimer("A", 1.minutes)
        engine.startTimer("B", 1.minutes)
        engine.startTimer("C", 5.minutes)
        clock.advanceBy(1.minutes)
        engine.onAlarm()

        TimerAlarmReceiver.handle(engine, TimerIntents.ACTION_STOP_ALL, null)

        assertEquals(listOf(TimerState.FINISHED, TimerState.FINISHED, TimerState.RUNNING), engine.timers.value.map { it.state })
    }

    @Test
    fun plusOneMinuteOnARingingTimerRunsItAgain() = runTest {
        val engine = newEngine()
        val timer = engine.startTimer("Bröd", 30.minutes)
        clock.advanceBy(30.minutes)
        engine.onAlarm()
        clock.advanceBy(10.seconds)

        TimerAlarmReceiver.handle(engine, TimerIntents.ACTION_PLUS_ONE_MINUTE, timer.id)

        val again = engine.timers.value.single()
        assertEquals(TimerState.RUNNING, again.state)
        assertEquals(clock.now() + 1.minutes, again.endsAt)
        assertEquals(31.minutes, again.duration)
        assertNull(notificationOrNull(TandemNotificationIds.timerAlarm(timer.id)))
        assertNotNull(notification(TandemNotificationIds.timerOngoing(timer.id)))
        assertEquals((clock.now() + 1.minutes).toEpochMilliseconds(), ringAlarms().single().triggerAtMs)
        assertTrue(autoSilenceAlarms().isEmpty())
        assertEquals(TimerRingingService::class.java.name, shadowOf(context).nextStoppedService?.component?.className)
    }

    @Test
    fun anAlarmThatRingsTooLongIsSilencedAndLeavesAQuietNotice() = runTest {
        val engine = newEngine()
        val timer = engine.startTimer("Ugnen", 20.minutes)
        clock.advanceBy(20.minutes)
        engine.onAlarm()

        clock.advanceBy(TimerEngineConfig.DEFAULT_RING_TIMEOUT)
        TimerAlarmReceiver.handle(engine, TimerIntents.ACTION_AUTO_SILENCE, timer.id)

        assertEquals(TimerState.FINISHED, engine.timers.value.single().state)
        val notice = notification(TandemNotificationIds.timerAlarm(timer.id))
        assertEquals(0, notice.flags and Notification.FLAG_INSISTENT)
        assertTrue(notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("Rang at "))
        assertEquals(TimerRingingService::class.java.name, shadowOf(context).nextStoppedService?.component?.className)
    }

    @Test
    fun cancelRemovesTheAlarmAndTheNotification() = runTest {
        val engine = newEngine()
        val timer = engine.startTimer("Kaffe", 4.minutes)

        TimerAlarmReceiver.handle(engine, TimerIntents.ACTION_CANCEL, timer.id)

        assertEquals(emptyList<CookTimer>(), engine.timers.value)
        assertTrue(ringAlarms().isEmpty())
        assertNull(notificationOrNull(TandemNotificationIds.timerOngoing(timer.id)))
        assertEquals(emptyList<Any>(), store.stored.timers)
    }

    // --- without the notification permission ---

    @Test
    fun withoutNotificationsTimersStillRunAndRing() = runTest {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        system.notifications = false
        val engine = newEngine()
        assertTrue(engine.capabilities.value.shouldAskForNotifications)

        val timer = engine.startTimer("Ägg", 6.minutes)
        assertEquals(0, shadowOf(notificationManager).size())
        assertEquals(1, ringAlarms().size)

        clock.advanceBy(6.minutes)
        engine.onAlarm()
        assertEquals(TimerState.RINGING, engine.timers.value.single { it.id == timer.id }.state)
        assertEquals(0, shadowOf(notificationManager).size())
        val service = shadowOf(context).nextStartedService
        assertEquals("the service rings instead", TimerRingingService::class.java.name, service.component?.className)
    }

    @Test
    fun theNotificationAnswerIsRememberedAndShowsRunningTimers() = runTest {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        system.notifications = false
        val engine = newEngine()
        val timer = engine.startTimer("Ägg", 6.minutes)

        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        system.notifications = true
        engine.onNotificationPermissionResult(granted = true)

        assertTrue(settings.state.value.notificationPermissionAsked)
        assertTrue(engine.capabilities.value.notificationPermissionAsked)
        assertFalse(engine.capabilities.value.shouldAskForNotifications)
        assertNotNull(notification(TandemNotificationIds.timerOngoing(timer.id)))
    }

    @Test
    fun aDeniedAnswerIsNeverAskedAgain() = runTest {
        system.notifications = false
        val engine = newEngine()
        engine.onNotificationPermissionResult(granted = false)
        assertTrue(settings.state.value.notificationPermissionAsked)
        assertFalse(engine.capabilities.value.shouldAskForNotifications)
        assertTrue(engine.capabilities.value.alarmMayBeSilent)
    }

    // --- clock app ---

    @Test
    fun clockAppModeHandsTheTimerOver() = runTest {
        settings.state.value = AppSettings.Default.copy(timerMode = TimerMode.CLOCK_APP)
        val engine = newEngine()

        assertEquals(TimerStartResult.HandedToClockApp, engine.start("Ägg", 6.minutes))
        assertEquals(listOf("Ägg" to 6.minutes), system.clockAppTimers)
        assertEquals("Tandem does not track it", emptyList<CookTimer>(), engine.timers.value)
        assertTrue(ringAlarms().isEmpty())

        system.clockApp = false
        assertEquals(TimerStartResult.ClockAppUnavailable, engine.start("Ägg", 6.minutes))
    }

    // --- persistence, reboot, clock change ---

    @Test
    fun timersSurviveARecreatedEngine() = runTest {
        val file = File(context.filesDir, "datastore/${DataStoreTimerStore.FILE_NAME}")
        val firstJob = Job()
        val first = newEngine(dataStoreOn(file, CoroutineScope(Dispatchers.IO + firstJob)))
        val pasta = first.startTimer("Pasta", 10.minutes, recipeId = 3L, stepIndex = 1)
        val sauce = first.startTimer("Sås", 20.minutes)
        clock.advanceBy(4.minutes)
        first.pause(sauce.id)
        val before = first.timers.value
        firstJob.cancelAndJoin()

        // A new process: the stored timers come back as they were, ids keep counting.
        val second = newEngine(dataStoreOn(file, CoroutineScope(Dispatchers.IO + Job())))
        assertEquals(before, second.loadedTimers())
        assertEquals(TimerState.RUNNING, second.timers.value.first { it.id == pasta.id }.state)
        assertEquals(16.minutes, second.timers.value.first { it.id == sauce.id }.remaining(clock.now()))
        val third = second.startTimer("Ris", 15.minutes)
        assertTrue(third.id > sauce.id)
    }

    @Test
    fun aDamagedTimersFileReadsAsEmpty() = runTest {
        val file = File(context.filesDir, "datastore/damaged-timers.json")
        file.parentFile?.mkdirs()
        file.writeText("{ not json")
        val engine = newEngine(dataStoreOn(file, CoroutineScope(Dispatchers.IO + Job())))
        assertEquals(emptyList<CookTimer>(), engine.loadedTimers())
        engine.startTimer("Ägg", 6.minutes)
        assertEquals(1, engine.timers.value.size)
    }

    @Test
    fun rebootReArmsAndRingsTimersThatEndedWhileThePhoneWasOff() = runTest {
        val start = clock.now()
        store.stored = StoredTimers.of(
            nextId = 3L,
            timers = listOf(
                CookTimer.start(1L, "Långkok", 3.hours, start),
                CookTimer.start(2L, "Ägg", 10.minutes, start),
            ),
        )
        clock.advanceBy(30.minutes)
        // After a reboot no alarm is left and the process is new.
        val engine = newEngine()
        clearAlarms()

        engine.onSystemEvent(SystemEvent.BOOT)

        assertEquals(listOf(TimerState.RUNNING, TimerState.RINGING), engine.timers.value.map { it.state })
        assertEquals((start + 3.hours).toEpochMilliseconds(), ringAlarms().single().triggerAtMs)
        assertNotNull("the egg timer rings at once", notification(TandemNotificationIds.timerAlarm(2L)))
        assertNotNull(notification(TandemNotificationIds.timerOngoing(1L)))
    }

    @Test
    fun aTimerThatWasRingingLongBeforeTheRebootIsOnlyNoted() = runTest {
        val start = clock.now()
        val rang = CookTimer.start(1L, "Ägg", 10.minutes, start).ring(start + 10.minutes)
        store.stored = StoredTimers.of(nextId = 2L, timers = listOf(rang))
        clock.advanceBy(2.hours)
        val engine = newEngine()

        engine.onSystemEvent(SystemEvent.BOOT)

        assertEquals(TimerState.FINISHED, engine.timers.value.single().state)
        val notice = notification(TandemNotificationIds.timerAlarm(1L))
        assertEquals("no alarm sound for an old alarm", 0, notice.flags and Notification.FLAG_INSISTENT)
    }

    @Test
    fun aLateAlarmRingsItsFullTimeBeforeItIsSilenced() = runTest {
        // Play build without exact alarms: Doze delivered the alarm 10 minutes late.
        val engine = newEngine()
        val timer = engine.startTimer("Bröd", 30.minutes)
        clock.advanceBy(40.minutes)
        engine.onAlarm()

        val ringing = engine.timers.value.single()
        assertEquals(TimerState.RINGING, ringing.state)
        assertEquals("rings from now", clock.now(), ringing.endsAt)
        val silenceAt = clock.now() + TimerEngineConfig.DEFAULT_RING_TIMEOUT
        assertEquals(silenceAt.toEpochMilliseconds(), autoSilenceAlarms().single().triggerAtMs)
        assertNotNull(notification(TandemNotificationIds.timerAlarm(timer.id)))
    }

    @Test
    fun aManualClockChangeKeepsTheTimeLeft() = runTest {
        val engine = newEngine()
        val timer = engine.startTimer("Bröd", 10.minutes)
        clock.advanceBy(2.minutes)
        monotonic.advanceBy(2.minutes)

        // The user sets the clock one hour ahead; the bread still needs 8 minutes.
        clock.set(clock.now() + 1.hours)
        engine.onSystemEvent(SystemEvent.CLOCK_CHANGED)

        val moved = engine.timers.value.single { it.id == timer.id }
        assertEquals(TimerState.RUNNING, moved.state)
        assertEquals(clock.now() + 8.minutes, moved.endsAt)
        assertEquals((clock.now() + 8.minutes).toEpochMilliseconds(), ringAlarms().single().triggerAtMs)
    }

    @Test
    fun aRecreatedEngineReArmsOnStart() = runTest {
        val first = newEngine()
        val timer = first.startTimer("Pasta", 10.minutes)
        clearAlarms()

        // App update / process start: the init reconcile schedules the stored timer again.
        newEngine()

        assertEquals(timer.endsAt!!.toEpochMilliseconds(), ringAlarms().single().triggerAtMs)
    }

    @Test
    fun theAppStartupCreatesBothTimerChannelsWithoutPostingAnything() {
        // Phase 1b integration: TandemApplication runs every NotificationChannelInstaller once.
        TimerChannelInstaller(AndroidTimerNotifier(context, system, clock)).createChannels()

        val alarm = notificationManager.getNotificationChannel(TandemNotificationChannel.TIMER_ALARM.id)
        val ongoing = notificationManager.getNotificationChannel(TandemNotificationChannel.TIMER_ONGOING.id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, alarm.importance)
        assertEquals(AudioAttributes.USAGE_ALARM, alarm.audioAttributes.usage)
        assertEquals(NotificationManager.IMPORTANCE_LOW, ongoing.importance)
        assertNull("the countdown is silent", ongoing.sound)
        assertTrue("creating channels posts nothing", notificationManager.activeNotifications.isEmpty())
    }

    // --- helpers ---

    private fun TestScope.newEngine(timerStore: TimerStore = store): DefaultTimerController {
        val engine = DefaultTimerController(
            clock = clock,
            monotonicClock = monotonic,
            store = timerStore,
            scheduler = AndroidAlarmScheduler(context),
            notifier = AndroidTimerNotifier(context, system, clock),
            ringer = AndroidRinger(context),
            system = system,
            settings = settings,
            scope = backgroundScope,
            config = TimerEngineConfig(inProcessTicker = false),
        )
        runCurrent()
        return engine
    }

    /** The production DataStore setup (with its corruption handler) on [file]. */
    private fun dataStoreOn(file: File, scope: CoroutineScope): TimerStore = DataStoreTimerStore(timerDataStore(scope) { file })

    private suspend fun DefaultTimerController.startTimer(
        label: String,
        duration: Duration,
        recipeId: Long? = null,
        stepIndex: Int? = null,
    ): CookTimer {
        val result = start(label, duration, recipeId, stepIndex)
        return (result as TimerStartResult.Started).timer
    }

    private fun alarms(action: String): List<ShadowAlarmManager.ScheduledAlarm> =
        shadowOf(alarmManager).scheduledAlarms.filter { shadowOf(it.operationIntent()).savedIntent.action == action }

    private fun ringAlarms() = alarms(TimerIntents.ACTION_RING)

    private fun autoSilenceAlarms() = alarms(TimerIntents.ACTION_AUTO_SILENCE)

    @Suppress("DEPRECATION")
    private fun ShadowAlarmManager.ScheduledAlarm.operationIntent() = operation!!

    private fun clearAlarms() {
        shadowOf(alarmManager).scheduledAlarms.forEach { alarmManager.cancel(it.operationIntent()) }
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
    }

    private fun notificationOrNull(id: Int): Notification? = shadowOf(notificationManager).getNotification(id)

    private fun notification(id: Int): Notification = requireNotNull(notificationOrNull(id)) { "no notification $id" }
}
