package casa.moller.tandem.core.data.server

/**
 * A server that answered the probe like Tandoor (plan 4.2 step 2). Input for the login calls of
 * [casa.moller.tandem.core.data.account.AccountRepository].
 *
 * Holds only plain values, so a ViewModel can keep it in a `SavedStateHandle` and survive
 * process death without probing again.
 *
 * @property baseUrl normalized `https://` base URL with trailing slash, e.g.
 *   `https://example.com/tandoor/`. It can differ from what the user typed: the probe adds
 *   `https://`, cuts pasted deep links and follows HTTPS redirects of the settings endpoint.
 * @property version `version` exactly as the server reported it, e.g. `2.6.13`.
 */
data class ServerInfo(val baseUrl: String, val version: String, val compatibility: ServerCompatibility) {
    /** The address without scheme and trailing slash, for display: `example.com/tandoor`. */
    val displayAddress: String
        get() = displayAddress(baseUrl)

    companion object {
        /** `https://example.com/tandoor/` -> `example.com/tandoor`. */
        fun displayAddress(baseUrl: String): String = baseUrl.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
    }
}

/** How the app treats a server version (plan 3.6). */
enum class ServerCompatibility {
    /** The tested line (2.6.x): everything enabled. */
    SUPPORTED,

    /** Newer than the tested line. Login works; later phases run it in safe mode (outbox paused). */
    UNTESTED_NEWER,

    /**
     * 2.0.0 to 2.5.x: older than the tested line. Login works; the app runs in safe mode, like
     * [UNTESTED_NEWER] (plan 4.5: any Tandoor >= 2.0, with safe mode for untested versions).
     */
    UNTESTED_OLDER,

    /** Older than 2.0.0 (Tandoor 1.x). Login is refused. */
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
}
