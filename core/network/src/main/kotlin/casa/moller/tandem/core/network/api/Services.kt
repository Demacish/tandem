package casa.moller.tandem.core.network.api

import casa.moller.tandem.core.network.dto.AccessTokenCreateRequestDto
import casa.moller.tandem.core.network.dto.AccessTokenDto
import casa.moller.tandem.core.network.dto.ServerSettingsDto
import casa.moller.tandem.core.network.dto.SpaceDto
import casa.moller.tandem.core.network.dto.TokenAuthRequestDto
import casa.moller.tandem.core.network.dto.TokenAuthResponseDto
import casa.moller.tandem.core.network.dto.UserDto
import casa.moller.tandem.core.network.dto.UserPreferenceDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
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

    /** 204 with an empty body. A token may delete itself. */
    @DELETE("api/access-token/{id}/")
    suspend fun deleteAccessToken(@Path("id") id: Long)
}

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
