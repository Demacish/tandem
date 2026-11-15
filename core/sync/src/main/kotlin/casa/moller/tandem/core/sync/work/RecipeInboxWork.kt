package casa.moller.tandem.core.sync.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import casa.moller.tandem.core.data.inbox.RecipeInboxRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.withTimeoutOrNull

// Fas 5 (rss lane, plan §7 #35): the recipe inbox's light background refresh
// (docs/BYGGE.md "Inkorgen för recept").

/**
 * Every [WorkManagerRecipeInboxWork.PERIOD_HOURS] hours with a network while the inbox is on
 * ([RecipeInboxWork.ensure]): sends the waiting "read" marks and reads the unread posts, through the
 * inbox's own guard (its breaker holds a background refresh back). A failure waits for the next run:
 * the guard, not WorkManager's retry, decides when Miniflux is asked again. The class name is stored
 * by WorkManager (kept by consumer-rules.pro).
 */
@HiltWorker
internal class RecipeInboxWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val inbox: RecipeInboxRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        withTimeoutOrNull(TIMEOUT_MILLIS) { inbox.refresh(background = true) }
        return Result.success()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 90_000L
    }
}

/** Plans [RecipeInboxWorker]. */
internal interface RecipeInboxWork {
    /** Keeps the periodic work (an existing plan stays as it is). */
    fun ensure()

    fun cancel()
}

internal class WorkManagerRecipeInboxWork @Inject constructor(@ApplicationContext private val context: Context) : RecipeInboxWork {
    override fun ensure() {
        val request = PeriodicWorkRequestBuilder<RecipeInboxWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }

    companion object {
        const val NAME: String = "tandem.recipe_inbox"
        const val TAG: String = "tandem.recipe_inbox"

        /** A few times a day: new recipe posts are not urgent, and the server is the user's own. */
        const val PERIOD_HOURS: Long = 4

        const val BACKOFF_MINUTES: Long = 30
    }
}
