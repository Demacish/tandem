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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The short foreground service that runs ONLY while an alarm rings (plan 8.1: "kort
 * förgrundstjänst bara medan det ringer"); never during the countdown.
 *
 * - **Type:** `systemExempted` when exact alarms are allowed (Android 14+: for apps with exact
 *   alarms that continue an alarm in the background), otherwise `shortService` (play build
 *   without exact alarms; the system stops it after about 3 minutes, [onTimeout]).
 * - **What it does:** keeps the process and the CPU awake (partial wake lock) so the alarm keeps
 *   ringing on a locked screen, and shows the group summary of the alarms ("Stop all"). The sound
 *   itself is the insistent alarm notification; only when notifications are NOT allowed does the
 *   service play the alarm sound and vibration itself, so a timer still rings without the
 *   permission.
 * - **Ends** when the engine stops it (nothing rings any more, after "Stop", "+1 min" or the
 *   auto-silence), or by itself after [MAX_RINGING].
 */
internal class TimerRingingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val stopAfterTimeout = Runnable { stopSelf() }
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val entryPoint = TimerEntryPoint.from(this)
        val ringing = entryPoint.engine().timers.value.filter { it.state == TimerState.RINGING }
        val notification = entryPoint.notifier().ringingServiceNotification(ringing)
        // startForeground must come first, even when there is nothing to ring any more.
        val started = startForegroundOfType(notification, foregroundServiceType(entryPoint.system().exactAlarmsAllowed())) ||
            startForegroundOfType(notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        if (!started || ringing.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        if (!entryPoint.system().notificationsAllowed()) startSoundAndVibration()
        handler.removeCallbacks(stopAfterTimeout)
        handler.postDelayed(stopAfterTimeout, MAX_RINGING.inWholeMilliseconds)
        return START_NOT_STICKY
    }

    /** shortService ran out (Android 14). */
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    /** shortService ran out (Android 15+). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopAfterTimeout)
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        vibrator = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
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
