package casa.moller.tandem.core.sync.platform

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.domain.pantry.ExpiryDay
import casa.moller.tandem.core.domain.pantry.ExpiryReminder
import casa.moller.tandem.core.domain.pantry.ExpiryReminderItem
import casa.moller.tandem.core.domain.pantry.ExpiryReminderSummary
import casa.moller.tandem.core.model.notification.TandemNotificationChannel
import casa.moller.tandem.core.model.notification.TandemNotificationIds
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Fas 4 (skafferinotis lane): the words, channel and target of "Grädde går ut i morgon – 3 recept". */
@RunWith(AndroidJUnit4::class)
class PantryExpiryNotifierTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val today = LocalDate(2026, 11, 5)
    private val tomorrow = LocalDate(2026, 11, 6)

    private fun item(name: String, expires: LocalDate = tomorrow, location: Long = 1) =
        ExpiryReminderItem(ExpiryReminder.keyOf(name, expires), foodId = null, name = name, locationId = location, expires = expires)

    private fun summary(day: ExpiryDay, recipes: Int, vararg names: String) =
        ExpiryReminderSummary(names.map { item(it) }, day, recipes)

    @Test
    fun theTitleNamesTheFirstFoodTheOthersAndTheRecipes() {
        val notifier = AndroidPantryExpiryNotifier(context)
        assertEquals("Cream expires tomorrow – 3 recipes", notifier.title(summary(ExpiryDay.TOMORROW, 3, "cream")))
        assertEquals("Cream expires today – 1 recipe", notifier.title(summary(ExpiryDay.TODAY, 1, "cream")))
        assertEquals("Cream expires tomorrow", notifier.title(summary(ExpiryDay.TOMORROW, 0, "cream")))
        assertEquals("Cream and 2 more expire tomorrow – 5 recipes", notifier.title(summary(ExpiryDay.TOMORROW, 5, "cream", "milk", "eggs")))
        assertEquals("Cream and 1 more expire today and tomorrow", notifier.title(summary(ExpiryDay.MIXED, 0, "cream", "milk")))
    }

    @Test
    @Config(qualifiers = "sv")
    fun swedishTitles() {
        val notifier = AndroidPantryExpiryNotifier(context)
        assertEquals("Grädde går ut i morgon – 3 recept", notifier.title(summary(ExpiryDay.TOMORROW, 3, "grädde")))
        assertEquals("Grädde och 2 till går ut i morgon – 5 recept", notifier.title(summary(ExpiryDay.TOMORROW, 5, "grädde", "mjölk", "ägg")))
        assertEquals("Grädde och 1 till går ut i dag", notifier.title(summary(ExpiryDay.TODAY, 0, "grädde", "mjölk")))
    }

    @Test
    fun nothingIsPostedWithoutThePermissionOrWithTheChannelOff() {
        val notifier = AndroidPantryExpiryNotifier(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertFalse(notifier.canPost())
        assertFalse(notifier.post(summary(ExpiryDay.TOMORROW, 3, "grädde"), today, emptyMap()))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())

        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val off = NotificationChannel(TandemNotificationChannel.MEAL_REMINDERS.id, "Meal reminders", NotificationManager.IMPORTANCE_NONE)
        manager.createNotificationChannel(off)
        assertFalse(notifier.canPost())
    }

    @Test
    fun oneNotificationInMealRemindersOpensUseUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifier = AndroidPantryExpiryNotifier(context)
        val manager = context.getSystemService(NotificationManager::class.java)

        assertTrue(notifier.post(summary(ExpiryDay.TOMORROW, 3, "grädde", "mjölk"), today, mapOf(1L to "Kyl")))
        assertTrue(notifier.post(summary(ExpiryDay.TOMORROW, 3, "grädde"), today, mapOf(1L to "Kyl")))

        val channel = manager.getNotificationChannel(TandemNotificationChannel.MEAL_REMINDERS.id)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals("Meal reminders", channel.name.toString())
        val posted = shadowOf(manager).allNotifications
        assertEquals("a later one replaces the earlier", 1, posted.size)
        val notification = posted.single()
        assertEquals(TandemNotificationChannel.MEAL_REMINDERS.id, notification.channelId)
        assertEquals("Grädde expires tomorrow – 3 recipes", notification.extras.getString(Notification.EXTRA_TITLE))
        val lines = notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }
        assertEquals(listOf("Grädde, Kyl, tomorrow"), lines)
        assertNotNull("posted under its own id", shadowOf(manager).getNotification(TandemNotificationIds.PANTRY_EXPIRY))
    }
}
