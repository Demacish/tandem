package casa.moller.tandem.feature.recipes.editor

import androidx.compose.runtime.Composable
import casa.moller.tandem.core.model.recipe.DraftIngredient
import casa.moller.tandem.core.model.recipe.DraftKeyword
import casa.moller.tandem.core.model.recipe.DraftStep
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.testing.data.SampleRecipes

/** Sample editor states (#22, plan §8.9); no images (tests never load any). */
internal object RecipeEditorSamples {
    val units = listOf("dl", "g", "krm", "msk", "st", "tsk")
    val suggestions = SampleRecipes.details.flatMap { it.keywords }.distinctBy { it.id }.map { DraftKeyword(it.id, it.name) }

    val filled: RecipeDraft = RecipeDraft(
        name = "Krämig svamppasta",
        description = "Klassisk italiensk tagliatelle slungad i en sammetslen gräddsås med smörfrästa champinjoner.",
        servings = 4,
        servingsText = "portioner",
        workingMinutes = 35,
        keywords = listOf(DraftKeyword(2, "Vardag"), DraftKeyword(1, "Vegetariskt")),
        steps = listOf(
            DraftStep(
                id = 1,
                name = "Koka pastan",
                timeMinutes = 12,
                instruction = "Koka tagliatellen al dente i rikligt saltat vatten. Spara 1 dl av pastavattnet.",
                ingredients = listOf(
                    DraftIngredient(id = 1, amount = 300.0, unit = "g", food = "tagliatelle"),
                    DraftIngredient(id = 2, food = "salt", noAmount = true, note = "till pastavattnet"),
                ),
            ),
            DraftStep(
                id = 2,
                name = "Stek svampen",
                timeMinutes = 8,
                instruction = "Hetta upp smöret och stek champinjonerna tills de fått fin färg.",
                ingredients = listOf(
                    DraftIngredient(id = 3, isHeader = true, note = "Sås"),
                    DraftIngredient(id = 4, amount = 250.0, unit = "g", food = "champinjoner", note = "skivade"),
                    DraftIngredient(id = 5, amount = 2.0, unit = "dl", food = "vispgrädde"),
                ),
            ),
        ),
    )

    fun empty(nameMissing: Boolean = false) = RecipeEditorUiState(
        loaded = true,
        isNew = true,
        units = units,
        keywordSuggestions = suggestions,
        nameMissing = nameMissing,
    )

    /** Fas 3's leftovers: a stored recipe with a picture (never loaded in tests). */
    fun editingWithPicture(keep: Boolean) =
        editing().copy(draft = filled.copy(currentImageUrl = "https://example.com/media/recipes/svamppasta.jpg", keepImage = keep))

    fun editing(keptEdit: Boolean = false, readOnly: Boolean = false) = RecipeEditorUiState(
        loaded = true,
        isNew = false,
        draft = filled,
        units = units,
        keywordSuggestions = suggestions.filter { s -> filled.keywords.none { it.id == s.id } },
        keptEdit = keptEdit,
        readOnly = readOnly,
    )
}

/** Every editor state, for the screenshot and accessibility tests (one list, so both cover the same). */
internal enum class RecipeEditorShot(val content: @Composable () -> Unit) {
    NEW_EMPTY({ Editor(RecipeEditorSamples.empty()) }),
    NEW_NAME_MISSING({ Editor(RecipeEditorSamples.empty(nameMissing = true)) }),
    EDITING({ Editor(RecipeEditorSamples.editing()) }),
    EDITING_KEPT_EDIT({ Editor(RecipeEditorSamples.editing(keptEdit = true)) }),
    EDITING_GUEST({ Editor(RecipeEditorSamples.editing(readOnly = true)) }),
    LOADING({ Editor(RecipeEditorUiState(isNew = false)) }),
    UNKNOWN({ Editor(RecipeEditorUiState(isNew = false, unknownRecipe = true)) }),

    // Fas 3's leftovers: the recipe's picture with "Ta bort bilden", and after it ("Behåll bilden").
    EDITING_PICTURE({ Editor(RecipeEditorSamples.editingWithPicture(keep = true)) }),
    EDITING_PICTURE_REMOVED({ Editor(RecipeEditorSamples.editingWithPicture(keep = false)) }),
}

@Composable
private fun Editor(state: RecipeEditorUiState) =
    RecipeEditorScreen(state = state, onAction = {}, onTakePhoto = {}, onPickPhoto = {}, onBack = {})
