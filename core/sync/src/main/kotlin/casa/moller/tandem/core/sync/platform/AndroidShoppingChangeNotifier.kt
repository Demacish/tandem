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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import casa.moller.tandem.core.data.notification.NotificationChannelInstaller
import casa.moller.tandem.core.model.navigation.AppOpenDestinations
import casa.moller.tandem.core.model.notification.TandemNotificationChannel
import casa.moller.tandem.core.model.notification.TandemNotificationIds
import casa.moller.tandem.core.sync.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts "Inköpslistan ändrades" in the channel "shopping" (Fas 4), only with the user's
 * permission; the list's own foreground updates cover the rest. One notification at a time: a
 * later batch replaces it. Tapping opens the Handla tab ([AppOpenDestinations.SHOPPING]).
 */
@Singleton
internal class AndroidShoppingChangeNotifier @Inject constructor(@ApplicationContext private val context: Context) :
    ShoppingChangeNotifier {

    override fun post(summary: ShoppingChangeSummary): Boolean {
        if (summary.isEmpty) return false
        val manager = NotificationManagerCompat.from(context)
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        val channelEnabled = manager.getNotificationChannelCompat(CHANNEL_ID)
            ?.let { it.importance != NotificationManagerCompat.IMPORTANCE_NONE } ?: true
        val allowed = ShoppingNotificationPolicy.shouldPost(
            enabled = true,
            appVisible = false,
            permissionGranted = permission == PackageManager.PERMISSION_GRANTED,
            notificationsEnabled = manager.areNotificationsEnabled(),
            channelEnabled = channelEnabled,
        )
        if (!allowed) return false
        ensureChannel()
        val lines = lines(summary)
        val title = context.resources.getQuantityString(R.plurals.sync_notification_shopping_title, summary.count, summary.count)
        val style = NotificationCompat.InboxStyle().also { style -> lines.forEach { style.addLine(it) } }
        if (summary.more > 0) {
            style.setSummaryText(context.resources.getQuantityString(R.plurals.sync_notification_shopping_more, summary.more, summary.more))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tandem_shopping)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull())
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(openShoppingIntent())
            .build()
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            // The permission was revoked between the check and the call.
            false
        }
    }

    /** One line per change, as the list's own snackbars word them. */
    private fun lines(summary: ShoppingChangeSummary): List<String> = buildList {
        summary.added.forEach { change ->
            add(
                change.by?.let { context.getString(R.string.sync_notification_shopping_added_by, it, change.foodName) }
                    ?: context.getString(R.string.sync_notification_shopping_added, change.foodName),
            )
        }
        summary.checked.forEach { add(context.getString(R.string.sync_notification_shopping_checked, it)) }
        summary.removed.forEach { add(context.getString(R.string.sync_notification_shopping_removed, it)) }
    }

    /** The channel "Inköpslistan" (idempotent, like the "Server" channel). */
    fun ensureChannel() {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.sync_notification_channel_shopping))
                .setDescription(context.getString(R.string.sync_notification_channel_shopping_description))
                .build(),
        )
    }

    /** The same intent `AppOpenIntents.open(context, SHOPPING)` builds (core:ui is not a dependency here). */
    private fun openShoppingIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        val intent = Intent(AppOpenDestinations.ACTION).apply {
            component = launch.component
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = "${AppOpenDestinations.SCHEME}://${AppOpenDestinations.SHOPPING}".toUri()
            putExtra(AppOpenDestinations.EXTRA_DESTINATION, AppOpenDestinations.SHOPPING)
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private companion object {
        val CHANNEL_ID: String = TandemNotificationChannel.SHOPPING.id
        const val NOTIFICATION_ID = TandemNotificationIds.SHOPPING_CHANGES
    }
}

/** The channel "Inköpslistan" for the app's startup (plan 8.7). */
internal class ShoppingChannelInstaller @Inject constructor(private val notifier: AndroidShoppingChangeNotifier) :
    NotificationChannelInstaller {
    override fun createChannels() = notifier.ensureChannel()
}

/** The process lifecycle: visible while any activity is started. */
internal class ProcessAppVisibility @Inject constructor() : AppVisibility {
    override fun isVisible(): Boolean = try {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    } catch (e: IllegalStateException) {
        // Without the process lifecycle (never expected): treated as visible, so nothing is posted twice.
        true
    }
}
