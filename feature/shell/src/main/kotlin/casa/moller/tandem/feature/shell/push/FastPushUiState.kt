package casa.moller.tandem.feature.shell.push

import androidx.annotation.StringRes
import casa.moller.tandem.core.model.push.FastPushAvailability
import casa.moller.tandem.core.model.push.FastPushError
import casa.moller.tandem.core.model.push.FastPushStatus
import casa.moller.tandem.core.model.push.PushDistributor
import kotlin.time.Instant

/**
 * Mer → Synk och lagring → Snabb push (Fas 4, push lane; plan §4.3, B11; docs/PUSH.md).
 *
 * @property url the address in the text field (the saved one until the user types).
 * @property urlInvalid the last attempt said the address is not a valid `https://` URL.
 * @property distributors the UnifiedPush distributor apps on the phone (empty: what to install).
 * @property selectedDistributor the chosen distributor's package name.
 * @property busy an action is running (the buttons wait).
 * @property listNotificationsOn "Notis när någon ändrar listan" (the notification a wake-up leads to).
 * @property notificationsAllowed whether the system lets the app post notifications.
 */
data class FastPushUiState(
    val loading: Boolean = true,
    val availability: FastPushAvailability = FastPushAvailability.NO_ACCOUNT,
    val url: String = "",
    val urlInvalid: Boolean = false,
    val distributors: List<PushDistributor> = emptyList(),
    val selectedDistributor: String? = null,
    val status: FastPushStatus = FastPushStatus.OFF,
    val error: FastPushError? = null,
    val testSupported: Boolean = false,
    val pollSeconds: Int? = null,
    val lastWakeUp: Instant? = null,
    val busy: Boolean = false,
    val listNotificationsOn: Boolean = false,
    val notificationsAllowed: Boolean = true,
) {
    /** Registered or on the way: the address and the distributor are locked until it is turned off. */
    val isOn: Boolean
        get() = status == FastPushStatus.ACTIVE || status == FastPushStatus.REGISTERING

    /** "Turn on" (or "Try again" after a failure) can be tapped. */
    val canEnable: Boolean
        get() = availability == FastPushAvailability.AVAILABLE && !busy && !isOn &&
            url.isNotBlank() && selectedDistributor != null

    /** "Send test notification" can be tapped. */
    val canTest: Boolean
        get() = status == FastPushStatus.ACTIVE && testSupported && !busy
}

/** What the screen can ask for. */
sealed interface FastPushAction {
    data class UrlChanged(val url: String) : FastPushAction

    data class SelectDistributor(val packageName: String) : FastPushAction

    /** Turn on (or try again). */
    data object Enable : FastPushAction

    /** Turn off. */
    data object Disable : FastPushAction

    data object SendTest : FastPushAction

    /** The screen came back (e.g. after installing ntfy): read the distributors again. */
    data object Refresh : FastPushAction

    /** The answer of the system's notification permission dialog. */
    data class NotificationPermissionResult(val granted: Boolean) : FastPushAction
}

/** One-off results. */
sealed interface FastPushEffect {
    data class Message(@StringRes val text: Int) : FastPushEffect

    /** Ask for POST_NOTIFICATIONS in context (Android 13+), right after turning push on. */
    data object RequestNotificationPermission : FastPushEffect
}
