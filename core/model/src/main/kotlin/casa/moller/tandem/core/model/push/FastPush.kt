package casa.moller.tandem.core.model.push

import kotlin.time.Instant

// Fas 4 (push lane): the optional fast push for the shopping list (plan §4.3, B11, #30). A small
// companion service the household runs itself (push-service/, docs/PUSH.md) polls Tandoor and
// wakes the phones through UnifiedPush; the phone then syncs and shows its own list notification.
// Off by default and per account; guests and demo mode cannot use it.

/** Whether the active account may use fast push at all. */
enum class FastPushAvailability {
    /** A logged-in member: the settings can be used. */
    AVAILABLE,

    /** Demo mode: no network, nothing to push. */
    DEMO,

    /** A guest (read-only, #42): the service refuses guests too. */
    GUEST,

    /** Nobody is logged in. */
    NO_ACCOUNT,
}

/** Where the registration of the active account stands. */
enum class FastPushStatus {
    /** Not set up (the default). */
    OFF,

    /** Waiting for the distributor app to hand out an endpoint, then for the service. */
    REGISTERING,

    /** Registered: changes arrive as push. */
    ACTIVE,

    /** The last attempt failed ([FastPushState.error] says why); the background sync still works. */
    FAILED,
}

/** Why something about fast push did not work. Every value has its own explanation in the UI. */
enum class FastPushError {
    /** The address is not a valid `https://` URL. */
    INVALID_URL,

    /** The address answered, but not as a Tandem push service. */
    NOT_A_PUSH_SERVICE,

    /** The service could not be reached (offline, DNS, TLS, timeout). */
    UNREACHABLE,

    /** The service (asking Tandoor) did not accept the account's token. */
    UNAUTHORIZED,

    /** The service serves another household or space. */
    OTHER_HOUSEHOLD,

    /** Guests cannot register. */
    GUEST,

    /** The household already has as many phones as the service allows. */
    TOO_MANY,

    /** The service asked to wait (rate limit); try again in a minute. */
    RATE_LIMITED,

    /** The service or Tandoor behind it failed. */
    SERVICE_ERROR,

    /** No UnifiedPush distributor app is installed (or the chosen one was removed). */
    NO_DISTRIBUTOR,

    /** The distributor app refused or failed the registration. */
    DISTRIBUTOR_FAILED,

    /** The service does not offer a test notification. */
    TEST_UNSUPPORTED,
}

/** A UnifiedPush distributor app installed on the phone (e.g. ntfy). */
data class PushDistributor(val packageName: String, val label: String)

/** What `GET /v1/info` of a push service says about itself. */
data class FastPushServiceInfo(val version: String, val testSupported: Boolean, val pollSeconds: Int)

/**
 * The fast push settings of the active account.
 *
 * @property serviceUrl the push service's address as saved (empty when never set).
 * @property distributor the chosen distributor's package name, if any.
 * @property testSupported whether the registered service offers "Send test notification".
 * @property pollSeconds how often the service checks the list (from its registration answer).
 * @property lastWakeUp when the last wake-up arrived, if ever.
 * @property error why [status] is [FastPushStatus.FAILED].
 */
data class FastPushState(
    val availability: FastPushAvailability = FastPushAvailability.NO_ACCOUNT,
    val serviceUrl: String = "",
    val distributor: String? = null,
    val status: FastPushStatus = FastPushStatus.OFF,
    val testSupported: Boolean = false,
    val pollSeconds: Int? = null,
    val lastWakeUp: Instant? = null,
    val error: FastPushError? = null,
)

/** The result of a fast push action. */
sealed interface FastPushResult<out T> {
    data class Success<T>(val value: T) : FastPushResult<T>

    data class Failure(val error: FastPushError) : FastPushResult<Nothing>
}
