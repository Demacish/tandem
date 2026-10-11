package casa.moller.tandem.core.data.sync

import casa.moller.tandem.core.model.error.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * Reads the outbox (plan 5.7) for the recovery screen's "Export pending changes" (feature #37):
 * when the app cannot start normally, queued changes (cook log, view log, later shopping) must not
 * be lost, so the user can share them as JSON text and enter them by hand in Tandoor.
 *
 * OPTIONAL binding: the app injects it as `Optional<PendingChangesExporter>` (Hilt
 * `@BindsOptionalOf`), so the recovery screen works before the sync lane provides one. The real
 * implementation lives next to the outbox (core:sync / core:database) and is bound with `@Binds`;
 * nothing else needs to change.
 *
 * - **Threading:** main-safe.
 * - **Content:** only the queued operations (type, target, payload, created at, attempts). Never
 *   tokens, passwords or server credentials (plan 5.5).
 */
interface PendingChangesExporter {

    /** Number of changes waiting in the outbox; 0 when none (or the outbox cannot be read). */
    fun observePendingCount(): Flow<Int>

    /**
     * The pending changes as a pretty-printed JSON document, ready to share as text. Fails with
     * [casa.moller.tandem.core.model.error.TandemError.Unknown] when the outbox cannot be read.
     */
    suspend fun exportAsJson(): Outcome<String>
}
