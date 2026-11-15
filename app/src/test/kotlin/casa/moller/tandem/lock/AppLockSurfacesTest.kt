package casa.moller.tandem.lock

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.MainActivity
import casa.moller.tandem.core.data.security.AppLock
import casa.moller.tandem.core.sync.security.DefaultAppLock
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.w3c.dom.Element

/**
 * Fas 5 (las lane, plan §5.5, "Klart när": nothing on the alarm path behind the lock). The lock
 * lives in MainActivity only (its lock screen); the full-screen alarm is an activity of its own
 * that shows on the phone's lock screen, and neither it nor the timers' service and receivers
 * (Stop, +1 min, boot), the widgets' receivers, the tile, the watch's listener or the store
 * reminder's receiver hold the lock. The app's MERGED manifest and the classes are read, not a
 * copy of them.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppLockSurfacesTest {

    /** The `<activity>` elements of the merged manifest (AGP hands its path to Robolectric). */
    private val activities: Map<String, Element> by lazy {
        val properties = Properties()
        val config = checkNotNull(javaClass.classLoader?.getResourceAsStream(TEST_CONFIG)) { "no $TEST_CONFIG" }
        config.use { properties.load(it) }
        val manifest = File(checkNotNull(properties.getProperty("android_merged_manifest")) { "no merged manifest" })
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(manifest)
        val nodes = document.getElementsByTagName("activity")
        (0 until nodes.length).map { nodes.item(it) as Element }.associateBy { it.getAttributeNS(ANDROID, "name") }
    }

    @Test
    fun theAlarmIsItsOwnActivityOnThePhonesLockScreen() {
        val alarm = checkNotNull(activities[ALARM_ACTIVITY]) { "the alarm activity is in the app" }
        assertEquals("the alarm shows on the phone's lock screen", "true", alarm.getAttributeNS(ANDROID, "showWhenLocked"))
        // A task of its own: Stop and +1 min never bring MainActivity (and its lock screen) along.
        assertTrue(alarm.hasAttributeNS(ANDROID, "taskAffinity"))
        assertEquals("", alarm.getAttributeNS(ANDROID, "taskAffinity"))
    }

    @Test
    fun theAppsScreensAreNotShownOnThePhonesLockScreen() {
        val main = activities.getValue(MainActivity::class.java.name)
        assertTrue("MainActivity must not show on the phone's lock screen", main.getAttributeNS(ANDROID, "showWhenLocked") != "true")
    }

    @Test
    fun nothingOnTheAlarmTimerReminderWidgetOrWatchPathHoldsTheLock() {
        EXEMPT_COMPONENTS.forEach { name ->
            val type = Class.forName(name)
            val holders = generateSequence(type) { it.superclass }
                // The class and Hilt's generated base class (same package); not the framework's.
                .takeWhile { it.name.startsWith("casa.moller.tandem.") }
                .flatMap { it.declaredFields.asSequence() }
                .filter { AppLock::class.java.isAssignableFrom(it.type) || it.type == DefaultAppLock::class.java }
                .toList()
            assertTrue("$name holds the app lock: $holders", holders.isEmpty())
        }
    }

    private companion object {
        const val TEST_CONFIG = "com/android/tools/test_config.properties"
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val ALARM_ACTIVITY = "casa.moller.tandem.core.timer.ring.TimerAlarmActivity"

        val EXEMPT_COMPONENTS = listOf(
            ALARM_ACTIVITY,
            "casa.moller.tandem.core.timer.ring.TimerRingingService",
            "casa.moller.tandem.core.timer.alarm.TimerAlarmReceiver",
            "casa.moller.tandem.core.timer.alarm.TimerSystemEventReceiver",
            "casa.moller.tandem.feature.widgets.ShoppingListWidgetReceiver",
            "casa.moller.tandem.feature.widgets.TonightWidgetReceiver",
            "casa.moller.tandem.feature.widgets.ShoppingTileService",
            "casa.moller.tandem.core.watch.PhoneWearListenerService",
            "casa.moller.tandem.core.sync.storereminder.StoreGeofenceReceiver",
        )
    }
}
