package casa.moller.tandem.core.network.auth

import casa.moller.tandem.core.model.error.NotTandoorReason
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.core.model.error.getOrNull
import casa.moller.tandem.core.network.api.TandoorApi
import casa.moller.tandem.core.network.api.TandoorApiFactory
import casa.moller.tandem.core.network.dto.AccessTokenCreateRequestDto
import casa.moller.tandem.core.network.dto.AccessTokenDto
import casa.moller.tandem.core.network.dto.AllauthLoginRequestDto
import casa.moller.tandem.core.network.dto.TokenAuthRequestDto
import casa.moller.tandem.core.network.dto.UserDto
import casa.moller.tandem.core.network.error.ErrorClassifier
import casa.moller.tandem.core.network.error.apiCall
import casa.moller.tandem.core.network.http.MutableTokenProvider
import casa.moller.tandem.core.network.http.TandemIOException
import casa.moller.tandem.core.network.json.TandoorDates
import casa.moller.tandem.core.network.probe.TandoorServer
import casa.moller.tandem.core.network.probe.TandoorVersion
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/**
 * Login per plan 3.1 / 4.2, adjusted to the contract facts of Tandoor 2.6.13:
 *
 * 1. `POST /api-token-auth/` with username and password. Never retried, not even by OkHttp
 *    (10 calls per day and IP, failures included); 400 and 429 are final.
 * 2. `POST /api/access-token/` with that token: this device's own token, scope `read write`,
 *    expiring in one year. Token and id are read from the same response (plaintext for 15 s only).
 * 3. Only the device token is returned; the login token is forgotten.
 * 4. The login token is deleted only with [LoginTokenPolicy.DELETE_IF_FRESH] and only if it was
 *    created by this login (`expires` about 5 years ahead). Default is [LoginTokenPolicy.KEEP].
 *
 * **Tandoor 2.7+** removed `api-token-auth` (it redirects to the web login). There, step 1 is
 * allauth headless' app login (`POST /_allauth/app/v1/auth/login`, 3 per minute and IP): its
 * `meta.session_token` creates the device token (`X-Session-Token`), and the session is logged
 * out right after (best effort), before the device token is used. Which login a server gets
 * follows its version ([usesAllauth]); a server whose `api-token-auth` turns out to be gone (a
 * redirect, 404 or 405, e.g. a development build without a parseable version) is tried once
 * more with allauth. When neither
 * works (allauth wants a step the app cannot do, such as e-mail verification or a second factor,
 * or the server has no app login) the failure is [PASSWORD_LOGIN_UNAVAILABLE]: the user pastes
 * a token instead.
 *
 * Then the space (`GET /api/space/current/`) and the user (`GET /api/user-preference/`) are read
 * with the device token. If the space read fails, the new device token is revoked again (best
 * effort).
 *
 * An account without an active space (a user an admin created who never opened the web UI) can
 * log in and create tokens, but Tandoor refuses the space read (and `GET /api/user/{id}/`) with
 * 403 + JSON, and `user-preference` even answers 500 (FACTS h). Right after the token was created,
 * that 403 cannot mean an invalid token, so it is not "log in again": the result has
 * `spaceId = null`, and the data layer reports "no space" (and revokes the token it created).
 */
