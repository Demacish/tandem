package casa.moller.tandem.core.model.diagnostics

import casa.moller.tandem.core.model.sync.SyncPause
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
 * @property syncHealth (Phase 2) whether the sync is held back ("blockerad") and what waits in the
 *   outbox per kind of change; empty without an account and in demo mode.
 */
data class DiagnosticsSnapshot(
    val appVersion: String,
    val appVersionCode: Long,
    val distribution: String,
    val androidSdk: Int,
    val serverVersion: String? = null,
    val lastColdStart: ColdStartTiming? = null,
    val lastFullSync: FullSyncTiming? = null,
    val syncHealth: SyncHealth = SyncHealth(),
)

/**
 * Phase 2 (koppling lane): "blockerad"-diagnostik (plan §10 Fas 2, 4.1, 5.4) under Mer →
 * Diagnostik: why the sync is held back and until when, the 404/400 answers the circuit breaker
 * counts, and the outbox per kind of change with its last error. Only kinds and counts: no
 * payloads, names or server text (plan 5.5).
 *
 * @property pause why requests are held back now, null when they go out.
 * @property pausedUntil when a time-limited pause ends (blocked, 429, circuit breaker), else null.
 * @property recentClientErrors 404/400 answers in the circuit breaker's window (it opens at
 *   [CLIENT_ERROR_LIMIT]).
 * @property outbox the waiting changes per kind, kinds with rows only, in [OutboxKind] order.
 */
data class SyncHealth(
    val pause: SyncPause? = null,
    val pausedUntil: Instant? = null,
    val recentClientErrors: Int = 0,
    val outbox: List<OutboxSummary> = emptyList(),
) {
    /** Changes that wait to be sent (not failed). */
    val waiting: Int
        get() = outbox.sumOf { it.waiting }

    /** Changes the server refused for good (400): kept for "Exportera väntande ändringar". */
    val failed: Int
        get() = outbox.sumOf { it.failed }

    companion object {
        /** 404/400 answers within 100 s that open the circuit breaker (plan 4.1: half of CrowdSec's 10). */
        const val CLIENT_ERROR_LIMIT: Int = 5
    }
}

/**
 * The outbox rows of one [kind].
 *
 * @property waiting rows still to send (pending, paused by safe mode, being sent).
 * @property failed rows the server refused (FAILED), never sent again by themselves.
 * @property lastProblem why the newest row with an error failed last time, null when none did.
 */
data class OutboxSummary(val kind: OutboxKind, val waiting: Int, val failed: Int, val lastProblem: OutboxProblem? = null)

/** What a queued change is about, for people (never the raw `op`/`entity`). */
enum class OutboxKind {
    SHOPPING_ENTRY,
    SHOPPING_RECIPE,
    MEAL_PLAN,
    MEAL_TYPE,
    RECIPE_BOOK,
    RECIPE_BOOK_ENTRY,
    WANT_TO_COOK,
    FOOD,
    AISLE_ORDER,
    PREFERENCES,
    COOK_LOG,
    VIEW_LOG,
    OTHER,
}

/** Why a queued change failed last time (from the row's stable error code). */
enum class OutboxProblem {
    OFFLINE,
    TIMEOUT,
    NOT_FOUND,
    BAD_REQUEST,
    UNAUTHORIZED,
    BLOCKED,
    THROTTLED,
    SERVER_ERROR,
    CIRCUIT_BREAKER,
    CONFLICT,
    OTHER,
}

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
