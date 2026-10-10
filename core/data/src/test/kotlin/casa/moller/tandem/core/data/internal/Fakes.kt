package casa.moller.tandem.core.data.internal

import casa.moller.tandem.core.data.remote.RemoteCredentials
import casa.moller.tandem.core.data.remote.TandoorRemote
import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.datastore.SecureAccountStore
import casa.moller.tandem.core.datastore.SecureStorageException
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.settings.AppSettings
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

internal val SERVER = ServerInfo(
    baseUrl = "https://tandoor.example.com/",
    version = "2.6.13",
    compatibility = ServerCompatibility.SUPPORTED,
)

internal fun credentials(
    baseUrl: String = SERVER.baseUrl,
    token: String = "tda_device_token_0001",
    tokenId: Long? = 42,
    userId: Long = 7,
    spaceId: Long? = 1,
    serverVersion: String = SERVER.version,
) = RemoteCredentials(
    baseUrl = baseUrl,
    token = token,
    tokenId = tokenId,
    tokenExpires = Instant.parse("2027-10-09T08:00:00Z"),
    userId = userId,
    username = "anna",
    displayName = "Anna",
    spaceId = spaceId,
    spaceName = "Home",
    serverVersion = serverVersion,
)

/** Scripted [TandoorRemote] that records every call. */
internal class FakeTandoorRemote : TandoorRemote {
    var probeResult: Outcome<ServerInfo> = Outcome.Success(SERVER)
    var loginResult: Outcome<RemoteCredentials> = Outcome.Success(credentials())
    var tokenLoginResult: Outcome<RemoteCredentials> = Outcome.Success(credentials(tokenId = null))
    var revokeResult: Outcome<TokenRevocation> = Outcome.Success(TokenRevocation.REVOKED)

    val probes = mutableListOf<String>()
    val logins = mutableListOf<Pair<String, String>>()
    val tokenLogins = mutableListOf<String>()
    val revocations = mutableListOf<Triple<String, String, Long>>()

    /** When set, revoke waits for it (to cancel the caller while the request runs). */
    var revokeGate: CompletableDeferred<Unit>? = null

    /** Revocations that ran to the end (not cancelled while waiting for [revokeGate]). */
    var completedRevocations = 0

    override suspend fun probe(url: String): Outcome<ServerInfo> {
        probes += url
        return probeResult
    }

    override suspend fun login(server: ServerInfo, username: String, password: String): Outcome<RemoteCredentials> {
        logins += username to password
        return loginResult
    }

    override suspend fun loginWithToken(server: ServerInfo, token: String): Outcome<RemoteCredentials> {
        tokenLogins += token
        return tokenLoginResult
    }

    override suspend fun revoke(baseUrl: String, token: String, tokenId: Long): Outcome<TokenRevocation> {
        revocations += Triple(baseUrl, token, tokenId)
        revokeGate?.await()
        completedRevocations++
        return revokeResult
    }
}

/**
 * In-memory [SecureAccountStore]; [failWrites] simulates unusable secure storage, [failRemoves]
 * a store that can still add but not remove.
 */
internal class FakeSecureAccountStore : SecureAccountStore {
    private val state = MutableStateFlow<List<Pair<ServerAccount, String>>>(emptyList())
    var failWrites = false
    var failRemoves = false

    override val accounts: Flow<List<ServerAccount>> = state.map { list -> list.map { it.first } }

    fun tokens(): Map<String, String> = state.value.associate { it.first.id to it.second }

    override suspend fun upsert(account: ServerAccount, token: String) {
        require(token.isNotBlank())
        checkWritable()
        state.update { list ->
            val index = list.indexOfFirst { it.first.id == account.id }
            if (index < 0) list + (account to token) else list.toMutableList().also { it[index] = account to token }
        }
    }

    override suspend fun update(account: ServerAccount): Boolean {
        checkWritable()
        var found = false
        state.update { list ->
            list.map { (old, token) ->
                if (old.id == account.id) {
                    found = true
                    account to token
                } else {
                    old to token
                }
            }
        }
        return found
    }

    override suspend fun token(accountId: String): String? = tokens()[accountId]

    override suspend fun remove(accountId: String) {
        checkWritable()
        if (failRemoves) throw SecureStorageException("test: remove failed")
        state.update { list -> list.filterNot { it.first.id == accountId } }
    }

    override suspend fun clearAll() {
        state.value = emptyList()
        checkWritable()
    }

    private fun checkWritable() {
        if (failWrites) throw SecureStorageException("test: storage unavailable")
    }
}

internal class FakeSettingsStore(initial: AppSettings = AppSettings.Default) : SettingsStore {
    val state = MutableStateFlow(initial)
    var failWrites = false

    override val settings: Flow<AppSettings> = state

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        if (failWrites) throw java.io.IOException("test: settings not writable")
        state.update(transform)
    }
}
