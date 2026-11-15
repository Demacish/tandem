package casa.moller.tandem.core.model.settings

/**
 * Fas 5 (las lane, plan §5.5 "Biometrisk lås valfritt"): Mer → Lås appen. The app's content is
 * hidden behind a lock screen until the phone's own screen lock (a strong biometric or the PIN,
 * pattern or password) is confirmed. Off until the user turns it on.
 *
 * @property enabled the lock is on.
 * @property timeout how long the app may be in the background before it locks again ("Lås efter").
 *   A cold start always locks.
 */
data class AppLockSettings(val enabled: Boolean = false, val timeout: AppLockTimeout = AppLockTimeout.DEFAULT)

/**
 * "Lås efter": how long the app may be in the background (no screen of it visible, the screen off
 * included) before it asks for the screen lock again. The time only runs in the background: an app
 * in front (cook mode, the kitchen display) never locks.
 *
 * @property millis the time in the background, in milliseconds.
 */
enum class AppLockTimeout(val millis: Long) {
    /** "Direkt": every time the app has been in the background. */
    IMMEDIATELY(0L),
    ONE_MINUTE(60_000L),
    FIVE_MINUTES(300_000L),
    FIFTEEN_MINUTES(900_000L),
    ;

    companion object {
        /** One minute until chosen. */
        val DEFAULT: AppLockTimeout = ONE_MINUTE
    }
}

/**
 * Where the app lock stands right now (core:data `AppLock.status`).
 */
enum class AppLockStatus {
    /** The setting has not been read yet at this process start: show nothing yet. */
    UNKNOWN,

    /** The lock is off. */
    OFF,

    /** The lock is on and the user has unlocked the app within the timeout. */
    UNLOCKED,

    /** The lock is on and the content must stay hidden until the user unlocks. */
    LOCKED,
    ;

    /** The app's content may be shown (and App Functions may read or write). */
    val allowsContent: Boolean
        get() = this == OFF || this == UNLOCKED
}
