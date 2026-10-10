package casa.moller.tandem.core.network.auth

import java.time.Instant

/**
 * What a successful login yields; the data layer stores it encrypted (plan 5.5) and forgets
 * everything else. [token] is this device's own token (plan 3.1 step 3), never the shared token
 * returned by `api-token-auth`.
 */
data class DeviceCredentials(
    /** Normalized base URL with trailing slash. */
    val baseUrl: String,
    val token: String,
    /** Id of [token] for renewal and revocation; null when a pasted token could not be matched. */
    val tokenId: Long?,
    /** When [token] expires; null when unknown (pasted token without an unambiguous match). */
    val expires: Instant?,
    val userId: Long,
    val username: String,
    val displayName: String,
    val spaceId: Long?,
    /** Name of the active space, for the account switcher; null when the server sent none. */
    val spaceName: String?,
    val serverVersion: String,
) {
    override fun toString(): String = "DeviceCredentials(baseUrl=$baseUrl, token=<redacted>, tokenId=$tokenId, expires=$expires, " +
        "userId=$userId, username=$username, spaceId=$spaceId, spaceName=$spaceName, " +
        "serverVersion=$serverVersion)"
}

/**
 * What to do with the token that `api-token-auth` returned, after the device token exists
 * (plan 3.1 step 4).
 */
enum class LoginTokenPolicy {
    /**
     * Never delete it (default). Observed on 2.6.13 (FACTS b, consequence 5): `api-token-auth`
     * hands out the user's OLDEST valid read+write token. If the login token is deleted, the next
     * password login from any client receives this device's token in plaintext, which would
     * share it again.
     */
    KEEP,

    /**
     * Plan 3.1 step 4 as written: delete it when its `expires` lies about 5 years ahead, i.e. it
     * was created by this very login. Only for servers where that trade-off is wanted.
     */
    DELETE_IF_FRESH,
}

/** Result of revoking this device's token. */
enum class RevokeResult {
    /** 204: the token was deleted. */
    REVOKED,

    /** The token was already gone or invalid (404 or 403 with JSON); nothing left to revoke. */
    ALREADY_INVALID,
}
