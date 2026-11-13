package casa.moller.tandem.core.model.watch

import casa.moller.tandem.core.model.error.TandemError
import kotlin.time.Instant

// Fas 5 (wear lane, plan §7 #33, §3.1): the phone's view of a Wear OS watch with Tandem.

/** Whether the phone can reach a watch with Tandem over the Wearable Data Layer. */
enum class WatchAvailability {
    /** The first lookup is running. */
    CHECKING,

    /**
     * The Data Layer cannot be used on this phone: no Google Play services, or no Wear OS
     * companion app (Galaxy Wearable or Pixel Watch). The watch is never offered then.
     */
    UNAVAILABLE,

    /** No Wear OS watch is connected. */
    NO_WATCH,

    /** A watch is connected, but Tandem is not installed on it (docs/WEAR.md). */
    NO_APP,

    /** A watch with Tandem is connected (nearby: Bluetooth or the same Wi-Fi). */
    READY,
}

/** Why this phone may not pair a watch at all. */
enum class WatchBlock {
    /** Nobody is logged in. */
    NO_ACCOUNT,

    /** Demo mode never talks to a server, so it has no token to share. */
    DEMO,

    /**
     * A guest may only read (plan §8.6): Tandoor refuses a guest's checks, so a watch would be a
     * list it cannot check off. Decided: not offered (Phase5WearTokenContractTest records what a
     * guest's token can do).
     */
    GUEST,
}

/**
 * A paired watch, as the phone remembers it. The watch token itself is never stored on the phone.
 *
 * @property watchName the watch's name as the Data Layer reports it ("Galaxy Watch6 (A1B2)").
 * @property accountId the phone's account the watch belongs to (one account per watch).
 * @property tokenExpires when the watch token expires; the phone renews it 30 days before.
 */
data class PairedWatch(
    val watchName: String,
    val accountId: String,
    val accountLabel: String,
    val tokenExpires: Instant,
    val pairedAt: Instant,
)

/**
 * Everything the "Klockan" page shows.
 *
 * @property watchName the connected watch with Tandem (or, for [WatchAvailability.NO_APP], without it).
 * @property paired the stored pairing, also while the watch is away.
 * @property pairedForAnotherAccount the pairing belongs to another account than the active one.
 * @property busy pairing or unpairing is running.
 */
data class WatchState(
    val availability: WatchAvailability = WatchAvailability.CHECKING,
    val watchName: String? = null,
    val paired: PairedWatch? = null,
    val block: WatchBlock? = null,
    val pairedForAnotherAccount: Boolean = false,
    val busy: Boolean = false,
) {
    /** Mer shows "Klockan" only when there is something to do there: a watch, or a pairing to undo. */
    val showInMore: Boolean
        get() = paired != null || availability == WatchAvailability.NO_APP || availability == WatchAvailability.READY

    /** "Koppla klockan" can be pressed now. */
    val canPair: Boolean
        get() = block == null && availability == WatchAvailability.READY && !busy
}

/** What pairing or unpairing did. */
enum class WatchOutcome {
    /** The watch has its own token now. */
    PAIRED,

    /** The token is deleted on the server and the watch forgot everything. */
    UNPAIRED,

    /**
     * The token is deleted on the server, but the watch could not be reached: it shows "log in
     * again" until it is paired again (its list stays on it until then).
     */
    UNPAIRED_WATCH_AWAY,

    /** No watch with Tandem is near the phone. */
    NO_WATCH,

    /** The watch did not take the token (its storage failed, or an older app). */
    WATCH_REFUSED,

    /** The server refused or could not be reached; see [WatchResult.error]. */
    SERVER,

    /** [WatchBlock]: not possible for this session. */
    NOT_ALLOWED,
}

/** The answer of a pairing action, with the server's error when [outcome] is [WatchOutcome.SERVER]. */
data class WatchResult(val outcome: WatchOutcome, val error: TandemError? = null)
