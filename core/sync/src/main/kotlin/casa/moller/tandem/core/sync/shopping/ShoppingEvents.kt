package casa.moller.tandem.core.sync.shopping

import casa.moller.tandem.core.model.shopping.ShoppingChange
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One change of another household member, for the account that noticed it. */
internal data class AccountShoppingChange(val accountId: String, val change: ShoppingChange)

/**
 * Changes other household members made, as the reads notice them (plan 6.2 "Anna lade till
 * Mjölk"), for `ShoppingRepository.observeChanges`. Hot and not replayed.
 */
@Singleton
internal class ShoppingChangeBus @Inject constructor() {
    private val mutable = MutableSharedFlow<AccountShoppingChange>(extraBufferCapacity = BUFFER)

    val changes: SharedFlow<AccountShoppingChange> = mutable.asSharedFlow()

    fun emit(accountId: String, change: ShoppingChange) {
        mutable.tryEmit(AccountShoppingChange(accountId, change))
    }

    private companion object {
        const val BUFFER = 64
    }
}

/**
 * A queued change that was dropped instead of sent (plan 3.5, 5.4: "raderat någon annanstans").
 *
 * @property entity the outbox entity (`shopping_entry` …).
 * @property op the outbox operation.
 * @property reason [DroppedChanges.DELETED_ELSEWHERE] (seen missing in a full read before sending:
 *   no request) or [DroppedChanges.NOT_FOUND] (the server answered 404; not counted by the
 *   circuit breaker).
 */
internal data class DroppedChange(val entity: String, val op: String, val serverId: Long?, val reason: String, val atMillis: Long)

/**
 * The reasons for dropped shopping changes, newest last (at most [LIMIT]); diagnostics and tests
 * read them. Kept in memory: the change itself is gone, only why it went is recorded.
 */
@Singleton
internal class DroppedChanges @Inject constructor() {
    private val mutable = MutableStateFlow<List<DroppedChange>>(emptyList())

    val recent: StateFlow<List<DroppedChange>> = mutable.asStateFlow()

    fun record(change: DroppedChange) {
        mutable.update { (it + change).takeLast(LIMIT) }
    }

    companion object {
        const val DELETED_ELSEWHERE: String = "deleted-elsewhere"
        const val NOT_FOUND: String = "not-found"
        const val LIMIT: Int = 50
    }
}
