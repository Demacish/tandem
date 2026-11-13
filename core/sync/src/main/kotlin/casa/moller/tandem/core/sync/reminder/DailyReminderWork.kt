package casa.moller.tandem.core.sync.reminder

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import casa.moller.tandem.core.domain.reminder.DailyRun
import casa.moller.tandem.core.domain.reminder.WallClockSchedule
import casa.moller.tandem.core.sync.platform.WallClock
import casa.moller.tandem.core.sync.work.MealReminderWorker
import casa.moller.tandem.core.sync.work.PantryExpiryWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

// Fas 5 (planera lane), plan §8.3 "Påminnelse kvällen innan" and §8.8 "Använd upp": the one scheduler
// of the daily reminders at a wall-clock time the user chooses (docs/BYGGE.md "Påminnelser kvällen
// innan och på morgonen").

/**
 * The daily reminders. Each has its own unique work name and worker class (the class names are
 * kept by core/sync/consumer-rules.pro).
 */
internal enum class DailyReminderKind(val workName: String, val tag: String) {
    /** The morning "Grädde går ut i morgon" check (Fas 4), at `AppSettings.pantryExpiryTime`. */
    PANTRY_EXPIRY("tandem.reminder.pantry_expiry", "tandem.reminder.pantry"),

    /** The evening "Tina ut kycklingen" reminder (Fas 5), at `AppSettings.mealReminderTime`. */
    MEAL("tandem.reminder.meal", "tandem.reminder.meal"),
}

/** Hands the daily reminders to WorkManager (tests record the calls or use the test WorkManager). */
internal interface DailyReminderScheduler {
    /**
     * The next run of [kind] at [time], unless one is planned already for that time in the zone the
     * phone is in now (every app start calls this: it keeps a good plan and replaces one made for
     * another time or another zone).
     */
    suspend fun ensure(kind: DailyReminderKind, time: LocalTime)

    /** [run] replaces whatever is planned for [kind] (a running reminder is stopped). */
    fun plan(kind: DailyReminderKind, run: DailyRun, retry: Boolean = false)

    /** From inside a running reminder: [run] comes after it (the running one is never stopped). */
    fun planAfterThisRun(kind: DailyReminderKind, run: DailyRun, retry: Boolean = false)

    /** Off, logged out or demo mode: nothing is planned. */
    fun cancel(kind: DailyReminderKind)
}

/**
 * The input of a planned run: its local date and wall-clock time ([DailyRun]) and whether it is a
 * retry later the same day. A run without input was started by hand (the release smoke test's
 * `SmokeWorkerReceiver`) and runs at once for today, planning nothing.
 */
internal object DailyRunInput {
    const val DATE: String = "date"
    const val MINUTES: String = "minutes"
    const val RETRY: String = "retry"

    fun of(run: DailyRun, retry: Boolean): Data = Data.Builder()
        .putString(DATE, run.date.toString())
        .putInt(MINUTES, WallClockSchedule.minutesOf(run.time))
        .putBoolean(RETRY, retry)
        .build()

    /**
     * What a planned run is, as a tag of its request (`WorkInfo` has the tags, not the input): the
     * run, whether it is a retry, and the instant it was planned for in the zone the phone was in
     * then, which an app start compares with the zone it finds.
     */
    data class Planned(val run: DailyRun, val retry: Boolean, val at: Instant)

    private const val TAG_PREFIX = "tandem.reminder.run="

    fun tagOf(run: DailyRun, retry: Boolean, at: Instant): String =
        "$TAG_PREFIX${run.date}|${WallClockSchedule.minutesOf(run.time)}|$retry|${at.toEpochMilliseconds()}"

    /** The [Planned] run among [tags], or null. */
    fun plannedOf(tags: Set<String>): Planned? {
        val parts = tags.firstOrNull { it.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX)?.split('|') ?: return null
        if (parts.size != PLANNED_PARTS) return null
        val date = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return null
        val minutes = parts[1].toIntOrNull() ?: return null
        val at = parts[3].toLongOrNull() ?: return null
        return Planned(DailyRun(date, WallClockSchedule.fromMinutes(minutes)), parts[2].toBoolean(), Instant.fromEpochMilliseconds(at))
    }

