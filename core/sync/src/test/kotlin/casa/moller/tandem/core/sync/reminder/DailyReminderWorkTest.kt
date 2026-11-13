package casa.moller.tandem.core.sync.reminder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import casa.moller.tandem.core.domain.reminder.DailyRun
import casa.moller.tandem.core.sync.work.MealReminderWorker
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Fas 5 (planera lane): the scheduler shared by the morning and the evening reminder, on the test
 * WorkManager: one-time runs at a local date and time, a plan kept across app starts, replaced for
 * a new time or a new zone, a run that starts early in a new zone waiting again, the March change,
 * runs started by hand and Fas 4's periodic work.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DailyReminderWorkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val stockholm = TimeZone.of("Europe/Stockholm")
    private val newYork = TimeZone.of("America/New_York")
    private val evening = LocalTime(19, 0)
    private val clock = ReminderClock(at("2026-11-13T12:00"), stockholm)
    private val scheduler by lazy { WorkManagerDailyReminderScheduler(context, clock) }
    private val manager: WorkManager
        get() = WorkManager.getInstance(context)

    private fun at(time: String, zone: TimeZone = stockholm): Instant = LocalDateTime.parse(time).toInstant(zone)

    private fun planned(): List<WorkInfo> = manager.getWorkInfosForUniqueWork(DailyReminderKind.MEAL.workName).get()

    private fun pending(): WorkInfo = planned().single { !it.state.isFinished }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @Test
    fun theEveningIsPlannedOnceAtItsLocalTime() {
        runBlocking { scheduler.ensure(DailyReminderKind.MEAL, evening) }
        val first = pending()
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        assertEquals(at("2026-11-13T19:00"), DailyRunInput.plannedOf(first.tags)?.at)
        assertEquals(DailyRun(LocalDate(2026, 11, 13), evening), DailyRunInput.plannedOf(first.tags)?.run)
        assertTrue(DailyReminderKind.MEAL.tag in first.tags)
        assertTrue(MealReminderWorker::class.java.name in first.tags)

        // Every app start asks again: the plan is kept.
        clock.instant = at("2026-11-13T15:00")
        runBlocking { scheduler.ensure(DailyReminderKind.MEAL, evening) }
        assertEquals(first.id, pending().id)
    }

    @Test
    fun aNewTimeOrANewZoneReplacesThePlan() {
        runBlocking { scheduler.ensure(DailyReminderKind.MEAL, evening) }
        val first = pending().id
        runBlocking { scheduler.ensure(DailyReminderKind.MEAL, LocalTime(20, 30)) }
        val second = pending()
        assertNotEquals(first, second.id)
        assertEquals(at("2026-11-13T20:30"), DailyRunInput.plannedOf(second.tags)?.at)

        // The phone landed in New York (06:00 there): 20:30 New York time, the same evening there.
        clock.timeZone = newYork
        runBlocking { scheduler.ensure(DailyReminderKind.MEAL, LocalTime(20, 30)) }
        val third = pending()
        assertNotEquals(second.id, third.id)
        assertEquals(at("2026-11-13T20:30", newYork), DailyRunInput.plannedOf(third.tags)?.at)
    }

    @Test
    fun cancellingRemovesThePlan() {
        runBlocking { scheduler.ensure(DailyReminderKind.MEAL, evening) }
        scheduler.cancel(DailyReminderKind.MEAL)
        assertTrue(planned().all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun theNextRunComesAfterTheRunningOne() {
        runBlocking { scheduler.ensure(DailyReminderKind.MEAL, evening) }
        val first = pending().id
        scheduler.planAfterThisRun(DailyReminderKind.MEAL, DailyRun(LocalDate(2026, 11, 14), evening))
        val states = planned().associate { it.id to it.state }
        assertEquals(WorkInfo.State.ENQUEUED, states[first])
        assertEquals(1, states.values.count { it == WorkInfo.State.BLOCKED })
    }

    @Test
    fun theMarchChangeKeepsTheEveningAtSeven() {
        // Saturday 28 March 19:00 CET → Sunday 29 March 19:00 CEST: 23 hours.
        clock.instant = at("2026-03-28T19:00")
        val request = scheduler.request(DailyReminderKind.MEAL, DailyRun(LocalDate(2026, 3, 29), evening))
        assertEquals(23.hours.inWholeMilliseconds, request.workSpec.initialDelay)
        val job = FixedJob(DailyReminderKind.MEAL, evening)
        val recording = RecordingReminderScheduler(clock)
        runBlocking { reminderWorker(context, job, recording, clock, DailyRun(LocalDate(2026, 3, 28), evening)).doWork() }
        assertEquals(listOf("after MEAL 2026-03-29 19:00"), recording.calls)
    }

    @Test
    fun aRunThatStartsEarlyInANewZoneWaitsAgain() {
        // Planned for 19:00 Stockholm time; it starts then, but the phone is in New York (13:00).
        clock.instant = at("2026-11-13T19:00")
        clock.timeZone = newYork
        val job = FixedJob(DailyReminderKind.MEAL, evening)
        val recording = RecordingReminderScheduler(clock)
        val run = DailyRun(LocalDate(2026, 11, 13), evening)
        val result = runBlocking { reminderWorker(context, job, recording, clock, run).doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(job.runs.isEmpty())
        assertEquals(listOf("after MEAL 2026-11-13 19:00"), recording.calls)
    }

    @Test
    fun aRunPlansTheNextFromTheTimeChosenNowAndNothingWhenOff() {
        clock.instant = at("2026-11-13T19:00:20")
        val recording = RecordingReminderScheduler(clock)
        val run = DailyRun(LocalDate(2026, 11, 13), evening)
        runBlocking { reminderWorker(context, FixedJob(DailyReminderKind.MEAL, LocalTime(18, 0)), recording, clock, run).doWork() }
        assertEquals(listOf("after MEAL 2026-11-14 18:00"), recording.calls)
        recording.calls.clear()
        val off = FixedJob(DailyReminderKind.MEAL, null)
        runBlocking { reminderWorker(context, off, recording, clock, run).doWork() }
        assertEquals(listOf(run), off.runs)
        assertTrue(recording.calls.isEmpty())
    }

    @Test
    fun aRunStartedByHandRunsAtOnceAndPlansNothing() {
        val job = FixedJob(DailyReminderKind.MEAL, evening)
        val recording = RecordingReminderScheduler(clock)
        runBlocking { reminderWorker(context, job, recording, clock, run = null).doWork() }
        assertEquals(listOf<DailyRun?>(null), job.runs)
        assertTrue(recording.calls.isEmpty())
    }

    @Test
    fun aRunOfFas4sPeriodicCheckOnlyMakesSureOfTheNewPlan() {
        val job = FixedJob(DailyReminderKind.PANTRY_EXPIRY, LocalTime(8, 0))
        val recording = RecordingReminderScheduler(clock)
        val tags = listOf(WorkManagerDailyReminderScheduler.LEGACY_PANTRY_TAG)
        runBlocking { reminderWorker(context, job, recording, clock, run = null, tags = tags).doWork() }
        assertTrue(job.runs.isEmpty())
        assertEquals(listOf("ensure PANTRY_EXPIRY 08:00"), recording.calls)
    }
}
