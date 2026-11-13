package casa.moller.tandem.core.sync.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import casa.moller.tandem.core.domain.pantry.ExpiryReminderSummary
import casa.moller.tandem.core.domain.reminder.DailyRun
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.pantry.PantryExpiryCheck
import casa.moller.tandem.core.sync.pantry.PantryExpiryReminders
import casa.moller.tandem.core.sync.platform.AppVisibility
import casa.moller.tandem.core.sync.platform.PantryExpiryNotifier
import casa.moller.tandem.core.sync.reminder.DailyReminderJob
import casa.moller.tandem.core.sync.reminder.DailyReminderKind
import casa.moller.tandem.core.sync.reminder.DailyRunInput
import casa.moller.tandem.core.sync.reminder.FixedJob
import casa.moller.tandem.core.sync.reminder.RecordingReminderScheduler
import casa.moller.tandem.core.sync.reminder.ReminderClock
import casa.moller.tandem.core.sync.reminder.WorkManagerDailyReminderScheduler
import casa.moller.tandem.core.sync.reminder.reminderWorker
import casa.moller.tandem.core.sync.testing.FakeSettingsStore
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Fas 4 (skafferinotis lane), Fas 5 (planera lane): the WorkManager side of the morning expiry
 * check: the worker runs the check and always succeeds, the request (one-time, battery not low, no
 * network needed, at the chosen time of the zone), the next run planned by each run (also across
 * the October daylight-saving change), the retry when Tandem was on screen, and the plan following
 * the setting, its time and the account.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PantryExpiryWorkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val stockholm = TimeZone.of("Europe/Stockholm")

    private fun local(time: String): Instant = LocalDateTime.parse(time).toInstant(stockholm)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @Test
    fun theRequestIsOneTimeAtTheChosenTimeWithoutNeedingANetwork() {
        val clock = ReminderClock(local("2026-11-05T21:00"), stockholm)
        val scheduler = WorkManagerDailyReminderScheduler(context, clock)
        val request = scheduler.request(DailyReminderKind.PANTRY_EXPIRY, DailyRun(LocalDate(2026, 11, 6), LocalTime(8, 0)))
        assertEquals(11.hours.inWholeMilliseconds, request.workSpec.initialDelay)
        assertTrue(!request.workSpec.isPeriodic)
        assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
        assertEquals(NetworkType.NOT_REQUIRED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(PantryExpiryWorker::class.java.name, request.workSpec.workerClassName)
        assertEquals(DailyRun(LocalDate(2026, 11, 6), LocalTime(8, 0)), DailyRunInput.runOf(request.workSpec.input))
        assertTrue(DailyReminderKind.PANTRY_EXPIRY.tag in request.tags)
    }

    @Test
    fun theWorkerRunsTheCheckAlwaysSucceedsAndPlansTheNextMorning() {
        val graph = SyncTestGraph()
        try {
            val posted = mutableListOf<ExpiryReminderSummary>()
            val notifier = object : PantryExpiryNotifier {
                override fun canPost() = true

                override fun post(summary: ExpiryReminderSummary, today: LocalDate, locationNames: Map<Long, String>): Boolean =
                    posted.add(summary)
            }
            val clock = ReminderClock(local("2026-10-06T08:00"), stockholm)
            val check = PantryExpiryCheck(
                settings = graph.settings,
                sources = graph.sources,
                databases = graph.databases,
                pantry = graph.pantry,
                makeNow = graph.makeNow,
                reader = graph.pantryReader,
                network = graph.network,
                visibility = AppVisibility { false },
                notifier = notifier,
                clock = clock,
            )
            val scheduler = RecordingReminderScheduler(clock)
            val job = PantryExpiryJob(check, graph.settings, graph.sources, clock)
            val factory = object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = PantryExpiryWorker(appContext, workerParameters, job, scheduler, clock)
            }
            val worker = TestListenableWorkerBuilder<PantryExpiryWorker>(context)
                .setWorkerFactory(factory)
                .setInputData(DailyRunInput.of(DailyRun(LocalDate(2026, 10, 6), LocalTime(8, 0)), retry = false))
                .build()

            // Off (the default): nothing, still a success, and nothing planned (the setting cancels the plan).
            assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
            assertEquals(emptyList<String>(), scheduler.calls)
            // On, with nothing in the pantry: nothing posted, still a success, the next morning planned.
            graph.settings.state.update { it.copy(pantryExpiryNotifications = true) }
            assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
            assertTrue(posted.isEmpty())
            assertEquals(listOf("after PANTRY_EXPIRY 2026-10-07 08:00"), scheduler.calls)
        } finally {
            graph.close()
        }
    }

    @Test
    fun theOctoberChangeKeepsTheMorningAtEight() {
        // Saturday 24 October 08:00 CEST: the next run is Sunday 08:00 CET, 25 hours later (Fas 4's bug: 07:00).
        val clock = ReminderClock(local("2026-10-24T08:00"), stockholm)
        val scheduler = RecordingReminderScheduler(clock)
        val job = FixedJob(DailyReminderKind.PANTRY_EXPIRY, LocalTime(8, 0))
        runBlocking { worker(job, scheduler, clock, DailyRun(LocalDate(2026, 10, 24), LocalTime(8, 0))).doWork() }
        assertEquals(listOf("after PANTRY_EXPIRY 2026-10-25 08:00"), scheduler.calls)
        val request = WorkManagerDailyReminderScheduler(context, clock)
            .request(DailyReminderKind.PANTRY_EXPIRY, DailyRun(LocalDate(2026, 10, 25), LocalTime(8, 0)))
        assertEquals(25.hours.inWholeMilliseconds, request.workSpec.initialDelay)
    }

    @Test
    fun aRunThatFoundTheAppOnScreenTriesAgainTheSameDay() {
        val clock = ReminderClock(local("2026-11-05T08:00"), stockholm)
        val scheduler = RecordingReminderScheduler(clock)
        val job = FixedJob(DailyReminderKind.PANTRY_EXPIRY, LocalTime(8, 0), retry = DailyRun(LocalDate(2026, 11, 5), LocalTime(10, 0)))
        runBlocking { worker(job, scheduler, clock, DailyRun(LocalDate(2026, 11, 5), LocalTime(8, 0))).doWork() }
        assertEquals(listOf("after PANTRY_EXPIRY 2026-11-05 10:00 retry"), scheduler.calls)
    }

    @Test
    fun theScheduleFollowsTheSettingItsTimeAndTheAccount() {
        val graph = SyncTestGraph()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val settings = FakeSettingsStore(AppSettings.Default)
            val scheduler = RecordingReminderScheduler(ReminderClock(local("2026-11-05T12:00"), stockholm))
            PantryExpiryReminders(settings, graph.sources, scheduler, scope).start()
            waitFor { scheduler.calls == listOf("cancel PANTRY_EXPIRY") }

            settings.state.update { it.copy(pantryExpiryNotifications = true) }
            waitFor { scheduler.calls.lastOrNull() == "ensure PANTRY_EXPIRY 08:00" }
            // A new time plans again at it.
            settings.state.update { it.copy(pantryExpiryTime = LocalTime(6, 30)) }
            waitFor { scheduler.calls.lastOrNull() == "ensure PANTRY_EXPIRY 06:30" }

            // Demo mode and logging out cancel it; back to the account plans it again.
            graph.sources.source.value = ActiveSource.Demo
            waitFor { scheduler.calls.lastOrNull() == "cancel PANTRY_EXPIRY" }
            graph.sources.source.value = graph.remote
            waitFor { scheduler.calls.lastOrNull() == "ensure PANTRY_EXPIRY 06:30" }
            settings.state.update { it.copy(pantryExpiryNotifications = false) }
            waitFor { scheduler.calls.lastOrNull() == "cancel PANTRY_EXPIRY" }
        } finally {
            runBlocking { scope.coroutineContext.job.cancelAndJoin() }
            graph.close()
        }
    }

    @Test
    fun fas4sPeriodicCheckIsReplacedByTheDailyOne() {
        val clock = ReminderClock(local("2026-11-05T12:00"), stockholm)
        val scheduler = WorkManagerDailyReminderScheduler(context, clock)
        val manager = WorkManager.getInstance(context)
        // What 0.6.0 left behind.
        manager.enqueueUniquePeriodicWork(
            WorkManagerDailyReminderScheduler.LEGACY_PANTRY_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PantryExpiryWorker>(24, TimeUnit.HOURS).build(),
        )
        runBlocking { scheduler.ensure(DailyReminderKind.PANTRY_EXPIRY, LocalTime(8, 0)) }
        val legacy = manager.getWorkInfosForUniqueWork(WorkManagerDailyReminderScheduler.LEGACY_PANTRY_WORK_NAME).get()
        assertTrue(legacy.all { it.state == WorkInfo.State.CANCELLED })
        val planned = manager.getWorkInfosForUniqueWork(DailyReminderKind.PANTRY_EXPIRY.workName).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, planned.state)
        assertEquals(local("2026-11-06T08:00"), DailyRunInput.plannedOf(planned.tags)?.at)
    }

    private fun worker(job: DailyReminderJob, scheduler: RecordingReminderScheduler, clock: ReminderClock, run: DailyRun?) =
        reminderWorker(context, job, scheduler, clock, run)

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
    }
}
