package casa.moller.tandem.core.datastore

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job

/**
 * DataStore allows one active instance per file. Tests that "restart the app" close every
 * open store (cancel its scope) before opening a new one on the same file.
 */
internal class StoreScopes {
    private val open = mutableListOf<CoroutineScope>()

    fun newScope(): CoroutineScope = CoroutineScope(Dispatchers.IO + Job()).also { open += it }

    suspend fun closeAll() {
        open.forEach { it.coroutineContext.job.cancelAndJoin() }
        open.clear()
    }
}
