package casa.moller.tandem.core.sync.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import casa.moller.tandem.core.sync.push.FastPushReceiver
import casa.moller.tandem.core.sync.push.PushWakeUp
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Fas 4 (push lane): a wake-up from the optional push service (`tandem.sync.push`, expedited):
 * reads the shopping list once ([PushWakeUp]). The list notification is posted by
 * `ShoppingChangeNotifications` as after any background read.
 */
@HiltWorker
internal class PushSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val wakeUp: PushWakeUp,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        wakeUp.run()
        // Never retried: the next wake-up or the periodic sync reads the list again (plan 4.1).
        return Result.success()
    }
}

/**
 * Fas 4 review (fix-data): `tandem.push.register`, a registration that failed for a passing reason
 * is tried once more. Its result arrives as a new endpoint, which schedules this again if it fails.
 */
@HiltWorker
internal class PushRegisterWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val receiver: FastPushReceiver,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        receiver.retryRegistration()
        return Result.success()
    }
}
