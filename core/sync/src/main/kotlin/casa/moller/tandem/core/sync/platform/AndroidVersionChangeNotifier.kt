package casa.moller.tandem.core.sync.platform

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import casa.moller.tandem.core.data.notification.NotificationChannelInstaller
import casa.moller.tandem.core.model.notification.TandemNotificationChannel
import casa.moller.tandem.core.model.notification.TandemNotificationIds
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
        // The app creates the channel at startup; one that is missing anyway is created below.
        val channelEnabled = manager.getNotificationChannelCompat(CHANNEL_ID)
            ?.let { it.importance != NotificationManagerCompat.IMPORTANCE_NONE } ?: true
        val post = VersionNotificationPolicy.shouldPost(
            permissionGranted = true,
            notificationsEnabled = enabled,
            channelEnabled = channelEnabled,
        )
        if (!post) return false
        ensureChannel()
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

    /**
     * The channel "Server" (idempotent: an existing channel only gets its name and description
     * updated). Created by the app at startup ([ServerChannelInstaller]) and again before posting.
     */
    fun ensureChannel() {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.sync_notification_channel_server))
                .setDescription(context.getString(R.string.sync_notification_channel_server_description))
                .build(),
        )
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private companion object {
        /** The channel "Server" (plan B18); every channel id is decided in core:model (Phase 1b). */
        val CHANNEL_ID: String = TandemNotificationChannel.SERVER.id
        const val NOTIFICATION_ID = TandemNotificationIds.SERVER_VERSION
    }
}

/**
 * The channel "Server" for the app's startup (plan 8.7). `sync_problems` is not created: nothing
 * posts in it yet (0.2.0), and an empty category in the system settings would only confuse.
 */
internal class ServerChannelInstaller @Inject constructor(private val notifier: AndroidVersionChangeNotifier) :
    NotificationChannelInstaller {
    override fun createChannels() = notifier.ensureChannel()
}
