package casa.moller.tandem.core.sync.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import casa.moller.tandem.core.domain.mealplan.MealReminder
import casa.moller.tandem.core.model.navigation.AppOpenDestinations
import casa.moller.tandem.core.model.notification.TandemNotificationChannel
import casa.moller.tandem.core.model.notification.TandemNotificationIds
import casa.moller.tandem.core.sync.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// Fas 5 (planera lane), plan §8.3 "Påminnelse kvällen innan" and §8.7 "Måltidspåminnelser": the
// evening reminder "Tina ut kycklingen" (docs/BYGGE.md "Påminnelser kvällen innan och på morgonen").

/** Posts and removes the evening reminders in the channel "meal_reminders" (tests record the calls). */
internal interface MealReminderNotifier {
    /** The system lets the app post in the channel (permission, app notifications and the channel on). */
    fun canPost(): Boolean

    /** Posts [reminder] (one notification per reminder key); true when it was posted. */
    fun post(reminder: MealReminder): Boolean

    /** Removes the notification of the reminder with [key] (the meal moved, was removed or lost its reminder). */
    fun cancel(key: String)
}

/**
 * [MealReminderNotifier] on Android: the reminder's own words as the title ("Tina ut kycklingen"),
 * "Middag i morgon: Kycklinggryta" below, tapping opens Veckan at the meal's day
 * ([AppOpenDestinations.WEEK_DAY_PREFIX]). Tag [TandemNotificationIds.MEAL_REMINDER_TAG], id from
 * the key, so each meal has its own and a second post of the same one replaces it.
 */
@Singleton
internal class AndroidMealReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    // The channel and the permission check are the morning notification's (same channel, plan 8.7).
    private val channel: AndroidPantryExpiryNotifier,
) : MealReminderNotifier {

    override fun canPost(): Boolean = channel.canPost()

    override fun post(reminder: MealReminder): Boolean {
        if (!canPost()) return false
        channel.ensureChannel()
        val title = capitalized(reminder.text)
        val text = text(reminder)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tandem_pantry)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openDayIntent(reminder))
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(TAG, TandemNotificationIds.mealReminder(reminder.key), notification)
            true
        } catch (e: SecurityException) {
            // The permission was revoked between the check and the call.
            false
        }
    }

    override fun cancel(key: String) {
        NotificationManagerCompat.from(context).cancel(TAG, TandemNotificationIds.mealReminder(key))
    }

    /** "Middag i morgon: Kycklinggryta" (the meal type as Tandoor names it). */
    fun text(reminder: MealReminder): String = if (reminder.mealTypeName.isBlank()) {
        context.getString(R.string.sync_meal_reminder_text_no_type, reminder.mealTitle)
    } else {
        context.getString(R.string.sync_meal_reminder_text, reminder.mealTypeName, reminder.mealTitle)
    }

    private fun capitalized(text: String): String {
        val locale = context.resources.configuration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
        return text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    /** The same intent `AppOpenIntents.open(context, "week:<date>")` builds (core:ui is not a dependency here). */
    private fun openDayIntent(reminder: MealReminder): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        val destination = AppOpenDestinations.WEEK_DAY_PREFIX + reminder.date
        val intent = Intent(AppOpenDestinations.ACTION).apply {
            component = launch.component
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = "${AppOpenDestinations.SCHEME}://$destination".toUri()
            putExtra(AppOpenDestinations.EXTRA_DESTINATION, destination)
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    internal companion object {
        val CHANNEL_ID: String = TandemNotificationChannel.MEAL_REMINDERS.id
        const val TAG: String = TandemNotificationIds.MEAL_REMINDER_TAG
    }
}
