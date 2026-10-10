package casa.moller.tandem.core.model.error

import kotlin.time.Duration

/**
 * Every failure the app can show the user, independent of HTTP, OkHttp or serialization types
 * (plan 5.4). `core:network` maps exceptions and responses to these with its `ErrorClassifier`,
 * following the contract facts observed on Tandoor 2.6.13
 * (`api-schema/fixtures/2.6.13/FACTS.md`).
 *
 * The UI picks a localized message per type; the optional technical fields are only meant for
 * "Show details" and logs. None of them ever contains a token or a password.
 */
sealed interface TandemError {

    /** No connection could be made (connection refused or reset, no route, network down). */
    data object NetworkUnavailable : TandemError

    /** The host name could not be resolved: the device is offline or the address is mistyped. */
    data object UnknownHost : TandemError

    /** Connect, read or whole-call timeout. */
    data object Timeout : TandemError

    /**
     * The TLS handshake failed. For [TlsErrorKind.UNTRUSTED_CERTIFICATE] onboarding explains how
     * to install a user CA (plan 5.5: the app trusts user CAs, no pinning).
     */
    data class TlsError(val kind: TlsErrorKind) : TandemError

    /** The address the user entered cannot be used. Nothing was sent. */
    data class InvalidUrl(val reason: InvalidUrlReason) : TandemError

    /**
     * The server answered, but not like a Tandoor API: an HTML page, a redirect (Tandoor answers
     * unknown `/api/` paths with a 302 to `/accounts/login/`), non-JSON or JSON without `version`.
     *
     * @property httpStatus the status code of the offending response, if there was one.
     */
    data class NotTandoor(val reason: NotTandoorReason, val httpStatus: Int? = null) : TandemError

    /**
     * The token is missing, invalid, expired or revoked. Tandoor 2.6.13 answers all of these with
     * 403 and a JSON body, never 401; a 401 comes from something in front of Tandoor.
     * Stops the sync queue and asks for a new login, but keeps the outbox (plan 4.1, 4.2).
     */
    data class Unauthorized(val source: UnauthorizedSource) : TandemError

    /**
     * 403 without a JSON body: a firewall, CrowdSec or the reverse proxy blocked the request
     * (plan 4.1). Not a login problem; the app pauses and tries again later.
     */
    data object Blocked : TandemError

    /** 404 with a JSON body: the object does not exist (any more) on the server. */
    data object NotFound : TandemError

    /**
     * 400 with Django REST framework field errors, e.g. `{"username": ["This field is required."]}`
     * or `{"non_field_errors": ["Unable to log in with provided credentials."]}`.
     * The messages are localized by the server; never match on their text.
     */
    data class BadRequest(val fieldErrors: Map<String, List<String>> = emptyMap()) : TandemError {
        /** Errors that belong to no single field; on login this means wrong username or password. */
        val nonFieldErrors: List<String>
            get() = fieldErrors[NON_FIELD_ERRORS].orEmpty()

        companion object {
            const val NON_FIELD_ERRORS: String = "non_field_errors"
        }
    }

    /**
     * 429. [retryAfter] comes from the `Retry-After` header when present. Never retried
     * automatically: the login endpoint allows 10 calls per day and IP, failures included.
     */
    data class Throttled(val retryAfter: Duration?) : TandemError

    /** 5xx, including 502/503/504 from the reverse proxy. */
    data class ServerError(val code: Int) : TandemError

    /** A 2xx response whose body could not be read into the expected shape. */
    data class Decode(val detail: String? = null) : TandemError

    /** Anything else, e.g. an unexpected 4xx status. */
    data class Unknown(val detail: String? = null) : TandemError
}

enum class TlsErrorKind {
    /** The certificate chain is not trusted (self-signed or a private CA that is not installed). */
    UNTRUSTED_CERTIFICATE,

    /** The certificate is valid but issued for another host name. */
    HOSTNAME_MISMATCH,

    /** Any other handshake failure (protocol or cipher mismatch, broken TLS proxy). */
    HANDSHAKE_FAILED,
}

enum class InvalidUrlReason {
    EMPTY,
    MALFORMED,

    /** `http://` was entered or a redirect pointed to it. Tandem only talks HTTPS (plan 5.5). */
    CLEARTEXT_NOT_ALLOWED,

    /** A scheme other than https, e.g. `ftp://`. */
    UNSUPPORTED_SCHEME,
}

enum class NotTandoorReason {
    /** A 2xx HTML page, e.g. a login portal or another web app. */
    HTML_RESPONSE,

    /** A body that is not a JSON object where Tandoor returns one. */
    NOT_JSON,

    /** `server-settings/current` answered JSON without a `version`. */
    MISSING_VERSION,

    /** A redirect to `/accounts/login/`: Tandoor does not know the path (wrong base path or version). */
    LOGIN_REDIRECT,

    /** Any other redirect on an API call (API calls never follow redirects). */
    REDIRECT,

    /** The probe gave up after its request budget while following redirects. */
    TOO_MANY_REDIRECTS,

    /** A status the endpoint never returns on Tandoor, e.g. an HTML 404 from another server. */
    UNEXPECTED_STATUS,
}

enum class UnauthorizedSource {
    /** 403 with a JSON body from Tandoor itself. */
    TANDOOR,

    /** 401: from a proxy or SSO in front of Tandoor (Tandoor itself never answers 401). */
    PROXY,
}

/** Carries a [TandemError] through code that can only throw, e.g. a `Flow` or a Worker. */
class TandemException(val error: TandemError) : Exception(error.toString())
