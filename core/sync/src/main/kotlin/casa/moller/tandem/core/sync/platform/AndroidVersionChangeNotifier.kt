package casa.moller.tandem.core.sync.platform

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import casa.moller.tandem.core.model.server.ServerVersionChange
import casa.moller.tandem.core.sync.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts "Tandoor was updated" in the notification channel "Server" (plan B18), only when the user
 * allowed notifications ([VersionNotificationPolicy.shouldPost]). Tapping it opens the app.
 * Without the permission (the app asks for it only at the first timer, plan 6), or with the
 * channel switched off, nothing is posted and `false` tells `AccountMirror` to store the in-app
 * notice instead ([VersionNotificationPolicy.needsInAppNotice]).
 */
@Singleton
internal class AndroidVersionChangeNotifier @Inject constructor(@ApplicationContext private val context: Context) :
    VersionChangeNotifier {

    override fun onVersionChanged(change: ServerVersionChange): Boolean {
        val manager = NotificationManagerCompat.from(context)
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        if (permission != PackageManager.PERMISSION_GRANTED) return false
        val enabled = manager.areNotificationsEnabled()
        // A channel the user switched off (importance NONE) shows nothing: tell in the app instead.
        // A channel that does not exist yet is created below with the default importance.
        val channelEnabled = manager.getNotificationChannelCompat(CHANNEL_ID)
            ?.let { it.importance != NotificationManagerCompat.IMPORTANCE_NONE } ?: true
        val post = VersionNotificationPolicy.shouldPost(
            permissionGranted = true,
            notificationsEnabled = enabled,
            channelEnabled = channelEnabled,
        )
        if (!post) return false
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.sync_notification_channel_server))
                .setDescription(context.getString(R.string.sync_notification_channel_server_description))
                .build(),
        )
        val text = context.getString(R.string.sync_notification_version_text, change.newVersion, change.previousVersion)
        val detail = when (VersionNotificationPolicy.noticeFor(change)) {
            VersionNotice.UPDATED -> text
            VersionNotice.SAFE_MODE -> text + "\n" + context.getString(R.string.sync_notification_version_safe_mode)
            VersionNotice.WRITES_OFF -> text + "\n" + context.getString(R.string.sync_notification_version_writes_off)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tandem_server)
            .setContentTitle(context.getString(R.string.sync_notification_version_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            // The permission was revoked between the check and the call: tell in the app instead.
            false
        }
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private companion object {
        /** The channel "Server" (plan B18). */
        const val CHANNEL_ID = "server"
        const val NOTIFICATION_ID = 1001
    }
}
