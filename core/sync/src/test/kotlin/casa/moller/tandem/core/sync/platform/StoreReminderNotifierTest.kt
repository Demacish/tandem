package casa.moller.tandem.core.sync.platform

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.navigation.AppOpenDestinations
import casa.moller.tandem.core.model.notification.TandemNotificationChannel
import casa.moller.tandem.core.model.notification.TandemNotificationIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Fas 5 (butik lane, plan §7 #34, §8.7): "You're near Testbutiken – 2 things on the list" in the
 * shopping list's channel, one per store under its tag, gone by itself after two hours, opening
 * store mode for that store; never without the permission or with the channel off; plurals in
 * English and Swedish.
 */
@RunWith(AndroidJUnit4::class)
class StoreReminderNotifierTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = AndroidStoreReminderNotifier(context, AndroidShoppingChangeNotifier(context))

    private fun posted(): Notification = shadowOf(manager).allNotifications.single()

    @Test
    fun theReminderSaysTheStoreAndWhatIsLeftInTheShoppingChannel() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(notifier.post(storeId = 7, storeName = "Testbutiken", count = 2))
        val notification = posted()
        assertEquals(TandemNotificationChannel.SHOPPING.id, notification.channelId)
        assertEquals("You're near Testbutiken", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("2 things on the list", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(Notification.CATEGORY_REMINDER, notification.category)
        assertEquals(AndroidStoreReminderNotifier.TIMEOUT_MILLIS, notification.timeoutAfter)
        val tapped = shadowOf(notification.contentIntent).savedIntent
        assertEquals(AppOpenDestinations.ACTION, tapped.action)
        assertEquals(AppOpenDestinations.STORE_MODE_PREFIX + 7, tapped.getStringExtra(AppOpenDestinations.EXTRA_DESTINATION))
        val active = manager.activeNotifications.single()
        assertEquals(TandemNotificationIds.STORE_REMINDER_TAG, active.tag)
        assertEquals(TandemNotificationIds.storeReminder(7), active.id)
    }

    @Test
    fun oneThingAndAStoreThatIsGone() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(notifier.post(storeId = 7, storeName = null, count = 1))
        assertEquals("You're near the store", posted().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("1 thing on the list", posted().extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    @Config(qualifiers = "sv")
    fun swedishWords() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.post(storeId = 7, storeName = "Testbutiken", count = 7)
        assertEquals("Du är nära Testbutiken", posted().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("7 saker på listan", posted().extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        manager.cancelAll()
        notifier.post(storeId = 8, storeName = "Hörnan", count = 1)
        assertEquals("1 sak på listan", posted().extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun nothingWithoutThePermissionWithTheChannelOffOrWithAnEmptyList() {
        assertFalse(notifier.canPost())
        assertFalse(notifier.post(storeId = 7, storeName = "Testbutiken", count = 2))
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(notifier.post(storeId = 7, storeName = "Testbutiken", count = 0))
        val off = NotificationChannel(TandemNotificationChannel.SHOPPING.id, "Shopping list", NotificationManager.IMPORTANCE_NONE)
        manager.createNotificationChannel(off)
        assertFalse(notifier.canPost())
        assertFalse(notifier.post(storeId = 7, storeName = "Testbutiken", count = 2))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
