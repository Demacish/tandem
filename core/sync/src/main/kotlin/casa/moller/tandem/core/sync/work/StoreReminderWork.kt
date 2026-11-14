package casa.moller.tandem.core.sync.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import casa.moller.tandem.core.domain.storereminder.StoreTransition
import casa.moller.tandem.core.sync.storereminder.StoreReminderCheck
import casa.moller.tandem.core.sync.storereminder.StoreReminderOutcome
import casa.moller.tandem.core.sync.storereminder.StoreReminderRegistrar
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// Fas 5 (butik lane, plan §7 #34): the reminder near the store's background work (docs/BYGGE.md
// "Påminnelse nära butiken"). The receivers only read the broadcast and hand it here, so nothing
// waits in a receiver and every path (a geofence, a reboot, the release smoke test) is the same.

/**
 * One geofence event ([StoreReminderWork.MODE_TRANSITION]: input [StoreReminderWork.KEY_STORES],
 * [StoreReminderWork.KEY_TRANSITION], [StoreReminderWork.KEY_AT]) or "put the geofences back"
 * ([StoreReminderWork.MODE_REGISTER], after a reboot, an app update or the location being turned on).
 * The class name is stored by WorkManager (kept by consumer-rules.pro).
 */
@HiltWorker
internal class StoreReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val job: StoreReminderJob,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val outcomes = job.run(inputData)
        // Only for the smoke test's receiver: what happened, without any place.
        return Result.success(workDataOf(StoreReminderWork.KEY_OUTCOMES to outcomes.values.map { it.name }.toTypedArray()))
    }
}

/** One run of [StoreReminderWorker] (tests call it directly). */
@Singleton
internal class StoreReminderJob @Inject constructor(private val check: StoreReminderCheck, private val registrar: StoreReminderRegistrar) {
    suspend fun run(input: Data): Map<Long, StoreReminderOutcome> {
        when (input.getString(StoreReminderWork.KEY_MODE)) {
            StoreReminderWork.MODE_REGISTER -> {
                registrar.sync(force = true)
                return emptyMap()
            }

            StoreReminderWork.MODE_LOST -> {
                registrar.onGeofencesLost()
                return emptyMap()
            }
        }
        val stores = input.getLongArray(StoreReminderWork.KEY_STORES)?.toList().orEmpty()
        val transition = StoreTransition.entries.firstOrNull { it.name == input.getString(StoreReminderWork.KEY_TRANSITION) }
        if (stores.isEmpty() || transition == null) return emptyMap()
        val at = input.getLong(StoreReminderWork.KEY_AT, System.currentTimeMillis())
        return check.onTransition(stores, transition, at)
    }
}

/** Hands events to [StoreReminderWorker] (expedited: a reminder at the store door cannot wait). */
internal interface StoreReminderWork {
    fun transition(storeIds: List<Long>, transition: StoreTransition, atMillis: Long)

    /** Puts the geofences back (forced: also an unchanged set). */
    fun register()

    /** Play services dropped the geofences (the location was turned off). */
    fun lost()

    companion object {
        const val KEY_MODE: String = "mode"
        const val KEY_STORES: String = "stores"
        const val KEY_TRANSITION: String = "transition"
        const val KEY_AT: String = "at"
        const val KEY_OUTCOMES: String = "outcomes"
        const val MODE_TRANSITION: String = "transition"
        const val MODE_REGISTER: String = "register"
        const val MODE_LOST: String = "lost"
        const val TAG: String = "tandem.store_reminder"
    }
}

internal class WorkManagerStoreReminderWork @Inject constructor(@ApplicationContext private val context: Context) : StoreReminderWork {
    override fun transition(storeIds: List<Long>, transition: StoreTransition, atMillis: Long) = enqueue(
        workDataOf(
            StoreReminderWork.KEY_MODE to StoreReminderWork.MODE_TRANSITION,
            StoreReminderWork.KEY_STORES to storeIds.toLongArray(),
            StoreReminderWork.KEY_TRANSITION to transition.name,
            StoreReminderWork.KEY_AT to atMillis,
        ),
    )

    override fun register() = enqueue(workDataOf(StoreReminderWork.KEY_MODE to StoreReminderWork.MODE_REGISTER))

    override fun lost() = enqueue(workDataOf(StoreReminderWork.KEY_MODE to StoreReminderWork.MODE_LOST))

    private fun enqueue(input: Data) {
        val request = OneTimeWorkRequestBuilder<StoreReminderWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(input)
            .addTag(StoreReminderWork.TAG)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
