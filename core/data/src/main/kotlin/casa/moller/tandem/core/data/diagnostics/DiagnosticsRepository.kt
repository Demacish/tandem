package casa.moller.tandem.core.data.diagnostics

import casa.moller.tandem.core.model.diagnostics.DiagnosticsSnapshot
import casa.moller.tandem.core.model.diagnostics.LogExport
import casa.moller.tandem.core.model.error.Outcome
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow

/**
 * Settings → Diagnostics (plan 6.2 "Övrigt: diagnostik (Exportera logg)") and the measurements
 * of Phase 1 "Klart när" (cold start < 1.5 s, full sync of ~300 recipes < 60 s).
 *
 * - **Threading:** main-safe.
 * - **Privacy (plan 5.5):** everything stays on the device until the user shares [exportLog]'s
 *   text. The export never contains tokens, passwords, server addresses, user names or e-mail
 *   addresses (redacted like the crash log).
 * - **Who records:** `:app` records the cold start (`reportFullyDrawn` of the first screen with
 *   content); core:sync records every complete sync (first sync and full index re-reads).
 */
interface DiagnosticsRepository {

    /** Versions and the latest timings; re-emitted when a new timing is recorded. */
    fun observeDiagnostics(): Flow<DiagnosticsSnapshot>

    /** Stores the cold start that just finished (the latest one replaces the previous). */
    suspend fun recordColdStart(duration: Duration)

    /** Stores a complete sync that just finished with [recipeCount] recipes in the index. */
    suspend fun recordFullSync(duration: Duration, recipeCount: Int)

    /**
     * Builds the sanitized diagnostics log (versions, timings, sync status and recent sync/outbox
     * events without content, the last crash log if any). Fails with
     * [casa.moller.tandem.core.model.error.TandemError.Unknown] when it cannot be written.
     */
    suspend fun exportLog(): Outcome<LogExport>
}
