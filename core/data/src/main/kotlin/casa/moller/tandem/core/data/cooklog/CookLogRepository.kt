package casa.moller.tandem.core.data.cooklog

import casa.moller.tandem.core.model.cooklog.CookLogDraft
import casa.moller.tandem.core.model.error.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * "Klart!" → Tandoor's cook log (feature #8), offline first (plan 5.3/5.7): the entry is written
 * locally at once (the recipe's "times cooked" and "last cooked" update on screen) and queued in
 * the outbox as `COOK_LOG` (`POST /api/cook-log/`, `created_at` = [CookLogDraft.cookedAt]); the
 * sync sends it when it can. The real implementation lives in core:sync next to the outbox.
 *
 * - **Threading:** main-safe.
 * - **Outbox rules** (same as VIEW_LOG): safe mode pauses the row, 404 (recipe gone) drops it,
 *   400 marks it FAILED (shown on the recovery screen's export), 401/403/429 and the circuit
 *   breaker only pause. Never merged: two "Klart!" are two log entries.
 * - **Demo mode:** stored in the demo database only, never sent.
 * - **Account:** the entry belongs to the account active when it was logged; logging out deletes
 *   unsent entries with the account's data (the recovery screen can export them first).
 */
interface CookLogRepository {

    /**
     * Logs [draft]. Succeeds offline. Fails with `DataProblem.NO_ACCOUNT` when there is neither
     * an account nor demo mode, or with [casa.moller.tandem.core.model.error.TandemError.Unknown]
     * when the local database cannot be written.
     */
    suspend fun logCooked(draft: CookLogDraft): Outcome<Unit>

    /** Cook-log entries of the active account still waiting in the outbox (0 when none). */
    fun observePendingCount(): Flow<Int>
}
