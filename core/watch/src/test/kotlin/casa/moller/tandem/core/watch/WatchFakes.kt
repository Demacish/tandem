package casa.moller.tandem.core.watch

import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.datastore.SecureAccountStore
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.account.TokenOrigin
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.watchlink.PairError
import casa.moller.tandem.core.watchlink.PairReply
import casa.moller.tandem.core.watchlink.UnpairRequest
import casa.moller.tandem.core.watchlink.WatchCodec
import casa.moller.tandem.core.watchlink.WatchCredentials
import casa.moller.tandem.core.watchlink.WatchLink
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal fun account(id: String = "account-1", baseUrl: String = "https://tandoor.example.com") = ServerAccount(
    id = id,
    baseUrl = baseUrl,
    username = "anna",
    displayName = "Anna",
    userId = 1,
    spaceId = 1,
    serverVersion = "2.6.13",
    addedAt = Instant.fromEpochMilliseconds(0),
    tokenOrigin = TokenOrigin.CREATED,
)

internal class FakeSessions(initial: Session) : SessionRepository {
    val current = MutableStateFlow(initial)

    override val session: Flow<Session> = current

    override suspend fun refreshServerInfo(): Outcome<ServerInfo> = Outcome.Failure(TandemError.NetworkUnavailable)

    override suspend fun logout(revokeToken: Boolean): Outcome<TokenRevocation> = Outcome.Success(TokenRevocation.SKIPPED)

    override suspend fun startDemo(): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun leaveDemo(): Outcome<Unit> = Outcome.Success(Unit)

    override fun acknowledgeRecovery() = Unit

    override suspend fun clearAppData(): Outcome<Unit> = Outcome.Success(Unit)

    companion object {
        fun loggedIn(account: ServerAccount) = Session.LoggedIn(account, "2.6.13", ServerCompatibility.SUPPORTED)
    }
}

/** The phone's encrypted accounts: the phone token is `tda_…phone-<account>`. */
internal class FakeAccounts(vararg initial: ServerAccount) : SecureAccountStore {
    val stored = MutableStateFlow(initial.toList())

    override val accounts: Flow<List<ServerAccount>> = stored

    override suspend fun upsert(account: ServerAccount, token: String) {
        stored.value = stored.value.filterNot { it.id == account.id } + account
    }

    override suspend fun update(account: ServerAccount): Boolean = true

    override suspend fun token(accountId: String): String? = if (stored.value.any { it.id == accountId }) "phone-$accountId" else null

    override suspend fun remove(accountId: String) {
        stored.value = stored.value.filterNot { it.id == accountId }
    }

    override suspend fun clearAll() {
        stored.value = emptyList()
    }
}

/** Tandoor's access tokens as the phone sees them; every created and deleted id is recorded. */
internal class FakeWatchTokens : WatchTokens {
    private var nextId = 100L
    val created = mutableListOf<Long>()
    val deleted = mutableListOf<Pair<String, Long>>()
    var failCreate: TandemError? = null
    var failDelete: TandemError? = null

    override suspend fun create(baseUrl: String, phoneToken: String, expires: Instant): Outcome<CreatedWatchToken> {
        failCreate?.let { return Outcome.Failure(it) }
        val id = nextId++
        created += id
        return Outcome.Success(CreatedWatchToken("$baseUrl/", "tda_00000000_0000_0000_0000_000000000$id", id, expires))
    }

    override suspend fun delete(baseUrl: String, phoneToken: String, tokenId: Long): Outcome<Unit> {
        failDelete?.let { return Outcome.Failure(it) }
        deleted += phoneToken to tokenId
        return Outcome.Success(Unit)
    }
}

/**
 * A fake Data Layer with one watch that runs the watch's side of the protocol: it keeps what the
 * phone sent (as the Data Layer delivers it, bytes on a path) and answers like the watch app.
 */
internal class FakeWatchLayer : DataLayer {
    var available = true
    var watch: WatchNode? = WatchNode("node-1", "Galaxy Watch", nearby = true)
    var hasTandem = true
    var refuses = false
    var stored: WatchCredentials? = null
    val unpairs = mutableListOf<UnpairRequest>()
    val messages = mutableListOf<Pair<String, ByteArray>>()

    override suspend fun connectedNodes(): List<WatchNode> = check().let { listOfNotNull(watch) }

    override suspend fun watchesWithTandem(): List<WatchNode> = check().let { if (hasTandem) listOfNotNull(watch) else emptyList() }

    override suspend fun request(nodeId: String, path: String, data: ByteArray): ByteArray {
        check()
        if (watch?.id != nodeId || !hasTandem) throw DataLayerException("unreachable")
        val reply = when (path) {
            WatchLink.PATH_PAIR -> if (refuses) {
                PairReply(false, PairError.STORAGE)
            } else {
                stored = WatchCodec.decode(WatchCredentials.serializer(), data)
                PairReply(stored != null, if (stored == null) PairError.BAD_PAYLOAD else null)
            }

            WatchLink.PATH_UNPAIR -> {
                unpairs += requireNotNull(WatchCodec.decode(UnpairRequest.serializer(), data))
                stored = null
                PairReply(true)
            }

            else -> throw DataLayerException("unknown path $path")
        }
        return WatchCodec.encode(PairReply.serializer(), reply)
    }

    override suspend fun send(nodeId: String, path: String, data: ByteArray) {
        check()
        if (watch?.id != nodeId) throw DataLayerException("unreachable")
        messages += path to data
    }

    private fun check() {
        if (!available) throw DataLayerException("no Play services")
    }
}

/** The pairing in memory. */
internal class InMemoryPairingStore : WatchPairingStore {
    val stored = MutableStateFlow<StoredWatchPairing?>(null)

    override val pairing: Flow<StoredWatchPairing?> = stored

    override suspend fun save(pairing: StoredWatchPairing?) {
        stored.value = pairing
    }
}
