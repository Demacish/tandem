package casa.moller.tandem.core.sync.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import casa.moller.tandem.core.sync.recipeimport.AiImportWatch
import casa.moller.tandem.core.sync.recipeimport.ImportKeepAlive
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull

/**
 * `tandem.import.keepalive`: does nothing but stay running until the AI import has ended. While it
 * runs the process is not cached, so Android does not freeze it with the answer on its way, and
 * the import can say it is done (plan §8.7). The request itself stays where it is, in the import
 * screen's ViewModel: the result is shown there. After a process restart nothing runs and it ends at once.
 */
@HiltWorker
internal class ImportKeepAliveWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val watch: AiImportWatch,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        withTimeoutOrNull(LONGEST_IMPORT_MILLIS) { watch.awaitNoneRunning() }
        return Result.success()
    }

    private companion object {
        /** The client gives an import 120 s (plan 5.4), and its provider lookup goes first. */
        const val LONGEST_IMPORT_MILLIS = 150_000L
    }
}

/** Expedited, so it starts at once; REPLACE, so an import that follows another never rides on a worker about to end. */
@Singleton
internal class WorkManagerImportKeepAlive @Inject constructor(@ApplicationContext private val context: Context) : ImportKeepAlive {
    override fun request() {
        val request = OneTimeWorkRequestBuilder<ImportKeepAliveWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val WORK_NAME: String = "tandem.import.keepalive"
    }
}
