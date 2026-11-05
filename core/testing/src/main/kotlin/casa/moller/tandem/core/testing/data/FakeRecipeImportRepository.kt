package casa.moller.tandem.core.testing.data

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.recipe.RecipeImportProblem
import casa.moller.tandem.core.data.recipe.RecipeImportRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.recipe.DraftIngredient
import casa.moller.tandem.core.model.recipe.DraftKeyword
import casa.moller.tandem.core.model.recipe.DraftStep
import casa.moller.tandem.core.model.recipe.ImportDuplicate
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.model.recipe.RecipeImportResult
import casa.moller.tandem.core.model.recipe.RecipeImportSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

// Fas 3 (import lane): the fake of the import contract in core:data (docs/BYGGE.md "Fas 3 –
// import"). In memory and deterministic: every source answers [result] unless [failure] or
// [readOnly] is set; the sources handed in are kept in [imported].

/** In-memory [RecipeImportRepository]: answers [result] for any source. */
class FakeRecipeImportRepository(
    var result: RecipeImportResult = SampleImports.pancakes,
    aiAvailable: Boolean = true,
    var readOnly: Boolean = false,
) : RecipeImportRepository {
    val aiAvailable = MutableStateFlow(aiAvailable)
    val imported = mutableListOf<RecipeImportSource>()
    var failure: TandemError? = null

    override fun observeAiImportAvailable(): Flow<Boolean> = aiAvailable

    override suspend fun import(source: RecipeImportSource): Outcome<RecipeImportResult> {
        imported += source
        if (readOnly) return Outcome.Failure(DataProblem.READ_ONLY.toError())
        failure?.let { return Outcome.Failure(it) }
        if (source is RecipeImportSource.Link) linkFailure?.let { return Outcome.Failure(it) }
        val empty = when (source) {
            is RecipeImportSource.Link -> source.url.isBlank()
            is RecipeImportSource.Text -> source.text.isBlank()
            is RecipeImportSource.Page -> source.html.isBlank()
            is RecipeImportSource.File -> source.bytes.isEmpty()
        }
        if (empty) return Outcome.Failure(RecipeImportProblem.EMPTY_INPUT.toError())
        return Outcome.Success(pageResult?.takeIf { source is RecipeImportSource.Page } ?: result)
    }

    /** What a [RecipeImportSource.Link] fails with (a site that blocks the server, #38); pages still answer. */
    var linkFailure: TandemError? = null

    /** What a [RecipeImportSource.Page] answers (#38), when it differs from [result]. */
    var pageResult: RecipeImportResult? = null

    /** The drafts handed to [sortIngredientsIntoSteps] (#40); [sorted] is its answer, [sortFailure] its error. */
    val sortRequests = mutableListOf<RecipeDraft>()
    var sorted: RecipeDraft? = null
    var sortFailure: TandemError? = null

    override suspend fun sortIngredientsIntoSteps(draft: RecipeDraft): Outcome<RecipeDraft> {
        sortRequests += draft
        if (readOnly) return Outcome.Failure(DataProblem.READ_ONLY.toError())
        sortFailure?.let { return Outcome.Failure(it) }
        return Outcome.Success(sorted ?: draft)
    }
}

/** Sample import answers for the tests and the screenshots. */
object SampleImports {
    val pancakes: RecipeImportResult = RecipeImportResult(
        draft = RecipeDraft(
            name = "Pannkakor",
            description = "Klassiska tunna pannkakor.",
            servings = 4,
            servingsText = "portioner",
            workingMinutes = 25,
            sourceUrl = "https://example.com/recept/pannkakor",
            keywords = listOf(DraftKeyword(name = "Mellanmål"), DraftKeyword(name = "Barnfavorit")),
            steps = listOf(
                DraftStep(
                    instruction = "Vispa ihop mjöl, salt och hälften av mjölken till en slät smet. Vispa i resten och äggen.",
                    ingredients = listOf(
                        DraftIngredient(amount = 2.5, unit = "dl", food = "vetemjöl"),
                        DraftIngredient(amount = 0.5, unit = "tsk", food = "salt"),
                        DraftIngredient(amount = 6.0, unit = "dl", food = "mjölk"),
                        DraftIngredient(amount = 3.0, unit = "st", food = "ägg"),
                    ),
                ),
                DraftStep(
                    instruction = "Smält smöret och rör ner i smeten. Grädda tunna pannkakor i en het panna.",
                    ingredients = listOf(DraftIngredient(amount = 2.0, unit = "msk", food = "smör")),
                ),
            ),
            imageUrl = "https://example.com/bilder/pannkakor.jpg",
        ),
        imageUrls = listOf("https://example.com/bilder/pannkakor.jpg", "https://example.com/bilder/pannkakor-2.jpg"),
        duplicates = listOf(ImportDuplicate(id = 3, name = "Pannkakor")),
    )
}
