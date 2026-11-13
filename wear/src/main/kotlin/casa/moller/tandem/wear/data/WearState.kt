package casa.moller.tandem.wear.data

import casa.moller.tandem.core.watchlink.MirroredTimer
import kotlinx.serialization.Serializable

/**
 * Everything the watch keeps (plan §7 #33, docs/BYGGE.md "Klockan"): one small JSON file in the
 * app's no-backup folder, never Room. The token is NOT here; it lives encrypted in
 * [CredentialsStore]. [account] only says which account and server the list belongs to.
 *
 * @property entries the list as the server sent it at [lastSyncMillis] (unchecked items and the
 *   ones checked within Tandoor's window).
 * @property pending the outbox: entry id → the checked state the user wants. The last tap wins,
 *   and two taps that cancel each other leave nothing to send. Sent as `bulk` calls.
 * @property supermarkets the stores with their aisle order; [supermarketId] is the one the list
 *   follows (null: the first store with an order, else the list's own order).
 * @property guard the protection against hurting the server ([WearGuard]).
 * @property signedOut the server refused the token (expired or revoked): no request goes out
 *   until the phone pairs the watch again.
 * @property timers the phone's timers as last mirrored ([timersReceivedMillis] = when).
 */
@Serializable
data class WearState(
    val account: WearAccount? = null,
    val signedOut: Boolean = false,
    val entries: List<WearEntry> = emptyList(),
    val pending: Map<Long, Boolean> = emptyMap(),
    val supermarkets: List<WearSupermarket> = emptyList(),
    val supermarketId: Long? = null,
    val supermarketsReadMillis: Long? = null,
    val lastSyncMillis: Long? = null,
    val guard: GuardState = GuardState(),
    val timers: List<MirroredTimer> = emptyList(),
    val timersReceivedMillis: Long? = null,
) {
    /** The entries with the outbox applied: what the user sees. */
    val visibleEntries: List<WearEntry>
        get() = if (pending.isEmpty()) entries else entries.map { entry -> pending[entry.id]?.let { entry.copy(checked = it) } ?: entry }

    /** The store whose aisle order the list follows. */
    val supermarket: WearSupermarket?
        get() = supermarkets.firstOrNull { it.id == supermarketId } ?: supermarkets.firstOrNull { it.categoryOrder.isNotEmpty() }
}

/** The paired account, without its token. */
@Serializable
data class WearAccount(
    val baseUrl: String,
    val tokenId: Long,
    val expiresEpochMillis: Long,
    val accountKey: String,
    val label: String = "",
    val serverVersion: String? = null,
)

/** One entry of the shopping list (Tandoor `ShoppingListEntry`), as much as the watch shows. */
@Serializable
data class WearEntry(
    val id: Long,
    val food: String,
    val foodPlural: String? = null,
    val foodId: Long? = null,
    val amount: Double = 0.0,
    val unit: String? = null,
    val unitPlural: String? = null,
    val unitId: Long? = null,
    val categoryId: Long? = null,
    val categoryName: String? = null,
    val checked: Boolean = false,
    val order: Int = 0,
    val recipeName: String? = null,
    /** "Köp senare": hidden from the list until then (Tandoor's `delay_until`). */
    val delayedUntilMillis: Long? = null,
)

/** A store and its aisles in walking order (`category_to_supermarket` sorted by `order`). */
@Serializable
data class WearSupermarket(val id: Long, val name: String, val categoryOrder: List<Long> = emptyList())

/**
 * What the watch learned from the last answers (plan §5.4, §4.1): never a burst of failing requests.
 *
 * @property pausedUntilMillis no request before this (wall clock), set after an error.
 * @property pauseMillis the current pause, doubled after every new error up to [WearGuard.MAX_PAUSE].
 * @property problem why the last attempt failed, for the screen; null after a success.
 */
@Serializable
data class GuardState(val pausedUntilMillis: Long = 0L, val pauseMillis: Long = 0L, val problem: WearProblem? = null)

/** Why the list may be old. */
@Serializable
enum class WearProblem {
    /** No network (or the server's name did not resolve): checks wait and are sent later. */
    OFFLINE,

    /** The server answered 429: wait as long as it asks. */
    THROTTLED,

    /** Something in front of the server refused (a firewall's 403), or the server failed. */
    SERVER,
}
