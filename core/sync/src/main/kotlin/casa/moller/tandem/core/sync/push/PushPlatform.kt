package casa.moller.tandem.core.sync.push

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import casa.moller.tandem.core.model.notification.TandemNotificationChannel
import casa.moller.tandem.core.model.push.PushDistributor
import casa.moller.tandem.core.sync.R
import casa.moller.tandem.core.sync.SyncSchedule
import casa.moller.tandem.core.sync.platform.AndroidShoppingChangeNotifier
import casa.moller.tandem.core.sync.work.PushSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.unifiedpush.android.connector.UnifiedPush

// Fas 4 (push lane): the Android side of fast push, behind small interfaces so the repository's
// tests use fakes (a fake distributor, a recording scheduler and notifier).

/** The UnifiedPush distributor apps (ntfy, NextPush, …) and the registrations at them. */
internal interface PushDistributorGateway {
    /** The installed distributors with their app names. */
    fun distributors(): List<PushDistributor>

    /** Saves [distributor] and asks it for an endpoint for [instance] (the account id). */
    fun register(instance: String, distributor: String)

    /** Drops [instance]'s registration at the distributor. */
    fun unregister(instance: String)
}

/** The real distributors through the UnifiedPush connector (Apache-2.0, Maven Central). */
@Singleton
internal class UnifiedPushGateway @Inject constructor(@ApplicationContext private val context: Context) : PushDistributorGateway {
    override fun distributors(): List<PushDistributor> = UnifiedPush.getDistributors(context)
        .filter { it != context.packageName }
        .map { packageName -> PushDistributor(packageName, label(packageName)) }
        .sortedBy { it.label.lowercase() }

    override fun register(instance: String, distributor: String) {
        UnifiedPush.saveDistributor(context, distributor)
        UnifiedPush.register(context, instance, MESSAGE_FOR_DISTRIBUTOR)
    }

    override fun unregister(instance: String) {
        UnifiedPush.unregister(context, instance)
    }

    private fun label(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    private companion object {
        /** What the distributor may show for the registration. */
        const val MESSAGE_FOR_DISTRIBUTOR = "Tandem"
    }
}

/** Runs the shopping list's sync after a wake-up ([PushSyncWorker]). */
internal fun interface PushWakeScheduler {
    fun wake()
}

/**
 * `tandem.sync.push`: expedited when the quota allows, with a network; a wake-up while one waits
 * or runs is dropped (KEEP): the service sends at most one per poll interval.
 */
@Singleton
internal class WorkManagerPushWakeScheduler @Inject constructor(@ApplicationContext private val context: Context) : PushWakeScheduler {
    override fun wake() {
        val request = OneTimeWorkRequestBuilder<PushSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(SyncSchedule.TAG_SYNC)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val WORK_NAME: String = "tandem.sync.push"
    }
}

/** "Skicka testnotis": the notification a test wake-up shows. */
internal fun interface FastPushNotifier {
    /** Posts the test notification; false when the app may not post. */
    fun postTest(): Boolean
}

/** Posts the test notification in the channel "Inköpslistan" (plan 8.7: push lands there). */
@Singleton
internal class AndroidFastPushNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shopping: AndroidShoppingChangeNotifier,
) : FastPushNotifier {
    override fun postTest(): Boolean {
        val manager = NotificationManagerCompat.from(context)
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        if (permission != PackageManager.PERMISSION_GRANTED || !manager.areNotificationsEnabled()) return false
        shopping.ensureChannel()
        val notification = NotificationCompat.Builder(context, TandemNotificationChannel.SHOPPING.id)
            .setSmallIcon(R.drawable.ic_stat_tandem_shopping)
            .setContentTitle(context.getString(R.string.sync_push_test_title))
            .setContentText(context.getString(R.string.sync_push_test_body))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()
        return try {
            manager.notify(TEST_NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    companion object {
        /**
         * The test notification's id: next to `TandemNotificationIds.SHOPPING_CHANGES` (1201) in the
         * shopping range, kept here so the shared id table is not touched by this lane.
         */
        const val TEST_NOTIFICATION_ID: Int = 1251
    }
}
