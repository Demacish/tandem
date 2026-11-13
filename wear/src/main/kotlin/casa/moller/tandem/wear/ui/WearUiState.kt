package casa.moller.tandem.wear.ui

import casa.moller.tandem.core.watchlink.MirroredTimer
import casa.moller.tandem.wear.data.WearProblem
import casa.moller.tandem.wear.shopping.WearList
import casa.moller.tandem.wear.shopping.WearRow

/** What the watch's one screen is about. */
enum class WearMode {
    /** The stored state is being read. */
    LOADING,

    /** No token yet: pair from the phone (Mer → Klockan). */
    NOT_PAIRED,

    /** The server refused the token: log in again on the phone (pair again). */
    SIGNED_OUT,

    /** Paired; the list may still be empty because the first sync has not finished. */
    READY,
}

/**
 * The watch's screen (stateless [ShoppingScreen]): the phone's timers on top, then the list.
 *
 * @property nowMillis the wall clock for the timers' countdown (ticks every second while one runs).
 * @property pending checks waiting in the outbox.
 * @property problem why the list may be old; [retryAtMillis] when the next try is (a pause).
 * @property firstSyncDone the list was read at least once since pairing.
 * @property storeName the store whose aisle order the list follows; [storeCount] > 1 lets the user switch.
 * @property askNotifications timers are shown but the watch may not post their notifications yet.
 */
data class WearUiState(
    val mode: WearMode = WearMode.LOADING,
    val list: WearList = WearList.EMPTY,
    val timers: List<MirroredTimer> = emptyList(),
    val nowMillis: Long = 0L,
    val pending: Int = 0,
    val problem: WearProblem? = null,
    val retryAtMillis: Long? = null,
    val lastSyncMillis: Long? = null,
    val firstSyncDone: Boolean = false,
    val storeName: String? = null,
    val storeCount: Int = 0,
    val accountLabel: String = "",
    val askNotifications: Boolean = false,
)

/** What the user does on the watch. */
sealed interface WearAction {
    /** Tap on a row: check it (or uncheck a checked one); all its entries. */
    data class Toggle(val row: WearRow) : WearAction

    /** "Uppdatera": read the list now (when the guard allows it). */
    data object Refresh : WearAction

    /** Follow the next store's aisle order. */
    data object NextStore : WearAction

    /** "Stoppa" on a ringing timer: silences it on the phone too. */
    data class StopTimer(val id: Long) : WearAction

    /** The answer of the system's notification dialog (or a change in the settings). */
    data object NotificationsChanged : WearAction
}
