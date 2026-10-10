package casa.moller.tandem.core.data.server

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.server.ServerStatus
import casa.moller.tandem.core.model.server.ServerVersionChange
import kotlinx.coroutines.flow.Flow

/**
 * Version, compatibility, capabilities and safe mode of the active server (plan 3.6, feature #2).
 *
 * - **Threading:** main-safe.
 * - **Offline:** [observeStatus] emits the last known status (stored with the account), so safe
 *   mode and capabilities stay correct without a connection.
 * - **Requests:** the version check is a single `GET /api/server-settings/current/`; capability
 *   probes are at most 3 per app start and never hit paths that may answer 404 (plan 4.1).
 */
interface ServerStatusRepository {

    /**
     * The current status: [ServerStatus.Unknown] before the first check, [ServerStatus.Demo] in
     * demo mode, otherwise the last checked version with its compatibility and capabilities.
     */
    fun observeStatus(): Flow<ServerStatus>

    /**
     * Emits when a check finds a different version than before (plan B18: a notice, and safe mode
     * if the new version is untested). Not replayed: only changes found while collecting.
     */
    fun observeVersionChanges(): Flow<ServerVersionChange>

    /**
     * Checks the server now. Without [force], a check younger than 15 minutes is reused (plan
     * 3.6 point 3) and the call succeeds without a request. Fails with the network or server
     * error; the stored status is then kept.
     */
    suspend fun refresh(force: Boolean = false): Outcome<ServerStatus>
}
