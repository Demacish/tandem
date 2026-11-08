package casa.moller.tandem.core.sync.shopping

import casa.moller.tandem.core.data.shopping.ShoppingViewPreferences
import casa.moller.tandem.core.datastore.SecureAccountStore
import casa.moller.tandem.core.datastore.ShoppingViewStore
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * [ShoppingViewPreferences] (Fas 5, lane handla; plan §8.2 "Rutnätsvy"): the grid per account in
 * core:datastore's [ShoppingViewStore], keyed by the active source (demo mode has its own key).
 * Accounts that left the device are dropped at the next change, so only ids of accounts still here
 * stay in the file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class DefaultShoppingViewPreferences @Inject constructor(
    private val sources: ActiveSources,
    private val store: ShoppingViewStore,
    private val accounts: SecureAccountStore,
) : ShoppingViewPreferences {

    override val gridView: Flow<Boolean> = sources.source
        .flatMapLatest { source -> source.viewKey()?.let(store::observeGrid) ?: flowOf(false) }
        .catch { emit(false) }

    override suspend fun setGridView(enabled: Boolean) {
        val key = sources.current().viewKey() ?: return
        store.setGrid(key, enabled)
        val known = runCatching { accounts.accounts.first().mapTo(HashSet()) { it.id } }.getOrNull() ?: return
        store.retainOnly(known + DEMO_KEY)
    }

    private fun ActiveSource.viewKey(): String? = when (this) {
        ActiveSource.Demo -> DEMO_KEY
        is ActiveSource.Remote -> accountId
        else -> null
    }

    private companion object {
        /** Demo mode's own key (an account id never has a colon). */
        const val DEMO_KEY = "demo:"
    }
}
