package casa.moller.tandem.core.sync.platform

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import casa.moller.tandem.core.data.notification.NotificationChannelInstaller
import casa.moller.tandem.core.model.navigation.AppOpenDestinations
import casa.moller.tandem.core.model.notification.TandemNotificationChannel
import casa.moller.tandem.core.model.notification.TandemNotificationIds
import casa.moller.tandem.core.sync.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Says that an AI import ended while the app was away from the screen (plan §8.7 "Import klar"). */
internal interface ImportDoneNotifier {
    /** The recipe [recipeName] waits to be reviewed. */
    fun finished(recipeName: String)

    /** The import failed; the reason is on the import screen. */
    fun failed()

    /** The result is on the screen, whatever way the user came back. */
    fun dismiss()
}

/**
 * [ImportDoneNotifier] on Android, in the channel "import_done". Tapping brings the app back as it
 * was left, to the import screen with the result; a finished import also when the process and the
 * task are gone ([AppOpenDestinations.IMPORT_RESULT]: the screen reads the kept answer). Coming back any
 * other way removes the notification too. Nothing is posted without the notification permission: an
 * import does not ask for it (plan 8.7: it is asked at the first timer), and the channel can be
 * turned off in the system.
 */
@Singleton
internal class AndroidImportDoneNotifier @Inject constructor(@ApplicationContext private val context: Context) : ImportDoneNotifier {

    override fun finished(recipeName: String) = post(
        title = context.getString(R.string.sync_import_done_title),
        text = recipeName.ifBlank { context.getString(R.string.sync_import_done_text) },
        tap = openReviewIntent(),
    )

    override fun failed() = post(
        title = context.getString(R.string.sync_import_failed_title),
        text = context.getString(R.string.sync_import_failed_text),
        tap = openAppIntent(),
    )

    override fun dismiss() = NotificationManagerCompat.from(context).cancel(TandemNotificationIds.IMPORT_DONE)

    private fun post(title: String, text: String, tap: PendingIntent?) {
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        if (permission != PackageManager.PERMISSION_GRANTED) return
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tandem_import)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(TandemNotificationIds.IMPORT_DONE, notification)
        } catch (e: SecurityException) {
            // The permission was revoked between the check and the call.
            return
        }
        removeWhenTheAppReturns()
    }

    /** The answer is on the screen as soon as the app is, however the user came back. */
    private fun removeWhenTheAppReturns() {
        ContextCompat.getMainExecutor(context).execute {
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        NotificationManagerCompat.from(context).cancel(TandemNotificationIds.IMPORT_DONE)
                        lifecycle.removeObserver(this)
                    }
                },
            )
        }
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The same intent `AppOpenIntents.open(context, IMPORT_RESULT)` builds (core:ui is not a dependency here). */
    private fun openReviewIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        val intent = Intent(AppOpenDestinations.ACTION).apply {
            component = launch.component
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = "${AppOpenDestinations.SCHEME}://${AppOpenDestinations.IMPORT_RESULT}".toUri()
            putExtra(AppOpenDestinations.EXTRA_DESTINATION, AppOpenDestinations.IMPORT_RESULT)
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Creates the channel (standard importance, plan 8.7); an existing one keeps the user's choices. */
    fun ensureChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.sync_notification_channel_import_done))
            .setDescription(context.getString(R.string.sync_notification_channel_import_done_description))
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    private companion object {
        val CHANNEL_ID = TandemNotificationChannel.IMPORT_DONE.id
    }
}

/** The channel "Import klar" for the app's startup (plan 8.7). */
internal class ImportDoneChannelInstaller @Inject constructor(private val notifier: AndroidImportDoneNotifier) :
    NotificationChannelInstaller {
    override fun createChannels() = notifier.ensureChannel()
}
