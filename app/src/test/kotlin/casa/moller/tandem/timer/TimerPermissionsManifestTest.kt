package casa.moller.tandem.timer

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The timer permissions in the app's MERGED manifest (plan 8.1, 4.4; docs/BYGGE.md "Timers"):
 * core:timer declares both exact-alarm permissions and each flavor removes the one that does not
 * apply. core:timer's own tests only see the library manifest, so this checks that the flavor
 * manifests really take effect. CI runs the sideload variant; the play branch documents (and checks
 * when testPlayDebugUnitTest runs) the other one.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class TimerPermissionsManifestTest {

    private val requested: Set<String> by lazy {
        val context = RuntimeEnvironment.getApplication()
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            .requestedPermissions
            .orEmpty()
            .toSet()
    }

    @Test
    fun everyFlavorHasTheSharedTimerPermissions() {
        listOf(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED",
            "android.permission.WAKE_LOCK",
            "android.permission.VIBRATE",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.POST_PROMOTED_NOTIFICATIONS",
            "com.android.alarm.permission.SET_ALARM",
        ).forEach { assertTrue("$it is missing", it in requested) }
    }

    @Test
    fun exactAlarmsAndTheFullScreenAlarmFollowTheFlavor() {
        when (BuildConfig.FLAVOR) {
            "sideload" -> {
                assertTrue(USE_EXACT_ALARM in requested)
                assertTrue(USE_FULL_SCREEN_INTENT in requested)
                assertFalse(SCHEDULE_EXACT_ALARM in requested)
            }

            "play" -> {
                assertTrue(SCHEDULE_EXACT_ALARM in requested)
                assertFalse(USE_EXACT_ALARM in requested)
                assertFalse(USE_FULL_SCREEN_INTENT in requested)
            }

            else -> fail("Unknown flavor ${BuildConfig.FLAVOR}")
        }
    }

    private companion object {
        const val USE_EXACT_ALARM = "android.permission.USE_EXACT_ALARM"
        const val SCHEDULE_EXACT_ALARM = "android.permission.SCHEDULE_EXACT_ALARM"
        const val USE_FULL_SCREEN_INTENT = "android.permission.USE_FULL_SCREEN_INTENT"
    }
}
