package casa.moller.tandem.core.model.notification

/** Channel importance, mapped to `NotificationManagerCompat.IMPORTANCE_*` by the Android code. */
enum class ChannelImportance {
    LOW,
    DEFAULT,
    HIGH,
}

/** The sound a channel plays. */
enum class ChannelSound {
    /** No sound and no vibration (`setSound(null, null)`). */
    SILENT,

    /** The default notification sound. */
    NOTIFICATION,

    /** The default alarm sound with `AudioAttributes.USAGE_ALARM`, so it rings at alarm volume. */
    ALARM,
}

/**
 * EVERY notification channel Tandem has or will have (plan 8.7). Decided in Phase 1b and never
 * renamed: a channel id is the key of the user's choices in the system settings, so changing an
 * id silently resets them. New channels get a NEW id; a removed channel is deleted with
 * `deleteNotificationChannel`, never reused.
 *
 * Each module creates the channels it posts to, right before posting, with these values and its
 * own localized name/description (`NotificationChannelCompat.Builder(id, importance)`); creating
 * an existing channel only updates its name and description. Only [inUse] channels are created;
 * the reserved ones fix their id for later phases.
 *
 * `POST_NOTIFICATIONS` is asked for in context at the FIRST TIMER, never at first start (plan 8.7).
 *
 * @property id the channel id (stable forever).
 * @property importance initial importance (the user can change it afterwards).
 * @property sound initial sound.
 * @property inUse created and posted to in the current release.
 */
enum class TandemNotificationChannel(val id: String, val importance: ChannelImportance, val sound: ChannelSound, val inUse: Boolean) {
    /** Timers (alarm): "timer done" with "+1 min" and "Stop", full screen on a locked screen (core:timer). */
    TIMER_ALARM("timer_alarm", ChannelImportance.HIGH, ChannelSound.ALARM, inUse = true),

    /** Running timers: the silent countdown, promoted to a Live Update on Android 16 (core:timer). */
    TIMER_ONGOING("timer_ongoing", ChannelImportance.LOW, ChannelSound.SILENT, inUse = true),

    /** Sync problems: blocked, logged out, safe mode (core:sync). */
    SYNC_PROBLEMS("sync_problems", ChannelImportance.LOW, ChannelSound.SILENT, inUse = true),

    /** "Tandoor was updated" (plan B18, since 0.1.0; core:sync `AndroidVersionChangeNotifier`). */
    SERVER("server", ChannelImportance.DEFAULT, ChannelSound.NOTIFICATION, inUse = true),

    /** Reserved (Phase 4): someone in the household changed the shopping list. */
    SHOPPING("shopping", ChannelImportance.DEFAULT, ChannelSound.NOTIFICATION, inUse = false),

    /** Reserved (Phase 3): an AI import finished. */
    IMPORT_DONE("import_done", ChannelImportance.DEFAULT, ChannelSound.NOTIFICATION, inUse = false),

    /** Reserved (later, optional): meal reminders ("take the chicken out of the freezer"). */
    MEAL_REMINDERS("meal_reminders", ChannelImportance.DEFAULT, ChannelSound.NOTIFICATION, inUse = false),
    ;

    companion object {
        /** The channel with [id], or null. */
        fun fromId(id: String): TandemNotificationChannel? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Notification ids, so modules never collide. Per-timer ids are derived from the timer's id
 * ([casa.moller.tandem.core.model.timer.CookTimer.id]) inside a range of 1000.
 */
object TandemNotificationIds {
    /** "Tandoor was updated" (core:sync, since 0.1.0). */
    const val SERVER_VERSION: Int = 1001

    /** The single sync-problem notification (core:sync). */
    const val SYNC_PROBLEM: Int = 1101

    /** The foreground service notification while an alarm rings (core:timer). */
    const val TIMER_RINGING_SERVICE: Int = 2000

    private const val TIMER_ONGOING_BASE = 3000
    private const val TIMER_ALARM_BASE = 4000
    private const val TIMER_RANGE = 1000L

    /** The silent countdown notification of timer [timerId]. */
    fun timerOngoing(timerId: Long): Int = TIMER_ONGOING_BASE + timerId.mod(TIMER_RANGE).toInt()

    /** The alarm notification of timer [timerId]. */
    fun timerAlarm(timerId: Long): Int = TIMER_ALARM_BASE + timerId.mod(TIMER_RANGE).toInt()
}
