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
import casa.moller.tandem.core.data.notification.NotificationChannelInstaller
import casa.moller.tandem.core.domain.pantry.ExpiryDay
import casa.moller.tandem.core.domain.pantry.ExpiryReminderItem
import casa.moller.tandem.core.domain.pantry.ExpiryReminderSummary
import casa.moller.tandem.core.model.navigation.AppOpenDestinations
import casa.moller.tandem.core.model.notification.TandemNotificationChannel
import casa.moller.tandem.core.model.notification.TandemNotificationIds
import casa.moller.tandem.core.sync.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.LocalDate

// Fas 4 (skafferinotis lane), plan §8.8 "Använd upp" and §8.7 "Måltidspåminnelser": the optional
// morning notification "Grädde går ut i morgon – 3 recept" (docs/BYGGE.md "Notis när något går ut").

/** Posts the pantry expiry notification in the channel "meal_reminders". */
internal interface PantryExpiryNotifier {
    /** The system lets the app post in the channel (permission, app notifications and the channel on). */
    fun canPost(): Boolean

    /**
     * Posts [summary] (one notification; a later one replaces it), [locationNames] naming the places
     * of its items; true when it was posted.
     */
    fun post(summary: ExpiryReminderSummary, today: LocalDate, locationNames: Map<Long, String>): Boolean
}

/**
 * [PantryExpiryNotifier] on Android: title "Grädde går ut i morgon – 3 recept", one line per food
 * ("Grädde, Kyl, i morgon", `InboxStyle`), tapping opens "Kan laga nu" with the recipes that use
 * them up first ([AppOpenDestinations.USE_UP]).
 */
@Singleton
internal class AndroidPantryExpiryNotifier @Inject constructor(@ApplicationContext private val context: Context) :
    PantryExpiryNotifier {

    override fun canPost(): Boolean {
        val manager = NotificationManagerCompat.from(context)
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        val channelEnabled = manager.getNotificationChannelCompat(CHANNEL_ID)
            ?.let { it.importance != NotificationManagerCompat.IMPORTANCE_NONE } ?: true
        return permission == PackageManager.PERMISSION_GRANTED && manager.areNotificationsEnabled() && channelEnabled
    }

    override fun post(summary: ExpiryReminderSummary, today: LocalDate, locationNames: Map<Long, String>): Boolean {
        if (!canPost()) return false
        ensureChannel()
        val title = title(summary)
        val style = NotificationCompat.InboxStyle()
        summary.items.forEach { style.addLine(line(it, today, locationNames)) }
        val text = context.getString(R.string.sync_pantry_expiry_text)
        style.setBigContentTitle(title)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tandem_pantry)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openUseUpIntent())
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            // The permission was revoked between the check and the call.
            false
        }
    }

    /** "Grädde går ut i morgon – 3 recept" (plurals; without recipes only the first part). */
    fun title(summary: ExpiryReminderSummary): String {
        val name = capitalized(summary.first.name)
        val resources = context.resources
        val base = when {
            summary.others == 0 && summary.day == ExpiryDay.TODAY -> context.getString(R.string.sync_pantry_expiry_one_today, name)
            summary.others == 0 -> context.getString(R.string.sync_pantry_expiry_one_tomorrow, name)
            else -> {
                val plural = when (summary.day) {
                    ExpiryDay.TODAY -> R.plurals.sync_pantry_expiry_many_today
                    ExpiryDay.TOMORROW -> R.plurals.sync_pantry_expiry_many_tomorrow
                    ExpiryDay.MIXED -> R.plurals.sync_pantry_expiry_many_mixed
                }
                resources.getQuantityString(plural, summary.others, name, summary.others)
            }
        }
        if (summary.recipeCount <= 0) return base
        return resources.getQuantityString(R.plurals.sync_pantry_expiry_with_recipes, summary.recipeCount, base, summary.recipeCount)
    }

    private fun line(item: ExpiryReminderItem, today: LocalDate, locationNames: Map<Long, String>): String {
        val day = context.getString(if (item.expires == today) R.string.sync_pantry_expiry_day_today else R.string.sync_pantry_expiry_day_tomorrow)
        val place = locationNames[item.locationId]
        val name = capitalized(item.name)
        return if (place == null) {
            context.getString(R.string.sync_pantry_expiry_line_no_place, name, day)
        } else {
            context.getString(R.string.sync_pantry_expiry_line, name, place, day)
        }
    }

    private fun capitalized(name: String): String {
        val locale = context.resources.configuration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    /** The channel "Måltidspåminnelser" (idempotent; plan 8.7: standard importance, optional). */
    fun ensureChannel() {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.sync_notification_channel_meal_reminders))
                .setDescription(context.getString(R.string.sync_notification_channel_meal_reminders_description))
                .build(),
        )
    }

    /** The same intent `AppOpenIntents.open(context, USE_UP)` builds (core:ui is not a dependency here). */
    private fun openUseUpIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        val intent = Intent(AppOpenDestinations.ACTION).apply {
            component = launch.component
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = "${AppOpenDestinations.SCHEME}://${AppOpenDestinations.USE_UP}".toUri()
            putExtra(AppOpenDestinations.EXTRA_DESTINATION, AppOpenDestinations.USE_UP)
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private companion object {
        val CHANNEL_ID: String = TandemNotificationChannel.MEAL_REMINDERS.id
        const val NOTIFICATION_ID = TandemNotificationIds.PANTRY_EXPIRY
    }
}

/** The channel "Måltidspåminnelser" for the app's startup (plan 8.7; Fas 4 is its first user). */
internal class MealRemindersChannelInstaller @Inject constructor(private val notifier: AndroidPantryExpiryNotifier) :
    NotificationChannelInstaller {
    override fun createChannels() = notifier.ensureChannel()
}
