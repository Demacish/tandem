package casa.moller.tandem.core.sync.data

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.server.ServerStatusRepository
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.server.Capabilities
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.server.ServerStatus
import casa.moller.tandem.core.model.server.ServerVersionChange
import casa.moller.tandem.core.network.capabilities.CapabilityResolver
import casa.moller.tandem.core.network.capabilities.TandoorCapabilities
import casa.moller.tandem.core.network.capabilities.TandoorVersionTable
import casa.moller.tandem.core.sync.SyncSchedule
import casa.moller.tandem.core.sync.account.AccountMirror
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.guard.SyncGuard
import casa.moller.tandem.core.sync.platform.SyncClock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Version, compatibility and capabilities of the active server (plan 3.6, feature #2, B18).
 *
 * - The version is the one stored with the account (`ServerAccount.serverVersion`), so safe mode
 *   is right offline and right after a cold start. [refresh] asks the server again (one
 *   `GET /api/server-settings/current/` through the session, which stores a changed version;
 *   `AccountMirror` then reports the change and posts the notification).
 * - Capabilities come from the version and `space/current` (`CapabilityResolver`, at most 3
 *   probes and only for unparseable versions); they are resolved once per account and version
 *   while the app runs ([ensureCapabilities]), and derived from the version alone before that.
 * - The in-app version notice (B18 fallback) is the one `AccountMirror` stored in the settings;
 *   it is offered only for the active account and while its server still runs that version.
 */
@Singleton
internal class DefaultServerStatusRepository @Inject constructor(
    private val sources: ActiveSources,
    private val sessions: SessionRepository,
    private val mirror: AccountMirror,
    private val settings: SettingsStore,
    private val resolver: CapabilityResolver,
    private val guard: SyncGuard,
    private val clock: SyncClock,
) : ServerStatusRepository {
    private val checks = MutableStateFlow<Map<String, ServerCheck>>(emptyMap())
    private val lastCheckMillis = ConcurrentHashMap<String, Long>()

    override fun observeStatus(): Flow<ServerStatus> = combine(sources.source, checks) { source, known -> statusFor(source, known) }
        .distinctUntilChanged()

    override fun observeVersionChanges(): Flow<ServerVersionChange> = mirror.versionChanges

    override fun observeVersionNotice(): Flow<ServerVersionChange?> = combine(
        sources.source,
        settings.settings.map { it.versionNotice }.distinctUntilChanged(),
    ) { source, notice ->
        val remote = source as? ActiveSource.Remote
        val current = remote?.account?.serverVersion
        notice
            ?.takeIf { remote != null && it.accountId == remote.accountId && it.newVersion == current }
            ?.let { ServerVersionChange(it.previousVersion, it.newVersion, TandoorVersionTable.assess(it.newVersion).compatibility) }
            // Only a tested version (an untested one has the safe mode banner).
            ?.takeIf { it.compatibility == ServerCompatibility.SUPPORTED }
    }.distinctUntilChanged()

    override suspend fun markVersionNoticeShown(change: ServerVersionChange) {
        try {
            settings.update { current ->
                val pending = current.versionNotice
                val shown = pending != null && pending.previousVersion == change.previousVersion && pending.newVersion == change.newVersion
                if (shown) current.copy(versionNotice = null) else current
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not saved: the notice may show once more at the next start.
        }
    }

    override suspend fun refresh(force: Boolean): Outcome<ServerStatus> {
        val source = sources.current()
        if (source !is ActiveSource.Remote) {
            val demo = source == ActiveSource.Demo
            return if (demo) Outcome.Success(ServerStatus.Demo) else Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        }
        val accountId = source.accountId
        val last = lastCheckMillis[accountId]
        val now = clock.nowMillis()
        if (!force && last != null && now - last < SyncSchedule.SERVER_CHECK_MIN_INTERVAL.inWholeMilliseconds) {
            return Outcome.Success(statusFor(source, checks.value))
        }
        guard.check(accountId, background = false)?.let { return Outcome.Failure(it) }
        val result = sessions.refreshServerInfo()
        // Also after a failure, so that a broken server is not asked on every resume (plan 4.1).
        lastCheckMillis[accountId] = now
        return when (result) {
            is Outcome.Failure -> result

            is Outcome.Success -> {
                val version = result.value.version
                checks.update { known ->
                    val previous = known[accountId]?.takeIf { it.version == version }
                    known + (accountId to ServerCheck(version, previous?.capabilities, now))
                }
                // The session stores the new version asynchronously; use it here already.
                val current = (sources.source.value as? ActiveSource.Remote ?: source).let {
                    it.copy(account = it.account.copy(serverVersion = version))
                }
                ensureCapabilities(current)
                Outcome.Success(statusFor(current, checks.value))
            }
        }
    }

    /**
     * Resolves the capabilities of [remote]'s server once per version while the app runs (one
     * `space/current` request; probes only for an unparseable version). Never throws.
     */
    suspend fun ensureCapabilities(remote: ActiveSource.Remote) {
        val accountId = remote.accountId
        val version = remote.account.serverVersion
        val known = checks.value[accountId]
        if (known != null && known.version == version && known.capabilities != null) return
        if (guard.check(accountId, background = true) != null) return
        val capabilities = try {
            resolver.resolve(remote.api, version).capabilities
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        checks.update { map ->
            val checkedAt = map[accountId]?.takeIf { it.version == version }?.checkedAtMillis
            map + (accountId to ServerCheck(version, capabilities, checkedAt))
        }
    }

    private fun statusFor(source: ActiveSource, known: Map<String, ServerCheck>): ServerStatus = when (source) {
        ActiveSource.Demo -> ServerStatus.Demo

        is ActiveSource.Remote -> {
            val version = source.account.serverVersion
            val check = known[source.accountId]?.takeIf { it.version == version }
            ServerStatus(
                version = version,
                compatibility = TandoorVersionTable.assess(version).compatibility,
                capabilities = check?.capabilities ?: TandoorCapabilities.fromVersionAndSpace(version, space = null),
                checkedAt = check?.checkedAtMillis?.let { Instant.fromEpochMilliseconds(it) },
            )
        }

        ActiveSource.Loading, ActiveSource.None -> ServerStatus.Unknown
    }

    /** What one check found: the version, its capabilities (null until resolved) and when it was confirmed. */
    private data class ServerCheck(val version: String?, val capabilities: Capabilities?, val checkedAtMillis: Long?)
}