    private const val PLANNED_PARTS = 4

    fun runOf(data: Data): DailyRun? {
        val date = data.getString(DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val minutes = data.getInt(MINUTES, -1).takeIf { it >= 0 } ?: return null
        return DailyRun(date, WallClockSchedule.fromMinutes(minutes))
    }

    fun isRetry(data: Data): Boolean = data.getBoolean(RETRY, false)
}

/**
 * [DailyReminderScheduler] on WorkManager.
 *
 * Fas 5 (planera lane), the DST fix: Fas 4's morning check was periodic work of 24 hours whose
 * next run each run moved with `setNextScheduleTimeOverride`; whenever that override did not take
 * (a run that never got so far, an override WorkManager dropped), the plain period took over and,
 * after a daylight-saving change, the check came at 07:00 or 09:00 until the setting was turned off
 * and on. Now every run is ONE-TIME work planned for a local date and time ([DailyRun]): its delay
 * is worked out from the date and time in the phone's zone when it is planned, each run plans the
 * next one ([planAfterThisRun], `APPEND_OR_REPLACE` so the running one is not stopped), a run that
 * starts early in its zone waits again (the zone changed), and every app start checks the plan
 * against the zone it finds ([ensure]). Nothing depends on a period.
 */
@Singleton
internal class WorkManagerDailyReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: WallClock,
) : DailyReminderScheduler {
    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    override suspend fun ensure(kind: DailyReminderKind, time: LocalTime) {
        dropLegacy(kind)
        val pending = workManager.getWorkInfosForUniqueWorkFlow(kind.workName).first().filterNot { it.state.isFinished }
        // A running reminder plans its own successor when it ends (with the time it reads then).
        if (pending.any { it.state == WorkInfo.State.RUNNING }) return
        val now = clock.now()
        val zone = clock.zone()
        val planned = pending.lastOrNull()
        if (planned != null && isGood(planned, time, now, zone)) return
        plan(kind, WallClockSchedule.next(now, zone, time))
    }

    /**
     * [info] is planned for [time] (or is a retry today) at the instant that time is in [zone] now;
     * a run that is due already is kept when it is today's (WorkManager starts it as soon as it can)
     * and replaced when it is an older day's.
     */
    private fun isGood(info: WorkInfo, time: LocalTime, now: Instant, zone: TimeZone): Boolean {
        val planned = DailyRunInput.plannedOf(info.tags) ?: return false
        val run = planned.run
        val today = WallClockSchedule.today(now, zone)
        val retryToday = planned.retry && run.date == today
        if (run.time != time && !retryToday) return false
        val at = run.at(zone)
        if (at <= now) return run.date == today
        return (planned.at - at).absoluteValue <= WallClockSchedule.EARLY_TOLERANCE
    }

    override fun plan(kind: DailyReminderKind, run: DailyRun, retry: Boolean) {
        workManager.enqueueUniqueWork(kind.workName, ExistingWorkPolicy.REPLACE, request(kind, run, retry))
    }

    override fun planAfterThisRun(kind: DailyReminderKind, run: DailyRun, retry: Boolean) {
        workManager.enqueueUniqueWork(kind.workName, ExistingWorkPolicy.APPEND_OR_REPLACE, request(kind, run, retry))
    }

    override fun cancel(kind: DailyReminderKind) {
        dropLegacy(kind)
        workManager.cancelUniqueWork(kind.workName)
    }

    /** Fas 4's periodic morning check (`tandem.pantry.expiry`) is replaced by the daily one. */
    private fun dropLegacy(kind: DailyReminderKind) {
        if (kind == DailyReminderKind.PANTRY_EXPIRY) workManager.cancelUniqueWork(LEGACY_PANTRY_WORK_NAME)
    }

    /** The one-time request for [run]: its delay counted now, from the date and time in the phone's zone. */
    fun request(kind: DailyReminderKind, run: DailyRun, retry: Boolean = false): OneTimeWorkRequest {
        val zone = clock.zone()
        val delay = WallClockSchedule.delayUntil(run, clock.now(), zone)
        return OneTimeWorkRequest.Builder(workerOf(kind))
            .setInitialDelay(delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .setInputData(DailyRunInput.of(run, retry))
            .setConstraints(constraintsOf(kind))
            .addTag(kind.tag)
            .addTag(DailyRunInput.tagOf(run, retry, run.at(zone)))
            .build()
    }

    internal companion object {
        /** Fas 4's unique periodic work, cancelled on the first start of this version. */
        const val LEGACY_PANTRY_WORK_NAME: String = "tandem.pantry.expiry"

        /** Fas 4's tag of that work: a run of it does nothing but make sure the new plan exists. */
        const val LEGACY_PANTRY_TAG: String = "tandem.pantry"

        fun workerOf(kind: DailyReminderKind): Class<out ListenableWorker> = when (kind) {
            DailyReminderKind.PANTRY_EXPIRY -> PantryExpiryWorker::class.java
            DailyReminderKind.MEAL -> MealReminderWorker::class.java
        }

        /**
         * No network constraint (both work offline from the local data). The morning check waits for a
         * battery that is not low, as in Fas 4; the evening reminder is about a time, so it does not.
         */
        fun constraintsOf(kind: DailyReminderKind): Constraints = when (kind) {
            DailyReminderKind.PANTRY_EXPIRY -> Constraints.Builder().setRequiresBatteryNotLow(true).build()
            DailyReminderKind.MEAL -> Constraints.NONE
        }
    }
}

/**
 * What one daily reminder does when its run comes (the worker around it plans the runs).
 *
 * @see DailyReminderWorker
 */
internal interface DailyReminderJob {
    val kind: DailyReminderKind

    /** The chosen time while the reminder is wanted (setting on); null when it is off. */
    suspend fun time(): LocalTime?

    /**
     * Does the reminder's work for [run] (null: started by hand, for now) and returns a run later the
     * same day when this one should be tried again (Fas 4: Tandem was on screen), else null.
     */
    suspend fun run(run: DailyRun?): DailyRun?
}

/**
 * The worker of a daily reminder: a run that starts early in the phone's zone plans itself again
 * and does nothing; otherwise the [job] runs, and whatever happened the next run is planned (a
 * retry the job asks for, else the next day at the time chosen now). Always a success, nothing
 * here is worth a WorkManager retry. A run started by hand (no input) plans nothing.
 */
internal abstract class DailyReminderWorker(
    context: Context,
    params: WorkerParameters,
    private val job: DailyReminderJob,
    private val scheduler: DailyReminderScheduler,
    private val clock: WallClock,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (WorkManagerDailyReminderScheduler.LEGACY_PANTRY_TAG in tags) {
            // Fas 4's periodic check, not yet cancelled: the new plan replaces it.
            job.time()?.let { scheduler.ensure(job.kind, it) }
            return Result.success()
        }
        val run = DailyRunInput.runOf(inputData)
        if (run != null && WallClockSchedule.isEarly(run, clock.now(), clock.zone())) {
            scheduler.planAfterThisRun(job.kind, run, DailyRunInput.isRetry(inputData))
            return Result.success()
        }
        var retry: DailyRun? = null
        try {
            retry = job.run(run)
        } finally {
            if (run != null) planNext(run, retry)
        }
        return Result.success()
    }

    private suspend fun planNext(run: DailyRun, retry: DailyRun?) {
        val time = job.time() ?: return
        if (retry != null) {
            scheduler.planAfterThisRun(job.kind, retry, retry = true)
        } else {
            scheduler.planAfterThisRun(job.kind, WallClockSchedule.next(clock.now(), clock.zone(), time, after = run.date))
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "${job.kind}: ran for $run, retry $retry")
    }

    private companion object {
        const val TAG = "TandemReminders"
    }
}
