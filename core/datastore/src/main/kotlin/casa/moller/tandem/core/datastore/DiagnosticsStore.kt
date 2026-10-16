package casa.moller.tandem.core.datastore

import casa.moller.tandem.core.model.diagnostics.ColdStartTiming
import casa.moller.tandem.core.model.diagnostics.FullSyncTiming
import kotlinx.coroutines.flow.Flow

/**
 * The latest measured timings for Settings → Diagnostics (Phase 1 "Klart när": cold start under
 * 1.5 s, a full sync of about 300 recipes under 60 s), in their own small Preferences DataStore
 * file ([DatastoreFiles.DIAGNOSTICS]): measurements, not settings, and nothing personal.
 *
 * Diagnostics must never break what they measure: reading falls back to [DiagnosticsTimings.None]
 * and writing never throws (a failed write is dropped).
 */
interface DiagnosticsStore {
    /** The latest timings; [DiagnosticsTimings.None] until something was measured. */
    val timings: Flow<DiagnosticsTimings>

    /** Replaces the stored cold start. */
    suspend fun recordColdStart(timing: ColdStartTiming)

    /** Replaces the stored full sync. */
    suspend fun recordFullSync(timing: FullSyncTiming)
}

/** The stored timings; null where nothing was measured yet. */
data class DiagnosticsTimings(val lastColdStart: ColdStartTiming? = null, val lastFullSync: FullSyncTiming? = null) {
    companion object {
        val None: DiagnosticsTimings = DiagnosticsTimings()
    }
}
