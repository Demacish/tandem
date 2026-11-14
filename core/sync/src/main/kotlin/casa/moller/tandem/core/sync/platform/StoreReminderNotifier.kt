package casa.moller.tandem.core.sync.platform

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import casa.moller.tandem.core.model.navigation.AppOpenDestinations
import casa.moller.tandem.core.model.notification.TandemNotificationChannel
import casa.moller.tandem.core.model.notification.TandemNotificationIds
import casa.moller.tandem.core.sync.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Fas 5 (butik lane, plan §7 #34): posts "Du är nära <butik> – 7 saker på listan". */
internal interface StoreReminderNotifier {
    /** POST_NOTIFICATIONS is granted and neither the app's notifications nor the channel are off. */
    fun canPost(): Boolean

    /** Posts the reminder for [storeId] ([storeName] null: the store is gone from Tandoor) with [count] things to buy. */
    fun post(storeId: Long, storeName: String?, count: Int): Boolean
}

/**
 * [StoreReminderNotifier] in the channel "Inköpslistan" (`shopping`, plan §8.7: the shopping list's
 * channel, standard priority; never a new channel). One notification per store, under
 * [TandemNotificationIds.STORE_REMINDER_TAG]; it goes away by itself after [TIMEOUT_MILLIS] (the
 * visit is over by then) and when tapped. Tapping opens store mode sorted for that store
 * ([AppOpenDestinations.STORE_MODE_PREFIX]).
 */
@Singleton
internal class AndroidStoreReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shoppingChannel: AndroidShoppingChangeNotifier,
) : StoreReminderNotifier {

    override fun canPost(): Boolean {
        val manager = NotificationManagerCompat.from(context)
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val channelOn = manager.getNotificationChannelCompat(CHANNEL_ID)
            ?.let { it.importance != NotificationManagerCompat.IMPORTANCE_NONE } ?: true
        return permission && manager.areNotificationsEnabled() && channelOn
    }

    override fun post(storeId: Long, storeName: String?, count: Int): Boolean {
        if (!canPost() || count <= 0) return false
        shoppingChannel.ensureChannel()
        val title = storeName?.takeIf { it.isNotBlank() }
            ?.let { context.getString(R.string.sync_store_reminder_title, it) }
            ?: context.getString(R.string.sync_store_reminder_title_unnamed)
        val text = context.resources.getQuantityString(R.plurals.sync_store_reminder_text, count, count)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tandem_shopping)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MILLIS)
            .setContentIntent(openStoreMode(storeId))
            .build()
        return try {
            val id = TandemNotificationIds.storeReminder(storeId)
            NotificationManagerCompat.from(context).notify(TandemNotificationIds.STORE_REMINDER_TAG, id, notification)
            true
        } catch (e: SecurityException) {
            // The permission was revoked between the check and the call.
            false
        }
    }

    /** The intent `AppOpenIntents.open(context, "store-mode:<id>")` builds (core:ui is not a dependency here). */
    private fun openStoreMode(storeId: Long): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        val destination = AppOpenDestinations.STORE_MODE_PREFIX + storeId
        val intent = Intent(AppOpenDestinations.ACTION).apply {
            component = launch.component
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // The data keeps one store's PendingIntent apart from another's.
            data = "${AppOpenDestinations.SCHEME}://$destination".toUri()
            putExtra(AppOpenDestinations.EXTRA_DESTINATION, destination)
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        val CHANNEL_ID: String = TandemNotificationChannel.SHOPPING.id

        /** Two hours: long enough for a shopping trip, gone before the next one. */
        const val TIMEOUT_MILLIS: Long = 2 * 60 * 60 * 1000L
    }
}
