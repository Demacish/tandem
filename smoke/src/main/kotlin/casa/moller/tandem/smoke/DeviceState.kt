package casa.moller.tandem.smoke

import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeoutException

/**
 * Phase 1b (integration lane): what the system says about the app under test, read from the
 * instrumentation's shell (`dumpsys`, `cmd`, which run as the `shell` user like `adb shell`) and
 * from public system services. Still a black box: nothing here knows app classes beyond the
 * component names core:timer keeps stable for R8 (core/timer/consumer-rules.pro).
 */
class DeviceState(private val app: TandemApp) {
    private val context = InstrumentationRegistry.getInstrumentation().context

    /** One of the app's active notifications in `dumpsys notification --noredact`. */
    data class Posted(val id: Int, val channel: String?, val fullScreen: Boolean)

    /**
     * The app's active notifications. Each record starts with `NotificationRecord(` and a header
     * line `pkg=<package> ... id=<id> ...: Notification(channel=<id> ...)`; its dump lists
     * `fullscreenIntent=PendingIntent{...}` when one is set. Archived (cancelled) notifications
     * are printed as `StatusBarNotification(`, so they are not counted.
     */
    fun postedNotifications(): List<Posted> {
        val dump = app.shell("dumpsys notification --noredact")
        return dump.split("NotificationRecord(").drop(1).mapNotNull { block ->
            val header = block.substringBefore('\n')
            if (!header.contains("pkg=${TandemApp.PACKAGE} ")) return@mapNotNull null
            val id = ID.find(header)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val record = block.substringBefore("\n\n")
            Posted(
                id = id,
                channel = CHANNEL.find(header)?.groupValues?.get(1) ?: CHANNEL_ID.find(record)?.groupValues?.get(1),
                fullScreen = FULL_SCREEN.containsMatchIn(record),
            )
        }.distinctBy { it.id }
    }

    /** The alarm notifications (core:model TandemNotificationIds.timerAlarm: 4000–4999). */
    fun alarmNotifications(): List<Posted> = postedNotifications().filter { it.id in ALARM_IDS }

    /** The countdown notifications (TandemNotificationIds.timerOngoing: 3000–3999). */
    fun countdownNotifications(): List<Posted> = postedNotifications().filter { it.id in ONGOING_IDS }

    /** True while core:timer's ringing service runs as a foreground service. */
    fun ringingServiceInForeground(): Boolean {
        val dump = app.shell("dumpsys activity services ${TandemApp.PACKAGE}")
        val record = dump.substringAfter(RINGING_SERVICE, missingDelimiterValue = "")
        return record.isNotEmpty() && "isForeground=true" in record.substringBefore("* ServiceRecord")
    }

    fun isInteractive(): Boolean = context.getSystemService(PowerManager::class.java).isInteractive

    fun isKeyguardLocked(): Boolean = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    fun hasPermission(permission: String): Boolean =
        context.packageManager.checkPermission(permission, TandemApp.PACKAGE) == PackageManager.PERMISSION_GRANTED

    /**
     * The power button: screen off, keyguard on. The emulator's lock screen is "swipe" (no
     * credential); `set-disabled false` makes sure there is one at all.
     */
    fun lockScreen() {
        app.shell("cmd lock_settings set-disabled false")
        app.shell("input keyevent KEYCODE_SLEEP")
        waitUntil("the screen is off") { !isInteractive() }
        waitUntil("the keyguard is locked") { isKeyguardLocked() }
    }

    /** Screen on and the (credential-free) keyguard dismissed, as a swipe would. */
    fun unlockScreen() {
        app.shell("input keyevent KEYCODE_WAKEUP")
        app.shell("wm dismiss-keyguard")
        waitUntil("the keyguard is gone") { isInteractive() && !isKeyguardLocked() }
    }

    /** `appops` state of [op] for the app, e.g. "allow" or "default", for the log. */
    fun appOp(op: String): String = app.shell("appops get ${TandemApp.PACKAGE} $op").trim()

    /**
     * Runs [action] and waits for the app's toast [text]: toasts are announced to accessibility
     * (and so to UiAutomation) as notification state events carrying their text.
     */
    fun expectToast(text: String, action: () -> Unit) {
        val seen = CopyOnWriteArrayList<String>()
        try {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeAndWaitForEvent(
                { action() },
                { event ->
                    val texts = event.text.map { it.toString() }
                    val toast = event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
                    if (toast) seen += texts.joinToString(" | ")
                    toast && text in texts
                },
                TOAST_TIMEOUT_MS,
            )
        } catch (e: TimeoutException) {
            throw AssertionError("No toast \"$text\"; toasts seen: $seen", e)
        }
    }

    /** `dumpsys notification` and the services, saved next to the screenshots (smoke-results). */
    fun saveDumps(name: String) {
        runCatching {
            File(TandemApp.outputDir(), "$name-notifications.txt").writeText(app.shell("dumpsys notification --noredact"))
            File(TandemApp.outputDir(), "$name-services.txt").writeText(app.shell("dumpsys activity services ${TandemApp.PACKAGE}"))
        }
    }

    fun waitUntil(what: String, timeoutMs: Long = TandemApp.UI_TIMEOUT_MS, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            if (SystemClock.uptimeMillis() > deadline) {
                throw AssertionError("Timed out after $timeoutMs ms waiting until $what (app running: ${app.isRunning()})")
            }
            SystemClock.sleep(POLL_MS)
        }
    }

    private companion object {
        val ID = Regex("""\bid=(-?\d+)""")
        val CHANNEL = Regex("""Notification\(channel=([^\s)]+)""")
        val CHANNEL_ID = Regex("""mId='([^']+)'""")
        val FULL_SCREEN = Regex("""fullscreenintent=PendingIntent""", RegexOption.IGNORE_CASE)
        val ALARM_IDS = 4000..4999
        val ONGOING_IDS = 3000..3999

        /** core:timer's service; its name is kept by core/timer/consumer-rules.pro. */
        const val RINGING_SERVICE = "casa.moller.tandem.core.timer.ring.TimerRingingService"
        const val TOAST_TIMEOUT_MS = 10_000L
        const val POLL_MS = 250L
    }
}
