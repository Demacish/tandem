package casa.moller.tandem.wear

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.watchlink.MirroredTimer
import casa.moller.tandem.core.watchlink.TimerAction
import casa.moller.tandem.core.watchlink.TimerMirror
import casa.moller.tandem.core.watchlink.WatchCredentials
import casa.moller.tandem.wear.data.CredentialsStore
import casa.moller.tandem.wear.data.WearEntry
import casa.moller.tandem.wear.data.WearSupermarket
import casa.moller.tandem.wear.link.PhoneLink
import casa.moller.tandem.wear.sync.ShoppingRemote
import casa.moller.tandem.wear.sync.SyncScheduler
import casa.moller.tandem.wear.timer.TimerNotifier

/** The phone's pairing as the tests send it (example.com, a fake token). */
internal fun credentials(accountKey: String = "account-1", tokenId: Long = 42, baseUrl: String = "https://tandoor.example.com/") =
    WatchCredentials(
        baseUrl = baseUrl,
        token = "tda_00000000_0000_0000_0000_00000000000$tokenId",
        tokenId = tokenId,
        expiresEpochMillis = 90L * 24 * 3600 * 1000,
        accountKey = accountKey,
        accountLabel = "Anna",
        serverVersion = "2.6.13",
    )

internal fun entry(
    id: Long,
    food: String,
    amount: Double = 0.0,
    unit: String? = null,
    category: Pair<Long, String>? = null,
    checked: Boolean = false,
    order: Int = id.toInt(),
) = WearEntry(
    id = id,
    food = food,
    amount = amount,
    unit = unit,
    categoryId = category?.first,
    categoryName = category?.second,
    checked = checked,
    order = order,
)

/** [CredentialsStore] in memory; [failSave] makes saving fail like a broken Keystore. */
internal class InMemoryCredentialsStore(var stored: WatchCredentials? = null) : CredentialsStore {
    var failSave = false

    override suspend fun load(): WatchCredentials? = stored

    override suspend fun save(credentials: WatchCredentials): Boolean {
        if (failSave) return false
        stored = credentials
        return true
    }

    override suspend fun clear() {
        stored = null
    }
}

/**
 * Tandoor as the watch sees it: the list, the stores, and every request recorded. [failNext]
 * answers the next request with an error instead (one request each).
 */
internal class FakeTandoor {
    val entries = mutableListOf<WearEntry>()
    val supermarkets = mutableListOf<WearSupermarket>()
    val bulkCalls = mutableListOf<Pair<List<Long>, Boolean>>()
    val revoked = mutableListOf<Long>()
    var entryReads = 0
    var supermarketReads = 0
    var offline = false
    val failNext = ArrayDeque<TandemError>()

    /** Every request that reached the server (offline ones never do). */
    val requests: Int
        get() = entryReads + supermarketReads + bulkCalls.size + revoked.size

    val factory = ShoppingRemote.Factory { Remote() }

    private fun <T> answer(block: () -> T): Outcome<T> {
        if (offline) return Outcome.Failure(TandemError.NetworkUnavailable)
        failNext.removeFirstOrNull()?.let { return Outcome.Failure(it) }
        return Outcome.Success(block())
    }

    private inner class Remote : ShoppingRemote {
        override suspend fun entries(): Outcome<List<WearEntry>> = answer {
            entryReads++
            entries.toList()
        }

        override suspend fun supermarkets(): Outcome<List<WearSupermarket>> = answer {
            supermarketReads++
            supermarkets.toList()
        }

        override suspend fun setChecked(ids: List<Long>, checked: Boolean): Outcome<Unit> = answer {
            bulkCalls += ids to checked
            entries.replaceAll { if (it.id in ids) it.copy(checked = checked) else it }
        }

        override suspend fun revoke(tokenId: Long): Outcome<Unit> = answer {
            revoked += tokenId
        }
    }
}

internal class FakeScheduler : SyncScheduler {
    val now = mutableListOf<Boolean>()
    val later = mutableListOf<Long>()

    override fun syncNow(force: Boolean) {
        now += force
    }

    override fun syncLater(delayMillis: Long) {
        later += delayMillis
    }
}

internal class FakePhoneLink(var mirror: TimerMirror? = null, var reachable: Boolean = true) : PhoneLink {
    val actions = mutableListOf<TimerAction>()

    override suspend fun sendTimerAction(action: TimerAction): Boolean {
        if (!reachable) return false
        actions += action
        return true
    }

    override suspend fun requestTimers(): TimerMirror? = if (reachable) mirror else null
}

internal class RecordingNotifier : TimerNotifier {
    val shown = mutableListOf<List<MirroredTimer>>()

    override fun show(timers: List<MirroredTimer>) {
        shown += timers
    }
}
