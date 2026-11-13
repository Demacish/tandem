package casa.moller.tandem.core.watchlink

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * [WatchLink.PATH_PAIR]: what the watch needs to talk to Tandoor on its own (plan §7 #33): the
 * server, the watch's OWN token (never the phone's) and which of the phone's accounts it belongs to.
 *
 * @property baseUrl the server's normalized `https://` base URL with a trailing slash.
 * @property token the watch token in plaintext (`tda_…`); only ever in this message and in the
 *   watch's encrypted store, never logged ([toString] redacts it).
 * @property tokenId its id on the server, so the phone (or the watch itself) can delete it.
 * @property expiresEpochMillis when the server stops accepting it.
 * @property accountKey the phone's local id of the account (a random UUID, never sent to a server):
 *   a new pairing for another account empties the watch's list first.
 * @property accountLabel the account's name as the phone shows it ("Anna"), for the watch's footer.
 */
@Serializable
data class WatchCredentials(
    val protocol: Int = WatchLink.PROTOCOL,
    val baseUrl: String,
    val token: String,
    val tokenId: Long,
    val expiresEpochMillis: Long,
    val accountKey: String,
    val accountLabel: String = "",
    val serverVersion: String? = null,
) {
    override fun toString(): String =
        "WatchCredentials(baseUrl=$baseUrl, token=<redacted>, tokenId=$tokenId, expires=$expiresEpochMillis, accountKey=$accountKey)"
}

/** The watch's answer to [WatchLink.PATH_PAIR] and [WatchLink.PATH_UNPAIR]. */
@Serializable
data class PairReply(val ok: Boolean, val error: PairError? = null)

/** Why the watch refused a pairing. */
@Serializable
enum class PairError {
    /** The payload could not be read (another protocol, a broken message). */
    BAD_PAYLOAD,

    /** The watch could not store the token encrypted (Keystore unavailable). */
    STORAGE,
}

/**
 * [WatchLink.PATH_UNPAIR]: forget everything. With [revoke] the watch first deletes its own token on
 * the server (a token may delete itself, FACTS c), used when the phone no longer has a token that
 * could (the paired account was logged out on the phone).
 */
@Serializable
data class UnpairRequest(val tokenId: Long? = null, val revoke: Boolean = false)

/** The watch's answer to [WatchLink.PATH_STATUS], for the phone's "Klockan" page. */
@Serializable
data class WatchStatusReply(
    val paired: Boolean,
    val tokenId: Long? = null,
    val accountKey: String? = null,
    /** The server refused the token (expired or revoked): the watch asks to pair again. */
    val signedOut: Boolean = false,
    /** Checks waiting to be sent. */
    val pending: Int = 0,
    val lastSyncEpochMillis: Long? = null,
)

/** [WatchLink.PATH_TIMERS]: the phone's cook timers as they are now (plan §8.1, §7 #33). */
@Serializable
data class TimerMirror(
    val protocol: Int = WatchLink.PROTOCOL,
    val sentAtEpochMillis: Long,
    val timers: List<MirroredTimer> = emptyList(),
)

/**
 * One phone timer on the watch. Times are wall-clock epoch milliseconds like `CookTimer.endsAt`;
 * the watch turns them into its own monotonic time for the countdown.
 */
@Serializable
data class MirroredTimer(
    val id: Long,
    val label: String,
    val recipeName: String? = null,
    val state: MirroredTimerState,
    /** RUNNING: when it rings; RINGING/FINISHED: when it rang; PAUSED: null. */
    val endsAtEpochMillis: Long? = null,
    /** PAUSED: what is left. */
    val remainingMillis: Long? = null,
    val durationMillis: Long,
)

/** `TimerState` of the phone, as the watch knows it. */
@Serializable
enum class MirroredTimerState {
    RUNNING,
    PAUSED,
    RINGING,
    FINISHED,
}

/** [WatchLink.PATH_TIMER_ACTION]: the watch asks the phone to act on timer [id]. */
@Serializable
data class TimerAction(val id: Long, val kind: TimerActionKind)

/** What the watch can do with a phone timer. */
@Serializable
enum class TimerActionKind {
    /** Silence a ringing timer (`TimerController.stopRinging`): the alarm stops on both. */
    STOP_RINGING,

    /** Remove the timer (`TimerController.cancel`). */
    CANCEL,
}

/** JSON in UTF-8 for every payload; unknown keys are ignored, so a newer side can add fields. */
object WatchCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray = json.encodeToString(serializer, value).encodeToByteArray()

    /** The payload, or null when [bytes] is not one (never throws). */
    fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray?): T? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            json.decodeFromString(serializer, bytes.decodeToString())
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
