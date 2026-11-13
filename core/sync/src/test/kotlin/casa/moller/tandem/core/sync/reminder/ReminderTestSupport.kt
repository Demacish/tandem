package casa.moller.tandem.core.sync.reminder

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import casa.moller.tandem.core.domain.reminder.DailyRun
import casa.moller.tandem.core.sync.platform.WallClock
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

// Fas 5 (planera lane): helpers of the daily reminders' tests.

/** A wall clock the test moves ([instant]) and takes to other zones ([timeZone]). */
internal class ReminderClock(@Volatile var instant: Instant, @Volatile var timeZone: TimeZone) : WallClock {
    override fun now(): Instant = instant

    override fun zone(): TimeZone = timeZone
}

/** Records what the reminders ask of WorkManager, as short lines ("ensure MEAL 19:00", "after MEAL 2026-11-14 19:00"). */
internal class RecordingReminderScheduler(private val clock: WallClock) : DailyReminderScheduler {
    val calls: MutableList<String> = CopyOnWriteArrayList()

    override suspend fun ensure(kind: DailyReminderKind, time: LocalTime) {
        calls += "ensure $kind $time"
    }

    override fun plan(kind: DailyReminderKind, run: DailyRun, retry: Boolean) {
        calls += "plan $kind ${run.date} ${run.time}" + if (retry) " retry" else ""
    }

    override fun planAfterThisRun(kind: DailyReminderKind, run: DailyRun, retry: Boolean) {
        calls += "after $kind ${run.date} ${run.time}" + if (retry) " retry" else ""
    }

    override fun cancel(kind: DailyReminderKind) {
        calls += "cancel $kind"
    }
}

/** A daily reminder that is wanted at [time] (null: off) and asks for [retry] after every run. */
internal class FixedJob(override val kind: DailyReminderKind, private val time: LocalTime?, private val retry: DailyRun? = null) :
    DailyReminderJob {
    val runs: MutableList<DailyRun?> = CopyOnWriteArrayList()

    override suspend fun time(): LocalTime? = time

    override suspend fun run(run: DailyRun?): DailyRun? {
        runs += run
        return retry
    }
}

/** The worker around any [DailyReminderJob] (the real ones only add their job). */
internal class TestReminderWorker(
    context: Context,
    params: WorkerParameters,
    job: DailyReminderJob,
    scheduler: DailyReminderScheduler,
    clock: WallClock,
) : DailyReminderWorker(context, params, job, scheduler, clock)

/** A worker for [job] with [run] as its planned run (null: started by hand) and [tags]. */
internal fun reminderWorker(
    context: Context,
    job: DailyReminderJob,
    scheduler: DailyReminderScheduler,
    clock: WallClock,
    run: DailyRun?,
    retry: Boolean = false,
    tags: List<String> = emptyList(),
): TestReminderWorker {
    val factory = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
            TestReminderWorker(appContext, workerParameters, job, scheduler, clock)
    }
    val builder = TestListenableWorkerBuilder<TestReminderWorker>(context)
        .setWorkerFactory(factory)
        .setTags(tags)
    if (run != null) builder.setInputData(DailyRunInput.of(run, retry))
    return builder.build()
}
