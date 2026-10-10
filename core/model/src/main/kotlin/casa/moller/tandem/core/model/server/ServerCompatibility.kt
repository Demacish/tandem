package casa.moller.tandem.core.model.server

/**
 * How the app treats a server version (plan 3.6). Moved from core:data (Phase 0) to core:model in
 * Phase 1 so that pure logic and every layer can use it.
 *
 * Safe mode (plan 3.6 point 4): for an untested version inside the supported range the app shows
 * a non-blocking banner, reading works as usual, the outbox is PAUSED and new writes are queued
 * locally with a clear marking; the user may choose "Send anyway" per queue ([WritePolicy]).
 */
enum class ServerCompatibility {
    /** The tested line (2.6.x): everything enabled. */
    SUPPORTED,

    /** Newer than the tested line. Login works; the app runs in safe mode (outbox paused). */
    UNTESTED_NEWER,

    /**
     * 2.0.0 to 2.5.x: older than the tested line. Login works; the app runs in safe mode, like
     * [UNTESTED_NEWER] (plan 4.5: any Tandoor >= 2.0, with safe mode for untested versions).
     */
    UNTESTED_OLDER,

    /** Older than 2.0.0 (Tandoor 1.x). Login is refused; writes are disabled entirely. */
    TOO_OLD,

    /** The version string could not be parsed (e.g. a development build); treated like [UNTESTED_NEWER]. */
    UNRECOGNIZED,
    ;

    /** False only for [TOO_OLD]: the app refuses to log in. */
    val canLogIn: Boolean
        get() = this != TOO_OLD

    /** True when the user must confirm before continuing, and the app runs in safe mode. */
    val isSafeMode: Boolean
        get() = this == UNTESTED_NEWER || this == UNTESTED_OLDER || this == UNRECOGNIZED

    /** What happens to changes made in the app (outbox: cook log, view log in Phase 1). */
    val writePolicy: WritePolicy
        get() = when (this) {
            SUPPORTED -> WritePolicy.SEND
            UNTESTED_NEWER, UNTESTED_OLDER, UNRECOGNIZED -> WritePolicy.QUEUE_PAUSED
            TOO_OLD -> WritePolicy.DISABLED
        }
}

/** What the outbox does with local changes for a server version (plan 3.6, 5.4). */
enum class WritePolicy {
    /** Changes are sent as usual. */
    SEND,

    /**
     * Safe mode: changes are stored and queued but not sent until the user chooses "Send anyway"
     * (per queue) or a tested app version arrives. Nothing is ever discarded.
     */
    QUEUE_PAUSED,

    /** Outside the supported range: the app offers no writes at all. */
    DISABLED,
}
