package casa.moller.tandem.core.datastore.internal

import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.account.TokenOrigin
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON settings for the encrypted document. Unknown keys are ignored for forward compatibility. */
internal val SecureDocumentJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** The plaintext of `secure_accounts.pb`. Only ever written to disk encrypted. */
@Serializable
internal data class SecureDocument(val schemaVersion: Int = CURRENT_SCHEMA_VERSION, val accounts: List<StoredAccount> = emptyList()) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * One account plus its device token. Dates are ISO-8601 strings ([Instant.toString]).
 * [toString] is overridden so the token can never end up in a log or crash report.
 *
 * [tokenOrigin] is the [TokenOrigin] name. Documents written before the field existed, and
 * unknown values, read as [TokenOrigin.PASTED]: the app then never deletes that token on the
 * server by mistake (only the user's own action in Tandoor does).
 */
@Serializable
internal data class StoredAccount(
    val id: String,
    val baseUrl: String,
    val username: String,
    val displayName: String? = null,
    val userId: Long,
    val spaceId: Long,
    val spaceName: String? = null,
    val tokenId: Long? = null,
    val tokenExpires: String? = null,
    val serverVersion: String? = null,
    val addedAt: String,
    val token: String,
    val tokenOrigin: String? = null,
) {
    /** @throws IllegalArgumentException if a stored date is malformed. */
    fun toModel(): ServerAccount = ServerAccount(
        id = id,
        baseUrl = baseUrl,
        username = username,
        displayName = displayName,
        userId = userId,
        spaceId = spaceId,
        spaceName = spaceName,
        tokenId = tokenId,
        tokenExpires = tokenExpires?.let(Instant::parse),
        serverVersion = serverVersion,
        addedAt = Instant.parse(addedAt),
        tokenOrigin = TokenOrigin.entries.firstOrNull { it.name == tokenOrigin } ?: TokenOrigin.PASTED,
    )

    override fun toString(): String = "StoredAccount(id=$id, baseUrl=$baseUrl, userId=$userId, token=<redacted>)"

    companion object {
        fun from(account: ServerAccount, token: String): StoredAccount = StoredAccount(
            id = account.id,
            baseUrl = account.baseUrl,
            username = account.username,
            displayName = account.displayName,
            userId = account.userId,
            spaceId = account.spaceId,
            spaceName = account.spaceName,
            tokenId = account.tokenId,
            tokenExpires = account.tokenExpires?.toString(),
            serverVersion = account.serverVersion,
            addedAt = account.addedAt.toString(),
            token = token,
            tokenOrigin = account.tokenOrigin.name,
        )
    }
}
