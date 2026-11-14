package casa.moller.tandem.core.watch

import casa.moller.tandem.core.data.account.AccountRemovalHook
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.watch.WatchRepository
import casa.moller.tandem.core.datastore.SecureAccountStore
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.watch.PairedWatch
import casa.moller.tandem.core.model.watch.WatchAvailability
import casa.moller.tandem.core.model.watch.WatchBlock
import casa.moller.tandem.core.model.watch.WatchOutcome
import casa.moller.tandem.core.model.watch.WatchResult
import casa.moller.tandem.core.model.watch.WatchState
import casa.moller.tandem.core.watchlink.PairReply
import casa.moller.tandem.core.watchlink.UnpairRequest
import casa.moller.tandem.core.watchlink.WatchCodec
import casa.moller.tandem.core.watchlink.WatchCredentials
import casa.moller.tandem.core.watchlink.WatchLink
import casa.moller.tandem.core.watchlink.WatchTokenPolicy
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [WatchRepository] (Fas 5, wear lane; plan §7 #33, §3.1; docs/BYGGE.md "Klockan").
 *
 * - **Pairing:** only for a logged-in member (never demo mode, never a guest, whose checks Tandoor
 *   refuses) and only with a NEARBY watch that has Tandem. The phone creates the watch token with
 *   the active account's token, sends it in one Data Layer request and keeps only its id. A watch
 *   that does not take it gets the token deleted again at once; a renewed pairing deletes the
 *   previous token. One account per watch: pairing for another account replaces the old one.
 * - **Unpairing:** the phone deletes the token on the server with its own token, then the watch
 *   forgets everything. Without the phone's token (the account was logged out) the watch is asked
 *   to delete its own token ([UnpairRequest.revoke]).
 * - **Upkeep** ([maintain], at app start and when the stored accounts change): the paired account
 *   left the phone → the watch deletes its own token as soon as it is reachable; the token
 *   expires within [WatchTokenPolicy.RENEW_BEFORE] and the watch is near → paired again.
 * - **The account leaves the phone** (Fas 5, integration-08; [beforeRemoval], an
 *   [AccountRemovalHook]): the phone deletes the watch token on the server with the account's
 *   token while it still has it; the watch is then only wiped. Offline, the upkeep above remains.
 */
@Singleton
internal class DefaultWatchRepository @Inject constructor(
    private val sessions: SessionRepository,
    private val accounts: SecureAccountStore,
    private val permissions: SpacePermissionsRepository,
    private val tokens: WatchTokens,
    private val dataLayer: DataLayer,
    private val store: WatchPairingStore,
    private val clock: Clock,
) : WatchRepository,
    AccountRemovalHook {
    private val discovery = MutableStateFlow(Discovery())
    private val busy = MutableStateFlow(false)
    private val actions = Mutex()

    /** What the last look for watches found. */
    private data class Discovery(
        val availability: WatchAvailability = WatchAvailability.CHECKING,
        val tandemWatch: WatchNode? = null,
        val otherWatch: WatchNode? = null,
    )

    override val state: Flow<WatchState> = combine(
        sessions.session,
        permissions.permissions,
        store.pairing,
        discovery,
        busy,
    ) { session, rights, pairing, found, working ->
        val active = (session as? Session.LoggedIn)?.account?.id
        WatchState(
            availability = found.availability,
            watchName = (found.tandemWatch ?: found.otherWatch)?.name,
            paired = pairing?.let {
                PairedWatch(
                    watchName = it.watchName,
                    accountId = it.accountId,
                    accountLabel = it.accountLabel,
                    tokenExpires = Instant.fromEpochMilliseconds(it.expiresEpochMillis),
                    pairedAt = Instant.fromEpochMilliseconds(it.pairedAtEpochMillis),
                )
            },
            block = block(session, rights),
            pairedForAnotherAccount = pairing != null && active != null && pairing.accountId != active,
            busy = working,
        )
    }

    override suspend fun refresh() {
        discovery.value = discover()
    }

    override suspend fun pair(): WatchResult = action { pairNow() }

    override suspend fun unpair(): WatchResult = action { unpairNow() }

    /** Upkeep, see the class comment. Never throws; a watch that is away is tried again next time. */
    suspend fun maintain() {
        val stored = store.load() ?: return
        val known = accounts.accounts.first()
        if (known.none { it.id == stored.accountId }) {
            val wiped = ask(stored.nodeId, WatchLink.PATH_UNPAIR, unpairRequest(stored.tokenId, revoke = !stored.revokedOnServer))
            store.save(if (wiped) null else stored.copy(revokeOnWatch = true))
            return
        }
        val active = (sessions.session.first() as? Session.LoggedIn)?.account ?: return
        val expires = Instant.fromEpochMilliseconds(stored.expiresEpochMillis)
        if (active.id == stored.accountId && WatchTokenPolicy.needsRenewal(expires, clock.now())) {
            val near = discover().also { discovery.value = it }.tandemWatch
            if (near?.id == stored.nodeId && near.nearby) pair()
        }
    }

    /** See the class comment; a failure leaves the pairing to [maintain] (the watch deletes its own token). */
    override suspend fun beforeRemoval(account: ServerAccount, token: String) {
        actions.withLock {
            val stored = store.load()?.takeIf { it.accountId == account.id && !it.revokedOnServer } ?: return
            if (tokens.delete(account.baseUrl, token, stored.tokenId) is Outcome.Success) {
                store.save(stored.copy(revokedOnServer = true))
            }
        }
    }

    private suspend fun action(block: suspend () -> WatchResult): WatchResult = actions.withLock {
        busy.value = true
        try {
            block()
        } finally {
            busy.value = false
        }
    }

    private suspend fun pairNow(): WatchResult {
        val session = sessions.session.first()
        if (block(session, permissions.permissions.first()) != null) return WatchResult(WatchOutcome.NOT_ALLOWED)
        val account = (session as Session.LoggedIn).account
        val phoneToken = phoneToken(account.id) ?: return WatchResult(WatchOutcome.NOT_ALLOWED)
        val found = discover().also { discovery.value = it }
        val watch = found.tandemWatch?.takeIf { it.nearby } ?: return WatchResult(WatchOutcome.NO_WATCH)

        val created = when (val made = tokens.create(account.baseUrl, phoneToken, WatchTokenPolicy.expiry(clock.now()))) {
            is Outcome.Failure -> return WatchResult(WatchOutcome.SERVER, made.error)
            is Outcome.Success -> made.value
        }
        val credentials = WatchCredentials(
            baseUrl = created.baseUrl,
            token = created.token,
            tokenId = created.id,
            expiresEpochMillis = created.expires.toEpochMilliseconds(),
            accountKey = account.id,
            accountLabel = account.label,
            serverVersion = account.serverVersion,
        )
        val reply = request(watch.id, WatchLink.PATH_PAIR, WatchCodec.encode(WatchCredentials.serializer(), credentials))
        if (reply?.ok != true) {
            // Never leave a token behind that no device holds.
            tokens.delete(account.baseUrl, phoneToken, created.id)
            return WatchResult(if (reply == null) WatchOutcome.NO_WATCH else WatchOutcome.WATCH_REFUSED)
        }
        val previous = store.load()
        store.save(
            StoredWatchPairing(
                nodeId = watch.id,
                watchName = watch.name,
                accountId = account.id,
                accountLabel = account.label,
                tokenId = created.id,
                expiresEpochMillis = created.expires.toEpochMilliseconds(),
                pairedAtEpochMillis = clock.now().toEpochMilliseconds(),
            ),
        )
        if (previous != null && previous.tokenId != created.id) deleteOld(previous)
        return WatchResult(WatchOutcome.PAIRED)
    }

    private suspend fun unpairNow(): WatchResult {
        val stored = store.load() ?: return WatchResult(WatchOutcome.UNPAIRED)
        val account = accounts.accounts.first().firstOrNull { it.id == stored.accountId }
        val phoneToken = account?.let { phoneToken(it.id) }
        var serverError: TandemError? = null
        val serverDone = if (stored.revokedOnServer) {
            true
        } else if (account != null && phoneToken != null) {
            when (val deleted = tokens.delete(account.baseUrl, phoneToken, stored.tokenId)) {
                is Outcome.Success -> true

                is Outcome.Failure -> {
                    serverError = deleted.error
                    false
                }
            }
        } else {
            false
        }
        // Without the server's confirmation the watch deletes its own token (a token may delete itself).
        val watchDone = ask(stored.nodeId, WatchLink.PATH_UNPAIR, unpairRequest(stored.tokenId, revoke = !serverDone))
        return when {
            serverDone || watchDone -> {
                store.save(null)
                WatchResult(if (watchDone) WatchOutcome.UNPAIRED else WatchOutcome.UNPAIRED_WATCH_AWAY)
            }

            serverError != null -> WatchResult(WatchOutcome.SERVER, serverError)

            else -> WatchResult(WatchOutcome.NO_WATCH)
        }
    }

    /** The token of the pairing that was replaced, deleted with its own account's phone token. */
    private suspend fun deleteOld(previous: StoredWatchPairing) {
        val owner = accounts.accounts.first().firstOrNull { it.id == previous.accountId } ?: return
        val token = phoneToken(owner.id) ?: return
        tokens.delete(owner.baseUrl, token, previous.tokenId)
    }

    private suspend fun discover(): Discovery = try {
        val stored = store.load()
        val tandem = dataLayer.watchesWithTandem()
        val chosen = tandem.firstOrNull { it.id == stored?.nodeId } ?: tandem.firstOrNull { it.nearby } ?: tandem.firstOrNull()
        when {
            chosen != null && chosen.nearby -> Discovery(WatchAvailability.READY, tandemWatch = chosen)

            chosen != null -> Discovery(WatchAvailability.NO_WATCH, tandemWatch = chosen)

            else -> {
                val other = dataLayer.connectedNodes().firstOrNull()
                Discovery(if (other != null) WatchAvailability.NO_APP else WatchAvailability.NO_WATCH, otherWatch = other)
            }
        }
    } catch (e: DataLayerException) {
        Discovery(WatchAvailability.UNAVAILABLE)
    }

    private suspend fun request(nodeId: String, path: String, data: ByteArray): PairReply? = try {
        WatchCodec.decode(PairReply.serializer(), dataLayer.request(nodeId, path, data))
    } catch (e: DataLayerException) {
        null
    }

    private suspend fun ask(nodeId: String, path: String, data: ByteArray): Boolean = request(nodeId, path, data)?.ok == true

    private suspend fun phoneToken(accountId: String): String? = try {
        accounts.token(accountId)?.takeIf { it.isNotBlank() }
    } catch (e: IOException) {
        null
    }

    private fun unpairRequest(tokenId: Long, revoke: Boolean): ByteArray =
        WatchCodec.encode(UnpairRequest.serializer(), UnpairRequest(tokenId = tokenId, revoke = revoke))

    private fun block(session: Session, rights: SpacePermissions): WatchBlock? = when (session) {
        Session.Demo -> WatchBlock.DEMO
        is Session.LoggedIn -> if (rights.readOnly) WatchBlock.GUEST else null
        Session.NoAccount, is Session.RecoveryNeeded -> WatchBlock.NO_ACCOUNT
    }
}
