package casa.moller.tandem.core.sync.recipeimport

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.recipe.RecipeImportResult
import casa.moller.tandem.core.sync.platform.AppVisibility
import casa.moller.tandem.core.sync.platform.ImportDoneNotifier
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/** Keeps the process running while an AI import waits for its answer (`ImportKeepAliveWorker`). */
internal fun interface ImportKeepAlive {
    fun request()
}

/**
 * Plan §8.7 "Import klar": an AI import takes 20–30 s, so the user may leave the app meanwhile.
 * When it ends with the app away from the screen the notification says so, also when it failed:
 * the result waits on the import screen either way. [ImportKeepAlive] is what lets it end at all:
 * Android freezes a cached process within seconds, and the answer would wait until the user came
 * back (docs/BYGGE.md "Notis när en AI-import är klar").
 *
 * An import that is cancelled (the user left the import screen) throws out of here: no notification.
 */
@Singleton
internal class AiImportWatch @Inject constructor(
    private val visibility: AppVisibility,
    private val notifier: ImportDoneNotifier,
    private val keepAlive: ImportKeepAlive,
) {
    private val running = MutableStateFlow(0)

    /**
     * Runs [read]; [stillWanted] is false when the answer no longer belongs to the active account.
     * [keep] stores a recipe before anyone is told: "klart att granska" holds also if the process dies
     * right after (`KeptImportStore`).
     */
    suspend fun whileRunning(
        stillWanted: suspend () -> Boolean,
        keep: suspend (RecipeImportResult) -> Unit = {},
        read: suspend () -> Outcome<RecipeImportResult>,
    ): Outcome<RecipeImportResult> {
        running.update { it + 1 }
        try {
            keepAlive.request()
            val outcome = read()
            val wanted = stillWanted()
            if (wanted && outcome is Outcome.Success) keep(outcome.value)
            if (wanted && !visibility.isVisible()) {
                when (outcome) {
                    is Outcome.Success -> notifier.finished(outcome.value.draft.name)
                    is Outcome.Failure -> notifier.failed()
                }
            }
            return outcome
        } finally {
            running.update { it - 1 }
        }
    }

    /** The answer is on the screen: after a process death nothing else takes the notification away. */
    fun reviewed() = notifier.dismiss()

    /** Returns when no AI import runs (at once after a process restart: then nothing is left to wait for). */
    suspend fun awaitNoneRunning() {
        running.first { it == 0 }
    }
}
