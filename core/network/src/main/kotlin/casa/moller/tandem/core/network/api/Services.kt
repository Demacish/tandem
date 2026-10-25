package casa.moller.tandem.core.network.api

import casa.moller.tandem.core.network.dto.AccessTokenCreateRequestDto
import casa.moller.tandem.core.network.dto.AccessTokenDto
import casa.moller.tandem.core.network.dto.AllauthLoginRequestDto
import casa.moller.tandem.core.network.dto.AllauthResponseDto
import casa.moller.tandem.core.network.dto.ServerSettingsDto
import casa.moller.tandem.core.network.dto.SpaceDto
import casa.moller.tandem.core.network.dto.TokenAuthRequestDto
import casa.moller.tandem.core.network.dto.TokenAuthResponseDto
import casa.moller.tandem.core.network.dto.UserDto
import casa.moller.tandem.core.network.dto.UserPreferenceDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

// Paths are relative (no leading slash) so that sub-path installs (https://x/tandoor/) work.
// Every path here must be listed in api-schema/used-endpoints.txt.

/** Login and per-device tokens (plan 3.1). */
interface AuthService {
    /** Never retried: 10 calls per day and IP, failures included (FACTS b). */
    @POST("api-token-auth/")
    suspend fun login(@Body body: TokenAuthRequestDto): TokenAuthResponseDto

    @GET("api/access-token/")
    suspend fun listAccessTokens(): List<AccessTokenDto>

    @POST("api/access-token/")
    suspend fun createAccessToken(@Body body: AccessTokenCreateRequestDto): AccessTokenDto

    /**
     * Tandoor 2.7+: the device token, created with the allauth session of the login instead of a
     * login token (`XSessionTokenAuthentication` is in Tandoor's DRF authentication classes).
     */
    @POST("api/access-token/")
    suspend fun createAccessTokenWithSession(
        @Header(HEADER_SESSION_TOKEN) sessionToken: String,
        @Body body: AccessTokenCreateRequestDto,
    ): AccessTokenDto

    /** 204 with an empty body. A token may delete itself. */
    @DELETE("api/access-token/{id}/")
    suspend fun deleteAccessToken(@Path("id") id: Long)
}

/**
 * allauth headless, app client (Tandoor 2.7+, which removed `api-token-auth`). Not part of
 * Tandoor's OpenAPI schema (allauth serves its own); listed in `api-schema/used-endpoints.txt`
 * under "allauth".
 */
interface AllauthService {
    /**
     * Never retried: allauth allows 3 logins per minute and IP (`ACCOUNT_RATE_LIMITS`), failures
     * included. 200 with `meta.session_token`; 400 for wrong or missing credentials; 401 when
     * the login needs another step (e-mail verification, a second factor) the app cannot do.
     */
    @POST("_allauth/app/v1/auth/login")
    suspend fun login(@Body body: AllauthLoginRequestDto): AllauthResponseDto

    /** Ends the login's session. allauth answers 401 (the now unauthenticated state). */
    @DELETE("_allauth/app/v1/auth/session")
    suspend fun logout(@Header(HEADER_SESSION_TOKEN) sessionToken: String): Response<Unit>
}

/** allauth's session header for the app client. */
const val HEADER_SESSION_TOKEN: String = "X-Session-Token"

/** Who is logged in. There is no `/api/user/me/`. */
interface UserService {
    @GET("api/user/{id}/")
    suspend fun user(@Path("id") id: Long): UserDto

    /** An array with exactly the caller's preferences; `user` is a full object. */
    @GET("api/user-preference/")
    suspend fun userPreferences(): List<UserPreferenceDto>
}

interface SpaceService {
    @GET("api/space/current/")
    suspend fun currentSpace(): SpaceDto
}

interface ServerService {
    /** Works without a token, and with an invalid one (FACTS a). */
    @GET("api/server-settings/current/")
    suspend fun serverSettings(): ServerSettingsDto
}
