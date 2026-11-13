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

    /** Fas 4: someone in the household changed the shopping list (core:sync `AndroidShoppingChangeNotifier`). */
    SHOPPING("shopping", ChannelImportance.DEFAULT, ChannelSound.NOTIFICATION, inUse = true),

    /** An AI import ended while the app was away from the screen (core:sync `AndroidImportDoneNotifier`). */
    IMPORT_DONE("import_done", ChannelImportance.DEFAULT, ChannelSound.NOTIFICATION, inUse = true),

    /**
     * Meal reminders, optional (plan 8.7 "Måltidspåminnelser"). Fas 4 (skafferinotis lane): "Grädde går
     * ut i morgon – 3 recept" (core:sync `AndroidPantryExpiryNotifier`); later also "take the chicken
     * out of the freezer".
     */
    MEAL_REMINDERS("meal_reminders", ChannelImportance.DEFAULT, ChannelSound.NOTIFICATION, inUse = true),
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

    /** The single "the shopping list changed" notification (Fas 4, core:sync); a new batch replaces it. */
    const val SHOPPING_CHANGES: Int = 1201

    /** Fas 4 (skafferinotis lane): the single "Grädde går ut i morgon" notification (core:sync); a new day's replaces it. */
    const val PANTRY_EXPIRY: Int = 1301

    /**
     * Fas 5 (planera lane): the evening reminders "Tina ut kycklingen" (core:sync), one per meal: posted
     * with this tag and [mealReminder] of the reminder's key, so they never collide with the ids above.
     */
    const val MEAL_REMINDER_TAG: String = "tandem.meal_reminder"

    /** The id of the evening reminder with [key] (under [MEAL_REMINDER_TAG]). */
    fun mealReminder(key: String): Int = key.hashCode()

    /** The single "the import is done" notification (core:sync); the next import's replaces it. */
    const val IMPORT_DONE: Int = 1401

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