class TandoorAuthenticator internal constructor(
    private val apiFactory: TandoorApiFactory,
    private val clock: Clock,
    private val classifier: ErrorClassifier,
) {
    @Inject
    constructor(apiFactory: TandoorApiFactory) : this(apiFactory, Clock.systemUTC(), ErrorClassifier.Default)

    /**
     * @param password used for this one request only; never stored or logged.
     */
    suspend fun login(
        server: TandoorServer,
        username: String,
        password: String,
        loginTokenPolicy: LoginTokenPolicy = LoginTokenPolicy.KEEP,
    ): Outcome<DeviceCredentials> {
        val tokens = MutableTokenProvider()
        val api = apiFactory.create(server.baseUrl, tokens, retryOnConnectionFailure = false)
        val now = clock.instant()
        val requestedExpiry = now.atOffset(ZoneOffset.UTC).plusYears(1).toInstant()
        val request = AccessTokenCreateRequestDto(scope = DEVICE_TOKEN_SCOPE, expires = TandoorDates.format(requestedExpiry))

        val allauth = AllauthAttempt(api, tokens, server, username, password, request, requestedExpiry)
        if (usesAllauth(server.version)) return allauthLogin(allauth)

        // Step 1: the (possibly shared) login token.
        val login = when (val r = call { api.auth.login(TokenAuthRequestDto(username, password)) }) {
            is Outcome.Failure ->
                return if (r.error.meansTokenLoginIsGone()) {
                    allauthLogin(allauth)
                } else {
                    r
                }

            is Outcome.Success -> r.value
        }
        val loginToken = login.token.trim()
        val userId = login.userId ?: login.test
        if (loginToken.isEmpty() || userId == null) return decodeError("api-token-auth: token or user_id missing")

        // Step 2: this device's own token, created with the login token.
        tokens.token = loginToken
        val device = when (val r = call { api.auth.createAccessToken(request) }) {
            is Outcome.Failure -> return r
            is Outcome.Success -> newDeviceToken(r.value, requestedExpiry) ?: return decodeError(DEVICE_TOKEN_MISSING)
        }

        // Step 3: from here on only the device token is used.
        tokens.token = device.token

        // Step 4: optionally delete the login token if this login created it.
        val loginTokenId = login.id
        if (loginTokenPolicy == LoginTokenPolicy.DELETE_IF_FRESH &&
            loginTokenId != null && loginTokenId != device.id &&
            isFreshLoginToken(login.expires, now)
        ) {
            // Failure is harmless: the token simply stays, as with KEEP.
            call { api.auth.deleteAccessToken(loginTokenId) }
        }
        return readAccount(api, server, username, userId, device)
    }

    /** What the allauth login needs; the password lives only as long as the login call. */
    private class AllauthAttempt(
        val api: TandoorApi,
        val tokens: MutableTokenProvider,
        val server: TandoorServer,
        val username: String,
        val password: String,
        val request: AccessTokenCreateRequestDto,
        val requestedExpiry: Instant,
    ) {
        override fun toString(): String = "AllauthAttempt(username=$username, password=<redacted>)"
    }

    /** A device token as created: plaintext token, id and expiry. */
    private class NewDeviceToken(val token: String, val id: Long, val expires: Instant) {
        override fun toString(): String = "NewDeviceToken(id=$id, token=<redacted>, expires=$expires)"
    }

    /** The plaintext token and id of a create response (both are needed), else null. */
    private fun newDeviceToken(device: AccessTokenDto, requestedExpiry: Instant): NewDeviceToken? {
        val token = device.token.trim()
        val id = device.id
        if (token.isEmpty() || isMasked(token) || id == null) return null
        return NewDeviceToken(token, id, TandoorDates.parseInstantOrNull(device.expires) ?: requestedExpiry)
    }

    /**
     * Tandoor 2.7+: allauth's app login, the device token created with its session, then the
     * session logged out. A 403 + JSON on the token create right after an accepted password is
     * Tandoor refusing an account without a space (FACTS 2.7 "noSpace"): the result then has
     * `spaceId = null` and no token, and the data layer reports "no space".
     */
    private suspend fun allauthLogin(attempt: AllauthAttempt): Outcome<DeviceCredentials> {
        val api = attempt.api
        val credentials = AllauthLoginRequestDto(attempt.username, attempt.password)
        val response = when (val r = call { allauthCall { api.allauth.login(credentials) } }) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val session = response.meta?.sessionToken?.trim()
        if (session.isNullOrEmpty() || response.meta?.isAuthenticated == false) {
            return decodeError("allauth login: session_token missing")
        }
        val userId = response.data?.user?.id
        val created = try {
            if (userId == null) null else call { api.auth.createAccessTokenWithSession(session, attempt.request) }
        } finally {
            // The session is only needed for the token: end it right away, also after a failure
            // (best effort; an unended session expires on the server). allauth answers 401.
            withContext(NonCancellable) { call { api.allauth.logout(session) } }
        }
        if (userId == null || created == null) return decodeError("allauth login: user.id missing")
        val device = when (created) {
            is Outcome.Success -> newDeviceToken(created.value, attempt.requestedExpiry) ?: return decodeError(DEVICE_TOKEN_MISSING)

            is Outcome.Failure ->
                return if (created.error.isRefusedByTandoor()) {
                    Outcome.Success(noSpaceCredentials(api, attempt.server, userId, attempt.username))
                } else {
                    created
                }
        }
        // From here on only the device token is used.
        attempt.tokens.token = device.token
        return readAccount(api, attempt.server, attempt.username, userId, device)
    }

    /** An accepted password of an account without a space: nothing was created, nothing to revoke. */
    private fun noSpaceCredentials(api: TandoorApi, server: TandoorServer, userId: Long, username: String) = DeviceCredentials(
        baseUrl = api.baseUrl,
        token = "",
        tokenId = null,
        expires = null,
        userId = userId,
        username = username,
        displayName = username,
        spaceId = null,
        spaceName = null,
        serverVersion = server.version,
    )

    /**
     * allauth's own refusals become typed errors: 401 means the login needs another step (a
     * pending flow such as e-mail verification or a second factor), and 404, 405 or a redirect
     * means the server has no app login. Both are [PASSWORD_LOGIN_UNAVAILABLE]. 400 (wrong or
     * missing credentials) and 429 (3 per minute) are classified as usual.
     */
    private suspend fun <T> allauthCall(block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        val code = e.code()
        if (code in NO_APP_LOGIN_STATUSES || code in HTTP_REDIRECTS) throw TandemIOException(PASSWORD_LOGIN_UNAVAILABLE)
        throw e
    }

    /** The space and the user, read with the new device token (both login paths). */
    private suspend fun readAccount(
        api: TandoorApi,
        server: TandoorServer,
        username: String,
        userId: Long,
        device: NewDeviceToken,
    ): Outcome<DeviceCredentials> {
        // The token response already says who logged in; the user read only adds the display name.
        val loginUser = UserDto(id = userId, username = username)
        val space = when (val r = call { api.space.currentSpace() }) {
            is Outcome.Success -> r.value

            is Outcome.Failure ->
                if (r.error.isRefusedByTandoor()) {
                    // The device token was created a moment ago, so it is valid: Tandoor refuses the
                    // read because the account has no space (or no group) yet.
                    null
                } else {
                    call { api.auth.deleteAccessToken(device.id) }
                    return r
                }
        }
        // Without a space user-preference answers 500 (FACTS h), so it is not even asked then.
        // With a space, a failed user read only costs the display name.
        val user = if (space == null) loginUser else readUser(api).getOrNull() ?: loginUser
        return Outcome.Success(
            DeviceCredentials(
                baseUrl = api.baseUrl,
                token = device.token,
                tokenId = device.id,
                expires = device.expires,
                userId = user.id ?: userId,
                username = user.username.ifBlank { username },
                displayName = user.displayName.ifBlank { user.username.ifBlank { username } },
                spaceId = space?.id,
                spaceName = space?.name?.ifBlank { null },
                serverVersion = server.version,
            ),
        )
    }

    /**
     * Fallback login with a token the user created in Tandoor and pasted (plan 4.2 point 2).
     * The token is validated by reading the space and the current user; its id and expiry are
     * matched in `GET /api/access-token/` (exact plaintext, else the last 4 characters of the
     * masked list). An ambiguous or missing match gives `tokenId = null` ("expiry unknown"), not an
     * error.
     *
     * A space read refused with 403 + JSON is either an invalid token or an account without a
     * space. The token list (owner check only, no space needed) tells them apart: if it answers,
     * the token is valid and the result has `spaceId = null` and `userId =`
     * [UNKNOWN_USER_ID] (no endpoint names the user without a space).
     */
    suspend fun loginWithPastedToken(server: TandoorServer, pastedToken: String): Outcome<DeviceCredentials> {
        val token = cleanPastedToken(pastedToken)
        // Anything that cannot go into an HTTP header (smart quotes, "ö", line breaks) is not a
        // token: refuse it here instead of letting OkHttp throw while building the request.
        if (token.isEmpty() || !isValidHeaderToken(token)) {
            return Outcome.Failure(TandemError.BadRequest(mapOf(FIELD_TOKEN to emptyList())))
        }
        val api = apiFactory.create(server.baseUrl, MutableTokenProvider(token))

        val space = when (val r = call { api.space.currentSpace() }) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> if (r.error.isRefusedByTandoor()) null else return r
        }
        if (space == null) {
            // Invalid token, or a valid one of an account without a space.
            val tokens = when (val r = call { api.auth.listAccessTokens() }) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
            val match = matchToken(tokens, token)
            return Outcome.Success(
                DeviceCredentials(
                    baseUrl = api.baseUrl,
                    token = token,
                    tokenId = match?.id,
                    expires = match?.let { TandoorDates.parseInstantOrNull(it.expires) },
                    userId = UNKNOWN_USER_ID,
                    username = "",
                    displayName = "",
                    spaceId = null,
                    spaceName = null,
                    serverVersion = server.version,
                ),
            )
        }
        val user = when (val r = readUser(api)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value ?: return decodeError("user-preference: no user")
        }
        val userId = user.id ?: return decodeError("user-preference: user.id missing")

        // Listing tokens needs only `read`; if it fails the login still works, expiry is unknown.
        val match = when (val r = call { api.auth.listAccessTokens() }) {
            is Outcome.Success -> matchToken(r.value, token)
            is Outcome.Failure -> if (r.error is TandemError.Unauthorized) return r else null
        }
        return Outcome.Success(
            DeviceCredentials(
                baseUrl = api.baseUrl,
                token = token,
                tokenId = match?.id,
                expires = match?.let { TandoorDates.parseInstantOrNull(it.expires) },
                userId = userId,
                username = user.username,
                displayName = user.displayName.ifBlank { user.username },
                spaceId = space.id,
                spaceName = space.name.ifBlank { null },
                serverVersion = server.version,
            ),
        )
    }

    /**
     * Revokes this device's token (logout, plan 4.2 point 4): `DELETE /api/access-token/{id}/`
     * authenticated with the token itself. Never touches any other token.
     *
     * Only Tandoor's own answers mean "already invalid": 404 + JSON and 403 + JSON. A 401 comes
     * from something in front of Tandoor (FACTS e: Tandoor never sends 401), e.g. SSO
     * forward-auth that never let the request through, so the token may still be valid; that and
     * every other error stay failures for the caller to report.
     */
    suspend fun revokeDeviceToken(baseUrl: String, token: String, tokenId: Long): Outcome<RevokeResult> {
        val api = apiFactory.create(baseUrl, MutableTokenProvider(token))
        return when (val r = call { api.auth.deleteAccessToken(tokenId) }) {
            is Outcome.Success -> Outcome.Success(RevokeResult.REVOKED)

            is Outcome.Failure ->
                if (r.error is TandemError.NotFound || r.error.isRefusedByTandoor()) {
                    Outcome.Success(RevokeResult.ALREADY_INVALID)
                } else {
                    r
                }
        }
    }

    /** The current user from `GET /api/user-preference/` (an array with the caller's entry). */
    private suspend fun readUser(api: TandoorApi): Outcome<UserDto?> = call {
        api.users.userPreferences().firstNotNullOfOrNull { it.user }
    }

    /**
     * `api-token-auth` is gone (Tandoor 2.7+ redirects it to the web login): a redirect, or 404 /
     * 405. The server step already saw Tandoor, so this is not a wrong address.
     */
    private fun TandemError.meansTokenLoginIsGone(): Boolean = when (this) {
        TandemError.NotFound -> true

        is TandemError.NotTandoor ->
            reason == NotTandoorReason.LOGIN_REDIRECT || reason == NotTandoorReason.REDIRECT ||
                httpStatus == HTTP_NOT_FOUND || httpStatus == HTTP_METHOD_NOT_ALLOWED

        else -> false
    }

    /** 403 + JSON: Tandoor itself refused (FACTS e), as opposed to a proxy (401) or firewall. */
    private fun TandemError.isRefusedByTandoor(): Boolean = this is TandemError.Unauthorized && source == UnauthorizedSource.TANDOOR

    /** True when [expires] is about now + 5 years, i.e. `api-token-auth` created the token just now. */
    internal fun isFreshLoginToken(expires: String?, now: Instant): Boolean {
        val expiry = TandoorDates.parseInstantOrNull(expires) ?: return false
        val candidates = listOf(
            now.plus(Duration.ofDays(LOGIN_TOKEN_LIFETIME_DAYS)),
            now.atOffset(ZoneOffset.UTC).plusYears(LOGIN_TOKEN_LIFETIME_YEARS).toInstant(),
        )
        return candidates.any { Duration.between(it, expiry).abs() <= FRESH_TOLERANCE }
    }

    private suspend fun <T> call(block: suspend () -> T): Outcome<T> = apiCall(classifier, block)

    private fun decodeError(detail: String): Outcome<Nothing> = Outcome.Failure(TandemError.Decode(detail))

    companion object {
        const val DEVICE_TOKEN_SCOPE: String = "read write"

        /** First Tandoor version without `api-token-auth`, where the password login uses allauth. */
        val ALLAUTH_LOGIN_FROM: TandoorVersion = TandoorVersion(2, 7, 0)

        /**
         * The password login cannot work for this server (allauth wants a step the app cannot do,
         * or there is no app login at all): the user should paste a token. Reported as
         * `TandemError.Unknown` with this detail; `core:data` turns it into its `DataProblem`.
         */
        const val PASSWORD_LOGIN_UNAVAILABLE_DETAIL: String = "tandem.network.password-login-unavailable"
        val PASSWORD_LOGIN_UNAVAILABLE: TandemError = TandemError.Unknown(PASSWORD_LOGIN_UNAVAILABLE_DETAIL)

        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val DEVICE_TOKEN_MISSING = "access-token: plaintext token or id missing"

        /** allauth: 401 = a pending step (verify the e-mail, a second factor); 404/405 = no app login. */
        private val NO_APP_LOGIN_STATUSES = setOf(401, HTTP_NOT_FOUND, HTTP_METHOD_NOT_ALLOWED)
        private val HTTP_REDIRECTS = 300..399

        /** True from [ALLAUTH_LOGIN_FROM] on; an unparseable version tries `api-token-auth` first. */
        fun usesAllauth(version: String?): Boolean = TandoorVersion.parse(version)?.let { it >= ALLAUTH_LOGIN_FROM } == true

        /**
         * `userId` of a pasted token whose account has no space: no endpoint names the user then.
         * Such credentials always have `spaceId = null` and are never stored (the data layer
         * reports "no space").
         */
        const val UNKNOWN_USER_ID: Long = 0L
        internal const val FIELD_TOKEN = "token"
        private const val LOGIN_TOKEN_LIFETIME_DAYS = 365L * 5
        private const val LOGIN_TOKEN_LIFETIME_YEARS = 5L
        private val FRESH_TOLERANCE: Duration = Duration.ofMinutes(10)
        private const val MASK_CHAR = '*'
        private const val MATCH_SUFFIX_LENGTH = 4

        internal fun isMasked(token: String): Boolean = token.contains(MASK_CHAR)

        private val TANDOOR_TOKEN = Regex("tda_[A-Za-z0-9_-]+")
        private val HEADER_TOKEN = Regex("^[\\x21-\\x7E]+$")
        private val QUOTES = charArrayOf('"', '\'', '`', '\u201C', '\u201D', '\u201E', '\u2018', '\u2019', '\u00AB', '\u00BB')

        /**
         * Accepts `tda_…`, `Bearer tda_…`, surrounding whitespace and quotes (also typographic
         * ones from notes and chat apps), invisible format characters (zero-width space, BOM) and
         * text around a Tandoor token ("Token för Tandem: tda_…"). The result may still contain
         * characters that are not allowed in a header; see [isValidHeaderToken].
         */
        internal fun cleanPastedToken(raw: String): String {
            val visible = raw.filterNot { Character.getType(it) == Character.FORMAT.toInt() }
            TANDOOR_TOKEN.find(visible)?.let { return it.value }
            var token = visible.trim().trim(*QUOTES).trim()
            if (token.startsWith("Bearer ", ignoreCase = true)) token = token.substring("Bearer ".length).trim()
            return token.trim(*QUOTES).trim()
        }

        /** Printable ASCII without spaces: the only characters a Bearer token may contain. */
        internal fun isValidHeaderToken(token: String): Boolean = HEADER_TOKEN.matches(token)

        /**
         * Finds the pasted token in the token list: an exact plaintext match wins; otherwise
         * exactly one entry with the same last 4 characters. Ambiguous or none: null.
         */
        internal fun matchToken(tokens: List<AccessTokenDto>, pasted: String): AccessTokenDto? {
            val withId = tokens.filter { it.id != null }
            withId.singleOrNull { it.token == pasted }?.let { return it }
            if (pasted.length < MATCH_SUFFIX_LENGTH * 2) return null
            val suffix = pasted.takeLast(MATCH_SUFFIX_LENGTH)
            if (suffix.contains(MASK_CHAR)) return null
            return withId.filter { it.token.length >= MATCH_SUFFIX_LENGTH && it.token.endsWith(suffix) }
                .singleOrNull()
        }
    }
}
