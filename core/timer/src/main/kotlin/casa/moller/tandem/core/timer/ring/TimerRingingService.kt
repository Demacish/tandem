package casa.moller.tandem.core.timer.ring

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.ServiceCompat
import casa.moller.tandem.core.model.notification.TandemNotificationIds
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.timer.di.TimerEntryPoint
import casa.moller.tandem.core.timer.engine.TimerEngineConfig
import casa.moller.tandem.core.timer.platform.TimerSystem
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch

/**
 * The short foreground service that runs ONLY while an alarm rings (plan 8.1: "kort
 * förgrundstjänst bara medan det ringer"); never during the countdown.
 *
 * - **Type:** `systemExempted` when exact alarms are allowed (Android 14+: for apps with exact
 *   alarms that continue an alarm in the background), otherwise `shortService` (play build
 *   without exact alarms; the system stops it after about 3 minutes, [onTimeout]).
 * - **What it does:** keeps the process and the CPU awake (partial wake lock) so the alarm keeps
 *   ringing on a locked screen, and shows its own quiet notification ("2 timers are ringing",
 *   "Stop all"). The sound is normally the insistent alarm notification; whenever that cannot
 *   sound ([TimerSystem.alarmNotificationAudible]: no notification permission, or the user set the
 *   `timer_alarm` channel to "Silent", blocked it or removed its sound) the service plays the alarm
 *   sound and vibration itself, so a timer never ends in silence.
 * - **Ends** when nothing rings any more. The engine never stops it from outside
 *   (`stopService` right after `startForegroundService` can crash the app before
 *   `startForeground` ran): [AndroidRinger.stop] sends another start command, every command calls
 *   `startForeground` first, and a command that finds nothing ringing stops the service with its
 *   own start id. After [MAX_RINGING] it asks the engine to silence what rang too long, then stops.
 * - **Its notification is no group summary** (fix-data, 0.2.0 review): removing a summary takes
 *   its group's children with it, which would remove the alarms and the quiet "Rang at 18:45"
 *   notices when the service stops.
 */
internal class TimerRingingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val stopAfterTimeout = Runnable { silenceAndStop() }
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val entryPoint = TimerEntryPoint.from(this)
        val ringing = entryPoint.engine().timers.value.filter { it.state == TimerState.RINGING }
        val notification = entryPoint.notifier().ringingServiceNotification(ringing)
        val system = entryPoint.system()
        // startForeground must come first, even when there is nothing to ring any more.
        val started = startForegroundOfType(notification, foregroundServiceType(system.exactAlarmsAllowed())) ||
            startForegroundOfType(notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        if (!started || ringing.isEmpty()) {
            // Only when no later command waits (that one must reach startForeground, too).
            stopSelf(startId)
            return START_NOT_STICKY
        }
        acquireWakeLock()
        if (system.alarmNotificationAudible()) stopSoundAndVibration() else startSoundAndVibration()
        handler.removeCallbacks(stopAfterTimeout)
        handler.postDelayed(stopAfterTimeout, MAX_RINGING.inWholeMilliseconds)
        return START_NOT_STICKY
    }

    /** shortService ran out (Android 14). The alarm notification (if any) keeps ringing until the engine's auto-silence. */
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    /** shortService ran out (Android 15+). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(stopAfterTimeout)
        stopSoundAndVibration()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    /** [MAX_RINGING]: the auto-silence alarm should have come; let the engine silence (and note) what rang too long. */
    private fun silenceAndStop() {
        val entryPoint = TimerEntryPoint.from(this)
        entryPoint.scope().launch { entryPoint.engine().onAutoSilence() }
        stopSelf()
    }

    private fun startForegroundOfType(notification: Notification, type: Int): Boolean = try {
        ServiceCompat.startForeground(this, TandemNotificationIds.TIMER_RINGING_SERVICE, notification, type)
        true
    } catch (e: RuntimeException) {
        // SecurityException (systemExempted without exact alarms), ForegroundServiceStartNotAllowedException.
        false
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(MAX_RINGING.inWholeMilliseconds)
        }
    }

    private fun stopSoundAndVibration() {
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun startSoundAndVibration() {
        if (ringtone != null) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ringtone = uri?.let { RingtoneManager.getRingtone(this, it) }?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            isLooping = true
            play()
        }
        vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator?.apply {
            val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, 0)
            vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        }
    }

    companion object {
        /** True between onCreate and onDestroy of this process's service ([AndroidRinger.stop] sends nothing otherwise). */
        @Volatile
        internal var running: Boolean = false

        /**
         * Start commands ([AndroidRinger]); the service handles both the same way (startForeground,
         * then ring or stop by what the engine says), the action only tells them apart in tests.
         */
        const val ACTION_RING = "casa.moller.tandem.timer.service.RING"
        const val ACTION_STOP = "casa.moller.tandem.timer.service.STOP"

        /** The longest the service runs: a little longer than the engine's auto-silence. */
        val MAX_RINGING: Duration = TimerEngineConfig.DEFAULT_RING_TIMEOUT + 30.seconds

        private const val WAKE_LOCK_TAG = "tandem:timer-ringing"
        private val VIBRATION_PATTERN = longArrayOf(0L, 600L, 400L, 600L, 400L, 600L, 1200L)

        /** `systemExempted` needs exact alarms; without them only `shortService` is allowed. */
        fun foregroundServiceType(exactAlarmsAllowed: Boolean): Int = if (exactAlarmsAllowed) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        }
    }
}
