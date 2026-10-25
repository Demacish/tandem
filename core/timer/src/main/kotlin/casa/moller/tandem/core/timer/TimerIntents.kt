package casa.moller.tandem.core.timer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.timer.alarm.TimerAlarmReceiver
import casa.moller.tandem.core.timer.store.TimerSnapshot

/**
 * How a timer notification, the alarm's "show" button in the system clock UI and the lock-screen
 * alarm open the app. The app's launcher activity is started with [ACTION_OPEN_TIMER] and the
 * extras below (`FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP`, so a running app gets
 * `onNewIntent`). The integration lane maps it to cook mode (`CookKey(recipeId, startStep =
 * stepIndex)`) or to `TimersKey` for a timer without a recipe; until then the app simply opens.
 */
object TimerIntents {
    /** Action of the intent that opens the app from a timer. */
    const val ACTION_OPEN_TIMER: String = "casa.moller.tandem.action.OPEN_TIMER"

    /** [CookTimer.id] (Long) of the timer that was tapped. */
    const val EXTRA_TIMER_ID: String = "casa.moller.tandem.extra.TIMER_ID"

    /** [CookTimer.recipeId] (Long); absent for a free timer. */
    const val EXTRA_RECIPE_ID: String = "casa.moller.tandem.extra.RECIPE_ID"

    /** [CookTimer.stepIndex] (Int, 0-based); absent when the timer has no step. */
    const val EXTRA_STEP_INDEX: String = "casa.moller.tandem.extra.STEP_INDEX"

    /**
     * [CookTimer.sourceKey] (String: demo mode or an account id, core:model `TimerSource`);
     * absent for a timer without one. The app opens cook mode only for the active source.
     */
    const val EXTRA_SOURCE_KEY: String = "casa.moller.tandem.extra.SOURCE_KEY"

    // Broadcasts to TimerAlarmReceiver (only our own explicit PendingIntents).
    internal const val ACTION_RING = "casa.moller.tandem.timer.RING"
    internal const val ACTION_AUTO_SILENCE = "casa.moller.tandem.timer.AUTO_SILENCE"
    internal const val ACTION_PLUS_ONE_MINUTE = "casa.moller.tandem.timer.PLUS_ONE_MINUTE"
    internal const val ACTION_STOP = "casa.moller.tandem.timer.STOP"
    internal const val ACTION_STOP_ALL = "casa.moller.tandem.timer.STOP_ALL"
    internal const val ACTION_PAUSE = "casa.moller.tandem.timer.PAUSE"
    internal const val ACTION_RESUME = "casa.moller.tandem.timer.RESUME"
    internal const val ACTION_CANCEL = "casa.moller.tandem.timer.CANCEL"

    /** The ringing timer itself on [ACTION_RING] ([casa.moller.tandem.core.timer.store.TimerSnapshot]). */
    internal const val EXTRA_TIMER_SNAPSHOT = "casa.moller.tandem.timer.extra.SNAPSHOT"

    private const val SCHEME = "tandem-timer"

    /** The intent that opens the app at [timer] (or just opens it for null), or null without a launcher activity. */
    fun openTimer(context: Context, timer: CookTimer?): Intent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return Intent(ACTION_OPEN_TIMER).apply {
            component = launch.component
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (timer != null) {
                // The data makes the PendingIntent unique per timer (extras do not count).
                data = timerUri(timer.id)
                putExtra(EXTRA_TIMER_ID, timer.id)
                timer.recipeId?.let { putExtra(EXTRA_RECIPE_ID, it) }
                timer.stepIndex?.let { putExtra(EXTRA_STEP_INDEX, it) }
                timer.sourceKey?.let { putExtra(EXTRA_SOURCE_KEY, it) }
            }
        }
    }

    internal fun openTimerPendingIntent(context: Context, timer: CookTimer?): PendingIntent? {
        val intent = openTimer(context, timer) ?: return null
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** A broadcast to [TimerAlarmReceiver]; [timerId] null for actions about every timer. */
    internal fun receiverIntent(context: Context, action: String, timerId: Long?): Intent =
        Intent(context, TimerAlarmReceiver::class.java).apply {
            this.action = action
            data = if (timerId == null) "$SCHEME://all".toUri() else timerUri(timerId)
            if (timerId != null) putExtra(EXTRA_TIMER_ID, timerId)
        }

    internal fun receiverPendingIntent(context: Context, action: String, timerId: Long?): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        receiverIntent(context, action, timerId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** [ACTION_RING] for [timer], carrying the timer itself (extras do not change which PendingIntent it is). */
    internal fun ringPendingIntent(context: Context, timer: CookTimer): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        receiverIntent(context, ACTION_RING, timer.id).putExtra(EXTRA_TIMER_SNAPSHOT, TimerSnapshot.encode(timer)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The existing broadcast PendingIntent, or null when none was created (nothing to cancel). */
    internal fun existingReceiverPendingIntent(context: Context, action: String, timerId: Long): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            0,
            receiverIntent(context, action, timerId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )

    private fun timerUri(timerId: Long): Uri = "$SCHEME://timer/$timerId".toUri()
}
