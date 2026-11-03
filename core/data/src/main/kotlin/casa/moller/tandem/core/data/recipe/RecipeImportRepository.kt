package casa.moller.tandem.core.data.recipe

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.recipe.RecipeImportResult
import casa.moller.tandem.core.model.recipe.RecipeImportSource
import kotlinx.coroutines.flow.Flow

/**
 * Fas 3 (import lane): turning a shared link, a text, a photo or a PDF into a recipe draft
 * (plan §8.9 "Importera recept"; docs/BYGGE.md "Fas 3 – import"). The parsing happens on the
 * server, so this is an ONLINE call and never an outbox row: a link or text goes to
 * `POST /api/recipe-from-source/` (Tandoor's scraper and site plugins), a file to
 * `POST /api/ai-import/` with the space's default AI provider (only when the server offers
 * `ServerFeature.AI_IMPORT`). The answer is a [RecipeImportResult] whose draft the editor
 * opens; nothing is stored until the user saves it there (`RecipeEditRepository.createRecipe`,
 * offline first). Guests get `DataProblem.READ_ONLY`; demo mode answers
 * [RecipeImportProblem.DEMO_MODE] (there is no server to parse with). Implemented in :core:sync.
 */
interface RecipeImportRepository {
    /** Whether a photo or PDF can be imported: the server's space has an AI provider. Never true in demo mode. */
    fun observeAiImportAvailable(): Flow<Boolean>

    /**
     * Asks the server to read [source]. Fails with the network or server error, with
     * `DataProblem.READ_ONLY` for a guest, or with a [RecipeImportProblem] (`TandemError.Unknown`).
     * Never retried by itself (an AI import costs the space credits).
     */
    suspend fun import(source: RecipeImportSource): Outcome<RecipeImportResult>
}

/** Fas 3: why an import gave no recipe (`TandemError.Unknown(detail)`). */
enum class RecipeImportProblem(val detail: String) {
    /** Nothing to import: an empty link or text, an empty file. */
    EMPTY_INPUT("tandem.import.empty-input"),

    /** The server found no recipe in the link or text (`error: true` with its message, or an empty name). */
    NOTHING_FOUND("tandem.import.nothing-found"),

    /** A photo or PDF, but the space has no AI provider (or the server refused the AI import). */
    AI_UNAVAILABLE("tandem.import.ai-unavailable"),

    /** The file is larger than the app sends ([MAX_FILE_BYTES]). */
    FILE_TOO_LARGE("tandem.import.file-too-large"),

    /** Demo mode has no server to parse with. */
    DEMO_MODE("tandem.import.demo-mode"),
    ;

    fun toError(): TandemError = TandemError.Unknown(detail)

    companion object {
        /** The largest file the app sends to the AI import (a scaled photo is far smaller; a PDF of a few pages fits). */
        const val MAX_FILE_BYTES: Int = 10 * 1024 * 1024

        /** The [RecipeImportProblem] this error stands for, or null. */
        fun of(error: TandemError): RecipeImportProblem? = entries.firstOrNull { (error as? TandemError.Unknown)?.detail == it.detail }
    }
}
