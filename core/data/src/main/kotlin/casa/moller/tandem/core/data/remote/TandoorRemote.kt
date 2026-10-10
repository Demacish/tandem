package casa.moller.tandem.core.data.remote

import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.model.error.Outcome
import kotlin.time.Instant

/**
 * The server calls the account and session repositories need, in core:data's own types. The
 * production implementation wraps `ServerProbe` and `TandoorAuthenticator` from core:network;
 * tests use a fake.
 */
interface TandoorRemote {
    suspend fun probe(url: String): Outcome<ServerInfo>

    /** Password login that creates this device's own token (plan 3.1). Never retried. */
    suspend fun login(server: ServerInfo, username: String, password: String): Outcome<RemoteCredentials>

    /** Validates a pasted token and reads who it belongs to. */
    suspend fun loginWithToken(server: ServerInfo, token: String): Outcome<RemoteCredentials>

    /**
     * Deletes this device's token on the server. Success is [TokenRevocation.REVOKED] or
     * [TokenRevocation.ALREADY_INVALID]; anything else is a failure.
     */
    suspend fun revoke(baseUrl: String, token: String, tokenId: Long): Outcome<TokenRevocation>
}

/** What a login yields. [token] is this device's token; [toString] never shows it. */
data class RemoteCredentials(
    /** Normalized base URL with trailing slash. */
    val baseUrl: String,
    val token: String,
    /** Server id of [token]; null for a pasted token that could not be matched. */
    val tokenId: Long?,
    val tokenExpires: Instant?,
    val userId: Long,
    val username: String,
    val displayName: String?,
    /** Null when the user has no space on the server. */
    val spaceId: Long?,
    val spaceName: String?,
    val serverVersion: String,
) {
    override fun toString(): String = "RemoteCredentials(baseUrl=$baseUrl, token=<redacted>, tokenId=$tokenId, " +
        "tokenExpires=$tokenExpires, userId=$userId, username=$username, spaceId=$spaceId, " +
        "spaceName=$spaceName, serverVersion=$serverVersion)"
}
