package casa.moller.tandem.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Body of `POST /api-token-auth/`. The password only lives as long as this object. */
@Serializable
data class TokenAuthRequestDto(val username: String, val password: String) {
    override fun toString(): String = "TokenAuthRequestDto(username=$username, password=<redacted>)"
}

/**
 * Response of `POST /api-token-auth/` (FACTS b): `{id, token, scope, expires, user_id, test}`.
 * The schema documents only `token`, so everything else is optional. `test` equals `user_id`.
 */
@Serializable
data class TokenAuthResponseDto(
    val id: Long? = null,
    val token: String = "",
    val scope: String? = null,
    val expires: String? = null,
    @SerialName("user_id") val userId: Long? = null,
    val test: Long? = null,
) {
    override fun toString(): String = "TokenAuthResponseDto(id=$id, token=<redacted>, scope=$scope, expires=$expires, userId=$userId)"
}

/** Body of `POST /api/access-token/`. */
@Serializable
data class AccessTokenCreateRequestDto(val scope: String, val expires: String)

/**
 * One entry of `GET /api/access-token/` (an unpaginated array) or the `201` of a create.
 * [token] is plaintext in the create response and for ~15 s after; later it is masked as
 * `tda_************_******_***********` plus the last 4 characters (FACTS c).
 */
@Serializable
data class AccessTokenDto(
    val id: Long? = null,
    val token: String = "",
    val expires: String? = null,
    val scope: String? = null,
    val created: String? = null,
    val updated: String? = null,
) {
    override fun toString(): String = "AccessTokenDto(id=$id, token=<redacted>, expires=$expires, scope=$scope)"
}

// --- allauth headless, app client (Tandoor 2.7+, where api-token-auth is gone) ----------------

/** Body of `POST /_allauth/app/v1/auth/login`. The password only lives as long as this object. */
@Serializable
data class AllauthLoginRequestDto(val username: String, val password: String) {
    override fun toString(): String = "AllauthLoginRequestDto(username=$username, password=<redacted>)"
}

/**
 * allauth headless' response envelope (`{status, data, meta}`, FACTS 2.7 "login.allauth"). A
 * successful login has `meta.session_token`, which is sent as `X-Session-Token`. Only the fields
 * the app needs are read; the user's e-mail address is never decoded.
 */
@Serializable
data class AllauthResponseDto(
    val status: Int? = null,
    val data: AllauthDataDto? = null,
    val meta: AllauthMetaDto? = null,
) {
    override fun toString(): String = "AllauthResponseDto(status=$status, data=$data, meta=$meta)"
}

@Serializable
data class AllauthDataDto(val user: AllauthUserDto? = null)

@Serializable
data class AllauthUserDto(val id: Long? = null, val username: String? = null, val display: String? = null)

@Serializable
data class AllauthMetaDto(
    @SerialName("is_authenticated") val isAuthenticated: Boolean? = null,
    @SerialName("session_token") val sessionToken: String? = null,
) {
    override fun toString(): String = "AllauthMetaDto(isAuthenticated=$isAuthenticated, sessionToken=<redacted>)"
}
