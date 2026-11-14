package casa.moller.tandem.smoke

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fas 4 (integration): ONLY in the `minified` build type (the release smoke test's package
 * casa.moller.tandem.minified, never shipped; `src/minified` is in no other variant, docs/BYGGE.md
 * "Release-röktest"). The morning expiry check is periodic work with its first run at the next
 * 08:00, and WorkManager refuses to start a periodic worker early (even through `cmd jobscheduler
 * run`), so the smoke test asks this receiver to run the same worker class once, through the same
 * Hilt worker factory, and waits for the result:
 *
 * `am broadcast -n <package>/casa.moller.tandem.smoke.SmokeWorkerReceiver` prints
 * `data="SUCCEEDED"` (`--es worker meal`: the evening reminder, Fas 5; `--es worker store --el store 1`:
 * a simulated ENTER of store 1 for the reminder near the store, which prints `data="SUCCEEDED:POSTED"`).
 * Only the shell (which holds DUMP) can send it. The class name is the one
 * core/sync/consumer-rules.pro keeps (WorkManager stores it the same way).
 */
class SmokeWorkerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        // Fas 5 (planera lane): `--es worker meal` runs the evening reminder (for this evening) instead.
        // Fas 5 (butik lane): `--es worker store --el store <id>` hands the reminder near the store a
        // simulated ENTER of that store, now (`--es transition EXIT` an EXIT), as a geofence would.
        val name = when (intent.getStringExtra(EXTRA_WORKER)) {
            MEAL -> MEAL_REMINDER_WORKER
            STORE -> STORE_REMINDER_WORKER
            else -> PANTRY_EXPIRY_WORKER
        }
        val worker = Class.forName(name).asSubclass(ListenableWorker::class.java)
        val builder = OneTimeWorkRequest.Builder(worker)
        if (name == STORE_REMINDER_WORKER) {
            builder.setInputData(
                workDataOf(
                    "mode" to "transition",
                    "stores" to longArrayOf(intent.getLongExtra(EXTRA_STORE, 0L)),
                    "transition" to (intent.getStringExtra(EXTRA_TRANSITION) ?: "ENTER"),
                    "at" to System.currentTimeMillis(),
                ),
            )
        }
        val request = builder.build()
        val manager = WorkManager.getInstance(context)
        manager.enqueue(request)
        CoroutineScope(Dispatchers.IO).launch {
            val info = withTimeoutOrNull(TIMEOUT_MILLIS) {
                manager.getWorkInfoByIdFlow(request.id).first { it?.state?.isFinished == true }
            }
            // The store reminder also says what it did (POSTED, SAME_VISIT, …), never a place.
            val outcomes = info?.outputData?.getStringArray("outcomes")?.joinToString(",")
            result.resultData = info?.state?.name?.let { state -> outcomes?.let { "$state:$it" } ?: state } ?: TIMED_OUT
            result.finish()
        }
    }

    private companion object {
        const val PANTRY_EXPIRY_WORKER = "casa.moller.tandem.core.sync.work.PantryExpiryWorker"
        const val MEAL_REMINDER_WORKER = "casa.moller.tandem.core.sync.work.MealReminderWorker"
        const val STORE_REMINDER_WORKER = "casa.moller.tandem.core.sync.work.StoreReminderWorker"
        const val EXTRA_WORKER = "worker"
        const val MEAL = "meal"
        const val STORE = "store"
        const val EXTRA_STORE = "store"
        const val EXTRA_TRANSITION = "transition"
        const val TIMED_OUT = "TIMED_OUT"

        /** Below the system's limit for a background broadcast (60 s). */
        const val TIMEOUT_MILLIS = 45_000L
    }
}
