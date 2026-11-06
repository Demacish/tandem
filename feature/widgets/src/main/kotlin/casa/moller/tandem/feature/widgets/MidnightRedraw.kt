package casa.moller.tandem.feature.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The "I dag" widget turns to the new day just after midnight. The widget has no period of its own
 * (`updatePeriodMillis = 0`) and a process that would notice the new day is usually gone at night.
 *
 * An inexact, non-waking alarm (no permission needed, it fires when the phone is next awake) starts
 * [MidnightRedrawReceiver]. It exists only while a widget is placed: set when the first one is
 * placed ([TonightWidgetReceiver]) and at every process start ([WidgetUpdater]; alarms do not survive
 * a reboot, and a widget's update starts a process then).
 */
internal object MidnightRedraw {
    /** How far after midnight the window opens, and how long it is. */
    private val WINDOW_START = 1.minutes
    private val WINDOW_LENGTH = 30.minutes

    /** Epoch milliseconds of the first moment after [now] where the date in [zone] has changed. */
    fun nextAt(now: Instant, zone: ZoneId): Long = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone)
        .toInstant().toEpochMilli() + WINDOW_START.inWholeMilliseconds

    /** True when an "I dag" widget is placed. */
    fun wanted(context: Context): Boolean = runCatching {
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, TonightWidgetReceiver::class.java))
            .isNotEmpty()
    }.getOrDefault(false)

    fun schedule(context: Context) {
        val at = nextAt(Instant.now(), ZoneId.systemDefault())
        runCatching {
            context.getSystemService(AlarmManager::class.java)
                .setWindow(AlarmManager.RTC, at, WINDOW_LENGTH.inWholeMilliseconds, pendingIntent(context))
        }
    }

    fun cancel(context: Context) {
        runCatching { context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, MidnightRedrawReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Redraws "I dag" for the new day and sets the next alarm. Not exported: only [MidnightRedraw]'s alarm sends it. */
class MidnightRedrawReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        MidnightRedraw.schedule(context)
        CoroutineScope(Dispatchers.Default).launch {
            try {
                runCatching { TonightWidget().updateAll(context) }
            } finally {
                pending.finish()
            }
        }
    }
}
