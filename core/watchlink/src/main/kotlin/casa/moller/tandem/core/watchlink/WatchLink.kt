package casa.moller.tandem.core.watchlink

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The Wearable Data Layer contract between the phone app and the Wear OS app (Fas 5, wear lane;
 * plan §7 #33, §3.1, docs/BYGGE.md "Klockan"). Both apps have the same applicationId and signing,
 * which the Data Layer requires; it only ever connects apps of the same package and key.
 *
 * Everything is sent with `MessageClient` (a message or a request with a reply), never as a
 * `DataItem`: a DataItem is stored and can be synced through Google's servers, a message is
 * delivered to a connected node or not at all. The credentials are only sent to a node that is
 * nearby (Bluetooth or the same Wi-Fi), never to one reached through the cloud.
 */
object WatchLink {
    /** Version of the payloads below; a side that gets a newer one ignores what it does not know. */
    const val PROTOCOL: Int = 1

    /** The capability the watch app declares (`res/values/wear.xml`): "Tandem is installed here". */
    const val WATCH_CAPABILITY: String = "tandem_wear"

    /** The capability the phone app declares, so the watch finds the phone for timer actions. */
    const val PHONE_CAPABILITY: String = "tandem_phone"

    /** Every path starts here (the listener services filter on it). */
    const val PATH_PREFIX: String = "/tandem"

    /** Request phone → watch: [WatchCredentials]; the reply is a [PairReply]. */
    const val PATH_PAIR: String = "/tandem/wear/pair"

    /** Request phone → watch: [UnpairRequest]; the reply is a [PairReply] (the watch is wiped). */
    const val PATH_UNPAIR: String = "/tandem/wear/unpair"

    /** Request phone → watch: no body; the reply is a [WatchStatusReply]. */
    const val PATH_STATUS: String = "/tandem/wear/status"

    /** Message phone → watch: [TimerMirror], whenever the phone's timers change. */
    const val PATH_TIMERS: String = "/tandem/timers"

    /** Request watch → phone: no body; the reply is the phone's current [TimerMirror]. */
    const val PATH_TIMERS_REQUEST: String = "/tandem/timers/request"

    /** Message watch → phone: [TimerAction] ("Stoppa" on the watch stops the alarm on both). */
    const val PATH_TIMER_ACTION: String = "/tandem/timer/action"
}

/**
 * The watch's own token (plan §3.1 "Andra klienter får egna, smalare tokens"): scope `read write`
 * (checking items needs `write`), a shorter life than the phone's one year, renewed by the phone
 * while the watch is paired, and never the phone's token.
 */
object WatchTokenPolicy {
    /** What `POST /api/access-token/` gets as `scope`. */
    const val SCOPE: String = "read write"

    /** How long a watch token lives. Short enough that a lost watch stops working on its own. */
    val LIFETIME: Duration = 90.days

    /** The phone renews the token when it expires within this (and the watch is reachable). */
    val RENEW_BEFORE: Duration = 30.days

    /** The `expires` of a token created at [now]. */
    fun expiry(now: Instant): Instant = now + LIFETIME

    /** True when a token that expires at [expires] should be replaced at [now]. Unknown = renew. */
    fun needsRenewal(expires: Instant?, now: Instant): Boolean = expires == null || expires - now <= RENEW_BEFORE

    /** True when the token is past its expiry at [now] (the server refuses it from then on). */
    fun isExpired(expires: Instant?, now: Instant): Boolean = expires != null && expires <= now
}
