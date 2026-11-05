package casa.moller.tandem.smoke

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
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
 * `data="SUCCEEDED"`. Only the shell (which holds DUMP) can send it. The class name is the one
 * core/sync/consumer-rules.pro keeps (WorkManager stores it the same way).
 */
class SmokeWorkerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        val worker = Class.forName(PANTRY_EXPIRY_WORKER).asSubclass(ListenableWorker::class.java)
        val request = OneTimeWorkRequest.Builder(worker).build()
        val manager = WorkManager.getInstance(context)
        manager.enqueue(request)
        CoroutineScope(Dispatchers.IO).launch {
            val info = withTimeoutOrNull(TIMEOUT_MILLIS) {
                manager.getWorkInfoByIdFlow(request.id).first { it?.state?.isFinished == true }
            }
            result.resultData = info?.state?.name ?: TIMED_OUT
            result.finish()
        }
    }

    private companion object {
        const val PANTRY_EXPIRY_WORKER = "casa.moller.tandem.core.sync.work.PantryExpiryWorker"
        const val TIMED_OUT = "TIMED_OUT"

        /** Below the system's limit for a background broadcast (60 s). */
        const val TIMEOUT_MILLIS = 45_000L
    }
}
