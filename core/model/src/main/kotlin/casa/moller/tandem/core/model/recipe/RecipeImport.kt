package casa.moller.tandem.core.model.recipe

/**
 * Fas 3 (import lane): what Tandoor made of a shared link, a pasted text, a photo or a PDF
 * (`POST /api/recipe-from-source/`, `POST /api/ai-import/`; plan §8.9 "Importera recept"). The
 * recipe is NOT created by the import: [draft] opens in the recipe editor, where the user checks
 * it and saves it like a recipe written by hand (offline first through `RecipeEditRepository`).
 *
 * @property draft the recipe as the server parsed it: name, description, servings, times, the
 *   source link, keywords by name (the ones the source carried), steps with ingredients by food
 *   and unit NAME, and [RecipeDraft.imageUrl] for the picture the source offered.
 * @property imageUrls every picture the source offered (the first is the draft's); the editor
 *   lets the user pick another.
 * @property duplicates recipes of the space that already have this name or source (Tandoor's
 *   check): the import screen warns before the editor opens.
 */
data class RecipeImportResult(
    val draft: RecipeDraft,
    val imageUrls: List<String> = emptyList(),
    val duplicates: List<ImportDuplicate> = emptyList(),
)

/** A recipe the space already has that looks like the imported one. */
data class ImportDuplicate(val id: Long, val name: String)

/** What was handed to the import: a link, a text, or a file (photo or PDF, through the AI import). */
sealed interface RecipeImportSource {
    /** A web address; Tandoor fetches and scrapes the page (schema.org, its site plugins). */
    data class Link(val url: String) : RecipeImportSource

    /** Pasted or shared text (a recipe copied from somewhere, or raw HTML). */
    data class Text(val text: String) : RecipeImportSource

    /**
     * A photo (JPEG, scaled by the caller) or a PDF, read by the space's AI provider
     * (`ServerFeature.AI_IMPORT`). [fileName] is only a label for the upload.
     */
    class File(val bytes: ByteArray, val mimeType: String, val fileName: String) : RecipeImportSource {
        override fun toString(): String = "File($fileName, $mimeType, ${bytes.size} bytes)"
    }
}
