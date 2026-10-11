package casa.moller.tandem.core.sync.account

import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.datastore.SecureAccountStore
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.network.api.TandoorApi
import casa.moller.tandem.core.network.api.TandoorApiFactory
import casa.moller.tandem.core.network.http.TokenProvider
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Follows the session and turns it into the [ActiveSource] every repository uses (plan 5.3,
 * B14: one active account per device). Keeps the active account's device token in memory for
 * OkHttp (the encrypted store is only read here, never on OkHttp's threads), and one
 * [TandoorApi] per account and server.
 */
@Singleton
internal class ActiveSourceProvider @Inject constructor(
    sessions: SessionRepository,
    private val accountStore: SecureAccountStore,
    private val apiFactory: TandoorApiFactory,
    @ApplicationScope scope: CoroutineScope,
) {
    private val tokens = ConcurrentHashMap<String, String>()
    private val apis = ConcurrentHashMap<String, TandoorApi>()

    /** The current source; [ActiveSource.Loading] until the session is known. */
    val source: StateFlow<ActiveSource> = sessions.session
        .map { session -> sourceFor(session) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, ActiveSource.Loading)

    /** The current source once the session is known. */
    suspend fun current(): ActiveSource = source.first { it != ActiveSource.Loading }

    /** The in-memory token of [accountId] for OkHttp; null until it was read (or when logged out). */
    fun tokenProvider(accountId: String): TokenProvider = TokenProvider { tokens[accountId] }

    /** Reads the account's token from the encrypted store again (after a new login it changed). */
    suspend fun reloadToken(accountId: String) {
        val token = accountStore.token(accountId)
        if (token.isNullOrBlank()) tokens.remove(accountId) else tokens[accountId] = token
    }

    private suspend fun sourceFor(session: Session): ActiveSource = when (session) {
        is Session.LoggedIn -> remoteFor(session.account)
        Session.Demo -> ActiveSource.Demo
        Session.NoAccount, is Session.RecoveryNeeded -> ActiveSource.None
    }

    private suspend fun remoteFor(account: ServerAccount): ActiveSource {
        reloadToken(account.id)
        val key = account.id + "|" + account.baseUrl
        val api = apis[key] ?: try {
            apiFactory.create(account.baseUrl, tokenProvider(account.id)).also { apis[key] = it }
        } catch (e: IllegalArgumentException) {
            // A stored address that is not an https URL (never written by the app): no source.
            return ActiveSource.None
        }
        return ActiveSource.Remote(account, api)
    }
}
