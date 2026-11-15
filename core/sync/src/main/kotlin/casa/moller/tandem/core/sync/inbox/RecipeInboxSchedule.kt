package casa.moller.tandem.core.sync.inbox

import casa.moller.tandem.core.data.account.AccountRemovalHook
import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.datastore.RecipeInboxKeyStore
import casa.moller.tandem.core.datastore.RecipeInboxStore
import casa.moller.tandem.core.datastore.SecureAccountStore
import casa.moller.tandem.core.datastore.StorageHealthMonitor
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.account.identity
import casa.moller.tandem.core.sync.work.RecipeInboxWork
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Fas 5 (rss lane, plan §7 #35): keeps the recipe inbox in step, started by `SyncStarter`:
 *
 * - the light background refresh ([RecipeInboxWork], a few times a day with a network) exists only
 *   while the active real account has the inbox on (a key and at least one category); logging out,
 *   demo mode or turning the inbox off cancels it (plan: every background feature can be turned off,
 *   off by default);
 * - an account that leaves the phone takes its inbox along: [beforeRemoval] forgets its key, its
 *   settings and its posts (the posts also go with the account's rows, `AccountDao.deleteAccountData`),
 *   and accounts that left some other way are forgotten at the next start (never while local storage
 *   needs recovery: a store that was just reset reads as empty and is no logout).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class RecipeInboxSchedule @Inject constructor(
    private val sources: ActiveSources,
    private val store: RecipeInboxStore,
    private val keys: RecipeInboxKeyStore,
    private val repository: DefaultRecipeInboxRepository,
    private val work: RecipeInboxWork,
    private val accounts: SecureAccountStore,
    private val health: StorageHealthMonitor,
    @ApplicationScope private val scope: CoroutineScope,
) : AccountRemovalHook {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            sources.source
                .filter { it != ActiveSource.Loading }
                .distinctUntilChangedBy { it.identity }
                .flatMapLatest { source ->
                    if (source is ActiveSource.Remote) {
                        repository.state.map { it.settings.enabled }
                    } else {
                        flowOf(false)
                    }
                }
                .distinctUntilChanged()
                .catch { emit(false) }
                .collect { on -> if (on) work.ensure() else work.cancel() }
        }
        scope.launch {
            accounts.accounts
                .map { list -> list.mapTo(HashSet()) { it.id } }
                .distinctUntilChanged()
                .collect { known -> forgetAccountsThatLeft(known) }
        }
    }

    /** The account's token is not needed: nothing of the inbox is on the Tandoor server. */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun beforeRemoval(account: ServerAccount, token: String) {
        try {
            repository.forget(account.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A hook must not throw; retainOnly at the next start removes what was left.
        }
    }

    // Storage trouble of any kind waits for the next change; cancellation is rethrown first.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun forgetAccountsThatLeft(known: Set<String>) {
        if (health.health.value.recoveryNeeded) return
        try {
            if (!known.containsAll(store.accounts())) store.retainOnly(known)
            keys.retainOnly(known)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The next change (at the latest the next start) tries again.
        }
    }
}
