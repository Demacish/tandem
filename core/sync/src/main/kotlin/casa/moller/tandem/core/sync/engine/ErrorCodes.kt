package casa.moller.tandem.core.sync.engine

import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.sync.guard.SyncGuard

/**
 * A short, stable, non-sensitive description of an error for `outbox.last_error` and the
 * "Export pending changes" JSON (class names are renamed by R8, messages may carry user data).
 */
internal fun TandemError.code(): String = when (this) {
    TandemError.NetworkUnavailable -> "network-unavailable"

    TandemError.UnknownHost -> "unknown-host"

    TandemError.Timeout -> "timeout"

    is TandemError.TlsError -> "tls"

    is TandemError.InvalidUrl -> "invalid-url"

    is TandemError.NotTandoor -> "not-tandoor" + (httpStatus?.let { "-$it" } ?: "")

    is TandemError.Unauthorized -> "unauthorized"

    TandemError.Blocked -> "blocked"

    TandemError.NotFound -> "http-404"

    is TandemError.BadRequest -> "http-400"

    is TandemError.Throttled -> "http-429"

    is TandemError.ServerError -> "http-$code"

    is TandemError.Decode -> "decode"

    // Phase 2 (koppling lane): the circuit breaker and an import hold rows back; "blockerad"-diagnostik names them.
    is TandemError.Unknown -> when (this) {
        SyncGuard.BREAKER_ERROR -> CIRCUIT_BREAKER_CODE
        SyncGuard.IMPORT_ERROR -> "import-running"
        else -> "unknown"
    }
}

/** `outbox.last_error` of a row the circuit breaker held back. */
internal const val CIRCUIT_BREAKER_CODE: String = "circuit-breaker"
