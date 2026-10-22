package casa.moller.tandem.core.sync.data

import android.os.Build
import casa.moller.tandem.core.common.AppBuildInfo
import casa.moller.tandem.core.common.crash.CrashLogFormatter
import casa.moller.tandem.core.common.crash.CrashLogStore
import casa.moller.tandem.core.data.diagnostics.DiagnosticsRepository
import casa.moller.tandem.core.data.server.ServerStatusRepository
import casa.moller.tandem.core.data.sync.SyncRepository
import casa.moller.tandem.core.data.timer.TimerController
import casa.moller.tandem.core.database.DatabaseKind
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.datastore.DiagnosticsStore
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.model.diagnostics.ColdStartTiming
import casa.moller.tandem.core.model.diagnostics.DiagnosticsSnapshot
import casa.moller.tandem.core.model.diagnostics.FullSyncTiming
import casa.moller.tandem.core.model.diagnostics.LogExport
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.server.ServerStatus
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.sync.SyncStatus
import casa.moller.tandem.core.model.timer.TimerCapabilities
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Settings → Diagnostics (plan 6.2 "Övrigt: diagnostik"): the build, the server version, the
 * measured timings ([DiagnosticsStore]: cold start from `:app`, full syncs from [SyncEngine]) and
 * "Export log".
 *
 * The export is built only from values the app chose to write, never from free text it received:
 * versions, the capability map, timer capabilities, settings, the sync status, the outbox as
 * counts and per-row operation/state/attempts (no payloads, so no comments or recipe content),
 * error kinds, the timings and the newest crash log (which was redacted when it was written).
 * Every remaining free-text piece (error details, an outbox row's last error) goes through
 * [CrashLogFormatter.redact]. No server address, user name, space name, token or e-mail address.
 */
@Singleton
internal class DefaultDiagnosticsRepository @Inject constructor(
    private val buildInfo: AppBuildInfo,
    private val store: DiagnosticsStore,
    private val serverStatus: ServerStatusRepository,
    private val syncRepository: SyncRepository,
    private val timers: TimerController,
    private val settings: SettingsStore,
    private val databases: TandemDatabaseFactory,
    private val crashLogs: CrashLogStore,
    private val clock: Clock,
) : DiagnosticsRepository {

    override fun observeDiagnostics(): Flow<DiagnosticsSnapshot> = combine(store.timings, serverStatus.observeStatus()) { timings, server ->
        snapshotOf(server, timings.lastColdStart, timings.lastFullSync)
    }.distinctUntilChanged()

    override suspend fun recordColdStart(duration: Duration) {
        store.recordColdStart(ColdStartTiming(duration, clock.now()))
    }

    override suspend fun recordFullSync(duration: Duration, recipeCount: Int) {
        store.recordFullSync(FullSyncTiming(duration, recipeCount, clock.now()))
    }

    override suspend fun exportLog(): Outcome<LogExport> = try {
        val now = clock.now()
        val text = withContext(Dispatchers.IO) { buildLog(now) }
        val day = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
        Outcome.Success(LogExport(text = text, fileName = "tandem-diagnostics-$day.txt"))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Failure(TandemError.Unknown(EXPORT_FAILED))
    }

    private fun snapshotOf(server: ServerStatus, coldStart: ColdStartTiming?, fullSync: FullSyncTiming?) = DiagnosticsSnapshot(
        appVersion = buildInfo.versionName,
        appVersionCode = buildInfo.versionCode,
        distribution = buildInfo.distribution,
        androidSdk = buildInfo.sdkInt,
        serverVersion = server.version.takeUnless { server.isDemo },
        lastColdStart = coldStart,
        lastFullSync = fullSync,
    )

    private suspend fun buildLog(now: Instant): String {
        val server = serverStatus.observeStatus().first()
        val timings = store.timings.first()
        val appSettings = settings.settings.first()
        val sync = syncRepository.status.value
        val timer = timers.capabilities.value
        return buildString {
            appendLine("Tandem diagnostics")
            appendLine("Created: $now")
            appendLine()
            section("App") {
                line("Version", "${buildInfo.versionName} (${buildInfo.versionCode})")
                line("Distribution", buildInfo.distribution)
                line("Debug build", buildInfo.isDebug.yesNo())
                line("Android", "${Build.VERSION.RELEASE.orEmpty()} (SDK ${buildInfo.sdkInt})")
                line("Device", "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim())
            }
            section("Server") { appendServer(server) }
            section("Timings") { appendTimings(timings.lastColdStart, timings.lastFullSync) }
            section("Settings") { appendSettings(appSettings) }
            section("Timers") { appendTimers(timer, timers.timers.value.size) }
            section("Sync") { appendSync(sync) }
            section("Outbox") { appendOutbox() }
            section("Last crash") { appendCrash() }
        }
    }

    private fun StringBuilder.appendServer(server: ServerStatus) {
        when {
            server.isDemo -> line("Mode", "demo (no server)")

            else -> {
                line("Tandoor", server.version ?: "unknown")
                line("Compatibility", server.compatibility.name)
                line("Safe mode", server.safeMode.yesNo())
                line("Capabilities", server.capabilities.features.map { it.name }.sorted().joinToString().ifEmpty { "none" })
                line("Checked", server.checkedAt?.toString() ?: "never")
            }
        }
    }

    private fun StringBuilder.appendTimings(coldStart: ColdStartTiming?, fullSync: FullSyncTiming?) {
        line(
            "Cold start",
            coldStart?.let { "${it.duration.inWholeMilliseconds} ms at ${it.measuredAt} (target < $COLD_START_TARGET_MS ms)" }
                ?: "not measured",
        )
        line(
            "Full sync",
            fullSync?.let {
                "${it.duration.inWholeMilliseconds} ms, ${it.recipeCount} recipes, at ${it.finishedAt} " +
                    "(target < $FULL_SYNC_TARGET_MS ms for about 300 recipes)"
            } ?: "not measured",
        )
    }

    private fun StringBuilder.appendSettings(settings: AppSettings) {
        line("Theme", settings.themeMode.name)
        line("Dynamic colour", settings.dynamicColor.yesNo())
        line("Cook mode text size", settings.cookTextScale.toString())
        line("Cook mode always dark", settings.cookModeAlwaysDark.yesNo())
        line("Timers", settings.timerMode.name)
        line("Sync interval", settings.syncInterval.name)
        line("Download all recipes", settings.downloadAllRecipes.yesNo())
        line("Demo mode", settings.demoMode.yesNo())
    }

    private fun StringBuilder.appendTimers(capabilities: TimerCapabilities, count: Int) {
        line("Notifications allowed", capabilities.notificationsAllowed.yesNo())
        line("Notification permission asked", capabilities.notificationPermissionAsked.yesNo())
        line("Alarm channel on", capabilities.alarmChannelEnabled.yesNo())
        line("Exact alarms allowed", capabilities.exactAlarmsAllowed.yesNo())
        line("Exact alarms requestable", capabilities.exactAlarmsRequestable.yesNo())
        line("Full-screen alarm allowed", capabilities.fullScreenAlarmAllowed.yesNo())
        line("Clock app available", capabilities.clockAppAvailable.yesNo())
        line("Timers now", count.toString())
    }

    private fun StringBuilder.appendSync(sync: SyncStatus) {
        line("Running", sync.running.yesNo() + (sync.progress?.let { " (${it.phase.name} ${it.done}/${it.total ?: "?"})" } ?: ""))
        line("Last success", sync.lastSuccess?.toString() ?: "never")
        line("Last error", sync.lastError?.let(::describe) ?: "none")
        line("Paused", sync.pause?.name ?: "no")
        line("Pending changes", sync.pendingChanges.toString())
    }

    private suspend fun StringBuilder.appendOutbox() {
        val database = databases.get(DatabaseKind.REAL)
        // Every account with rows (also rows of a removed account that are still waiting).
        val accounts = database.accountDao().getStoredAccountIds()
        val rows = accounts.flatMap { database.outboxDao().observeAll(it).first() }
        if (rows.isEmpty()) {
            line("Rows", "0")
            return
        }
        rows.groupingBy { "${it.op} ${it.entity} ${it.state.name}" }.eachCount().toSortedMap().forEach { (kind, count) ->
            line(kind, count.toString())
        }
        rows.filter { it.lastError != null || it.attempts > 0 }.takeLast(MAX_OUTBOX_ROWS).forEach { row ->
            val error = row.lastError?.let { CrashLogFormatter.redact(it) } ?: "-"
            line(
                "Row ${row.id}",
                "${row.op} ${row.entity}, ${row.state.name}, attempts ${row.attempts}, " +
                    "created ${Instant.fromEpochMilliseconds(row.createdAtEpochMillis)}, last error: $error",
            )
        }
    }

    private fun StringBuilder.appendCrash() {
        val newest = crashLogs.read().maxByOrNull { it.timeMillis }
        if (newest == null) {
            appendLine("none")
        } else {
            // Redacted when it was written (CrashLogHandler); the same text "Share" offers on the card.
            appendLine(newest.text.trimEnd())
        }
    }

    private inline fun StringBuilder.section(title: String, block: StringBuilder.() -> Unit) {
        appendLine("== $title")
        block()
        appendLine()
    }

    private fun StringBuilder.line(label: String, value: String) {
        appendLine("$label: $value")
    }

    private fun Boolean.yesNo(): String = if (this) "yes" else "no"

    internal companion object {
        const val EXPORT_FAILED: String = "tandem.diagnostics.export"
        const val MAX_OUTBOX_ROWS: Int = 20
        const val COLD_START_TARGET_MS: Long = 1_500
        const val FULL_SYNC_TARGET_MS: Long = 60_000

        /** An error as a stable kind (never a server's free text); details are redacted. */
        fun describe(error: TandemError): String = when (error) {
            TandemError.NetworkUnavailable -> "NetworkUnavailable"
            TandemError.UnknownHost -> "UnknownHost"
            TandemError.Timeout -> "Timeout"
            TandemError.Blocked -> "Blocked"
            TandemError.NotFound -> "NotFound"
            is TandemError.TlsError -> "TlsError(${error.kind.name})"
            is TandemError.InvalidUrl -> "InvalidUrl(${error.reason.name})"
            is TandemError.NotTandoor -> "NotTandoor(${error.reason.name}, ${error.httpStatus ?: "-"})"
            is TandemError.Unauthorized -> "Unauthorized(${error.source.name})"
            is TandemError.BadRequest -> "BadRequest(fields: ${error.fieldErrors.keys.sorted().joinToString().ifEmpty { "-" }})"
            is TandemError.Throttled -> "Throttled(retry after ${error.retryAfter ?: "-"})"
            is TandemError.ServerError -> "ServerError(${error.code})"
            is TandemError.Decode -> "Decode(${error.detail?.let { CrashLogFormatter.redact(it) } ?: "-"})"
            is TandemError.Unknown -> "Unknown(${error.detail?.let { CrashLogFormatter.redact(it) } ?: "-"})"
        }
    }
}
