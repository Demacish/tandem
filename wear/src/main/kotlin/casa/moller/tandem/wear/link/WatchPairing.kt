package casa.moller.tandem.wear.link

import casa.moller.tandem.core.watchlink.PairError
import casa.moller.tandem.core.watchlink.PairReply
import casa.moller.tandem.core.watchlink.UnpairRequest
import casa.moller.tandem.core.watchlink.WatchCodec
import casa.moller.tandem.core.watchlink.WatchCredentials
import casa.moller.tandem.core.watchlink.WatchLink
import casa.moller.tandem.core.watchlink.WatchStatusReply
import casa.moller.tandem.wear.data.CredentialsStore
import casa.moller.tandem.wear.data.WearAccount
import casa.moller.tandem.wear.data.WearState
import casa.moller.tandem.wear.data.WearStateStore
import casa.moller.tandem.wear.sync.ShoppingRemote
import casa.moller.tandem.wear.sync.WearGuard

/**
 * The watch's half of "Koppla klockan" and "Koppla från klockan" (plan §7 #33): the phone's
 * requests over the Data Layer, answered here. Called by [WatchListenerService]; plain code, so the
 * token exchange is tested with a fake Data Layer (the phone's requests as bytes).
 *
 * - [pair]: stores the watch token encrypted, makes the list belong to the new account (another
 *   account or server empties it first, a renewed token for the same account keeps it and its
 *   outbox) and lifts a "log in again" state. Then [onPaired] starts a sync.
 * - [unpair]: optionally deletes the watch's own token on the server first (one request, when the
 *   guard allows it), then forgets the token, the list and the outbox. The phone's timers stay.
 * - [status]: what the phone's "Klockan" page shows about the watch.
 */
class WatchPairing(
    private val credentials: CredentialsStore,
    private val store: WearStateStore,
    private val remotes: ShoppingRemote.Factory,
    private val clock: () -> Long,
    private val onPaired: () -> Unit,
    private val onChanged: () -> Unit,
) {
    /** Answers a [WatchLink.PATH_PAIR] request. */
    suspend fun pair(request: ByteArray?): ByteArray {
        val incoming = WatchCodec.decode(WatchCredentials.serializer(), request)
        if (incoming == null || incoming.token.isBlank() || !incoming.baseUrl.startsWith("https://")) {
            return reply(PairReply(ok = false, error = PairError.BAD_PAYLOAD))
        }
        if (!credentials.save(incoming)) return reply(PairReply(ok = false, error = PairError.STORAGE))
        store.update { state ->
            val account = WearAccount(
                baseUrl = incoming.baseUrl,
                tokenId = incoming.tokenId,
                expiresEpochMillis = incoming.expiresEpochMillis,
                accountKey = incoming.accountKey,
                label = incoming.accountLabel,
                serverVersion = incoming.serverVersion,
            )
            val sameList = state.account?.accountKey == incoming.accountKey && state.account.baseUrl == incoming.baseUrl
            val base = if (sameList) state else WearState(timers = state.timers, timersReceivedMillis = state.timersReceivedMillis)
            base.copy(account = account, signedOut = false, guard = WearGuard.success())
        }
        onPaired()
        onChanged()
        return reply(PairReply(ok = true))
    }

    /** Answers a [WatchLink.PATH_UNPAIR] request. */
    suspend fun unpair(request: ByteArray?): ByteArray {
        val wish = WatchCodec.decode(UnpairRequest.serializer(), request) ?: UnpairRequest()
        val stored = credentials.load()
        val state = store.load()
        val mayAsk = !state.signedOut && WearGuard.mayRequest(state.guard, clock())
        if (wish.revoke && stored != null && mayAsk) {
            // Best effort: an expired or already deleted token simply fails; it is forgotten anyway.
            remotes.create(stored).revoke(stored.tokenId)
        }
        credentials.clear()
        store.update { WearState(timers = it.timers, timersReceivedMillis = it.timersReceivedMillis) }
        onChanged()
        return reply(PairReply(ok = true))
    }

    /** Answers a [WatchLink.PATH_STATUS] request. */
    suspend fun status(): ByteArray {
        val stored = credentials.load()
        val state = store.load()
        val status = WatchStatusReply(
            paired = stored != null,
            tokenId = stored?.tokenId,
            accountKey = stored?.accountKey,
            signedOut = state.signedOut,
            pending = state.pending.size,
            lastSyncEpochMillis = state.lastSyncMillis,
        )
        return WatchCodec.encode(WatchStatusReply.serializer(), status)
    }

    private fun reply(reply: PairReply): ByteArray = WatchCodec.encode(PairReply.serializer(), reply)
}
