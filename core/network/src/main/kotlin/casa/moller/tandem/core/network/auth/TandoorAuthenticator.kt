package casa.moller.tandem.core.network.auth

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.core.model.error.getOrNull
import casa.moller.tandem.core.network.api.TandoorApi
import casa.moller.tandem.core.network.api.TandoorApiFactory
import casa.moller.tandem.core.network.dto.AccessTokenCreateRequestDto
import casa.moller.tandem.core.network.dto.AccessTokenDto
import casa.moller.tandem.core.network.dto.TokenAuthRequestDto
import casa.moller.tandem.core.network.dto.UserDto
import casa.moller.tandem.core.network.error.ErrorClassifier
import casa.moller.tandem.core.network.error.apiCall
import casa.moller.tandem.core.network.http.MutableTokenProvider
import casa.moller.tandem.core.network.json.TandoorDates
import casa.moller.tandem.core.network.probe.TandoorServer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.serialization.SerializationException

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

        // Step 1: the (possibly shared) login token.
        val login = when (val r = call { api.auth.login(TokenAuthRequestDto(username, password)) }) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val loginToken = login.token.trim()
        val userId = login.userId ?: login.test
        if (loginToken.isEmpty() || userId == null) return decodeError("api-token-auth: token or user_id missing")

        // Step 2: this device's own token, created with the login token.
        tokens.token = loginToken
        val now = clock.instant()
        val requestedExpiry = now.atOffset(ZoneOffset.UTC).plusYears(1).toInstant()
        val device = when (
            val r = call {
                api.auth.createAccessToken(
                    AccessTokenCreateRequestDto(scope = DEVICE_TOKEN_SCOPE, expires = TandoorDates.format(requestedExpiry)),
                )
            }
        ) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val deviceToken = device.token.trim()
        val deviceTokenId = device.id
        if (deviceToken.isEmpty() || isMasked(deviceToken) || deviceTokenId == null) {
            return decodeError("access-token: plaintext token or id missing")
        }

        // Step 3: from here on only the device token is used.
        tokens.token = deviceToken

        // Step 4: optionally delete the login token if this login created it.
        val loginTokenId = login.id
        if (loginTokenPolicy == LoginTokenPolicy.DELETE_IF_FRESH &&
            loginTokenId != null && loginTokenId != deviceTokenId &&
            isFreshLoginToken(login.expires, now)
        ) {
            // Failure is harmless: the token simply stays, as with KEEP.
            call { api.auth.deleteAccessToken(loginTokenId) }
        }

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
                    call { api.auth.deleteAccessToken(deviceTokenId) }
                    return r
                }
        }
        // Without a space user-preference answers 500 (FACTS h), so it is not even asked then.
        // With a space, a failed user read only costs the display name.
        val user = if (space == null) loginUser else readUser(api).getOrNull() ?: loginUser
        return Outcome.Success(
            DeviceCredentials(
                baseUrl = api.baseUrl,
                token = deviceToken,
                tokenId = deviceTokenId,
                expires = TandoorDates.parseInstantOrNull(device.expires) ?: requestedExpiry,
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
