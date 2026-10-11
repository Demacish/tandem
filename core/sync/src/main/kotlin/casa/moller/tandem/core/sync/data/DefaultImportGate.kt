package casa.moller.tandem.core.sync.data

import casa.moller.tandem.core.data.sync.ImportGate
import casa.moller.tandem.core.sync.guard.SyncGuard
import javax.inject.Inject

/** [ImportGate] on [SyncGuard]: background requests wait while [whileImporting] runs (plan 4.1). */
internal class DefaultImportGate @Inject constructor(private val guard: SyncGuard) : ImportGate {
    override suspend fun <T> whileImporting(block: suspend () -> T): T {
        guard.importStarted()
        try {
            return block()
        } finally {
            guard.importFinished()
        }
    }
}
