package casa.moller.tandem.core.model.diagnostics

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Settings → Diagnostics (plan 6.2 "Övrigt: diagnostik", Phase 1 "Klart när": cold start under
 * 1.5 s and a full sync of about 300 recipes under 60 s, read from here).
 *
 * @property appVersion version name, e.g. `0.2.0` or `0.2.0-dev`.
 * @property appVersionCode version code.
 * @property distribution `sideload` or `play`.
 * @property androidSdk `Build.VERSION.SDK_INT`.
 * @property serverVersion the active account's Tandoor version, null without an account or in demo mode.
 * @property lastColdStart the most recent measured cold start, null until one was measured.
 * @property lastFullSync the most recent complete sync of the recipe index, null until one ran.
 */
data class DiagnosticsSnapshot(
    val appVersion: String,
    val appVersionCode: Long,
    val distribution: String,
    val androidSdk: Int,
    val serverVersion: String? = null,
    val lastColdStart: ColdStartTiming? = null,
    val lastFullSync: FullSyncTiming? = null,
)

/**
 * One cold start: process start until the first frame with content (`reportFullyDrawn`).
 *
 * @property duration how long it took.
 * @property measuredAt when it was measured.
 */
data class ColdStartTiming(val duration: Duration, val measuredAt: Instant)

/**
 * One complete sync (the first sync after login or a full re-read of the recipe index).
 *
 * @property duration from start to DONE.
 * @property recipeCount recipes in the index afterwards.
 * @property finishedAt when it finished.
 */
data class FullSyncTiming(val duration: Duration, val recipeCount: Int, val finishedAt: Instant)

/**
 * "Exportera logg": a sanitized diagnostics text the user shares themselves (nothing is sent
 * automatically). Never contains tokens, passwords, server addresses, user names or e-mail
 * addresses; the same redaction as the crash log (core:common `CrashLogFormatter.redact`).
 *
 * @property text the whole log as plain text, ready for `ACTION_SEND` text/plain.
 * @property fileUri a `content://` URI of the same text as a file (FileProvider), when the
 *   implementation wrote one; null when only [text] is available.
 * @property fileName a suggested file name, e.g. `tandem-diagnostics-2026-10-16.txt`.
 */
data class LogExport(val text: String, val fileUri: String? = null, val fileName: String)
