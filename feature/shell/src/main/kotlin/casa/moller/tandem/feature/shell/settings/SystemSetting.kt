package casa.moller.tandem.feature.shell.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import casa.moller.tandem.core.common.net.LocalNetworkRules
import casa.moller.tandem.core.model.notification.TandemNotificationChannel

/**
 * The Android settings screens the permissions overview opens (one per row, plan 8.7 and the
 * design correction "each row opens the right system screen"). Only intents: Tandem itself never
 * changes a permission. When a screen is missing on a device, the app's own settings page opens
 * instead.
 */
internal enum class SystemSetting {
    /** The app's notifications (on/off and all channels). */
    NOTIFICATIONS,

    /** The "Timers (alarm)" notification channel (sound, on/off). */
    ALARM_CHANNEL,

    /** "Alarms & reminders" (exact alarms; play build). */
    EXACT_ALARMS,

    /** "Full-screen notifications" (the alarm over the lock screen; sideload build). */
    FULL_SCREEN,

    /** The app's page in Android's settings (battery: "Unrestricted"). */
    APP_DETAILS,
    ;

    fun intent(context: Context): Intent {
        val packageName = context.packageName
        val packageUri = Uri.fromParts("package", packageName, null)
        return when (this) {
            NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

            ALARM_CHANNEL -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, TandemNotificationChannel.TIMER_ALARM.id)

            EXACT_ALARMS -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)

            FULL_SCREEN -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri)

            APP_DETAILS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        }
    }

    /** Opens the screen, or the app's settings page when the device has no such screen. */
    fun open(context: Context) {
        try {
            context.startActivity(intent(context))
        } catch (e: ActivityNotFoundException) {
            runCatching { context.startActivity(APP_DETAILS.intent(context)) }
        } catch (e: SecurityException) {
            runCatching { context.startActivity(APP_DETAILS.intent(context)) }
        }
    }

    companion object {
        /** True when Android does not optimise Tandem's battery use; null when unknown. */
        fun batteryUnrestricted(context: Context): Boolean? =
            context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName)

        /** Whether the scanners may use the camera; null on a device without a camera (no row then). */
        fun cameraAllowed(context: Context): Boolean? = if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            null
        } else {
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }

        // Device test 2026-11-14 (B12): the permissions of handsfree, the calendar and the store reminder.

        /** Whether handsfree's voice commands may listen; null on a device without a microphone (no row then). */
        fun microphoneAllowed(context: Context): Boolean? {
            val hasMicrophone = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
            return if (hasMicrophone) context.granted(Manifest.permission.RECORD_AUDIO) else null
        }

        /** Whether "Show in the phone's calendar" may read and write the calendar (both are needed). */
        fun calendarAllowed(context: Context): Boolean =
            context.granted(Manifest.permission.READ_CALENDAR) && context.granted(Manifest.permission.WRITE_CALENDAR)

        /** How much of the position the reminder near the store may use. */
        fun locationAccess(context: Context): LocationAccess = LocationAccess.of(
            precise = context.granted(Manifest.permission.ACCESS_FINE_LOCATION),
            approximate = context.granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            background = context.granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        )

        /**
         * Fas 5 (sdk37 lane): whether Tandem may reach a server on the local network ("Nearby
         * devices"); null on Android 16 and older, where the permission does not exist (no row then).
         */
        fun localNetworkAllowed(context: Context): Boolean? =
            if (LocalNetworkRules.enforced(Build.VERSION.SDK_INT, context.applicationInfo.targetSdkVersion)) {
                context.granted(LocalNetworkRules.PERMISSION)
            } else {
                null
            }

        private fun Context.granted(permission: String): Boolean = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
