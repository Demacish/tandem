package casa.moller.tandem.core.model.error

/**
 * True when this error means the server could not be reached from here (no network, unknown host,
 * timeout): the app is "offline" and shows what it stored last (plan 3.5, 6.2). Any answer from
 * the server, including a firewall's 403, is not offline.
 */
val TandemError.isConnectivityProblem: Boolean
    get() = this == TandemError.NetworkUnavailable || this == TandemError.UnknownHost || this == TandemError.Timeout
