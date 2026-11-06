package casa.moller.tandem.core.data.sync

/**
 * Hook for imports (plan 4.1): while an AI or URL import runs, the background sync and the
 * detail prefetch pause, so that the server's other worker thread stays free for what the user
 * is doing. Requests the user starts (opening a recipe, pull to refresh) still go through.
 *
 * `RemoteRecipeImportRepository` wraps every server call of an import in [whileImporting].
 *
 * - **Threading:** main-safe; [block] runs in the caller's context.
 */
interface ImportGate {
    /** Runs [block] with the background sync paused; the pause ends when [block] returns or throws. */
    suspend fun <T> whileImporting(block: suspend () -> T): T
}
