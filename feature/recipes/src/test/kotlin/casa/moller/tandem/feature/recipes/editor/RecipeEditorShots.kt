package casa.moller.tandem.feature.recipes.editor

import androidx.compose.runtime.Composable
import casa.moller.tandem.core.model.recipe.DraftIngredient
import casa.moller.tandem.core.model.recipe.DraftKeyword
import casa.moller.tandem.core.model.recipe.DraftStep
import casa.moller.tandem.core.model.recipe.PropertyType
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.model.recipe.RecipeProperty
import casa.moller.tandem.core.model.recipe.StepFile
import casa.moller.tandem.core.model.recipe.StepRecipeRef
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.feature.recipes.recipeimport.FakeOriginal

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

    /** Fas 4 (#22): everything that goes beyond the plain form on one step, and the recipe's own nutrition. */
    val withOptions: RecipeDraft = filled.copy(
        description = "",
        isPrivate = true,
        keywords = emptyList(),
        steps = listOf(
            DraftStep(
                id = 1,
                name = "Deg",
                instruction = "Gör degen som i receptet.",
                showAsHeader = true,
                showIngredientsTable = false,
                stepRecipe = StepRecipeRef(105, "Pannkakor"),
                file = StepFile(id = 9, name = "Receptblad.pdf"),
            ),
        ),
        properties = listOf(
            RecipeProperty(id = 1, typeId = 1, typeName = "Energi", unit = "kcal", amount = 520.0),
            RecipeProperty(id = 2, typeId = 2, typeName = "Protein", unit = "g", amount = 18.5),
        ),
    )

    fun editingOptions() = editing().copy(
        draft = withOptions,
        propertyTypes = listOf(PropertyType(3, "Fett", "g"), PropertyType(4, "Kolhydrater", "g")),
    )

    val linkPicker = StepLinkPicker(step = 1, results = SampleRecipes.overviews.take(4))

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

    // Review of Fas 3: the keyboard up in a low window, the original of an import, the dialogs.
    EDITING_KEYBOARD({ Editor(RecipeEditorSamples.editing(), keyboardOpen = true) }),
    EDITING_ORIGINAL({ Editor(RecipeEditorSamples.editing().copy(isNew = true, sourcePath = "original.jpg")) }),
    LEAVE_DIALOG({ LeaveEditorDialog(onSave = {}, onDiscard = {}, onKeepEditing = {}) }),
    DISCARD_DIALOG({ DiscardEditDialog(onConfirm = {}, onDismiss = {}) }),

    // Fas 3's leftovers: the recipe's picture with "Ta bort bilden", and after it ("Behåll bilden").
    EDITING_PICTURE({ Editor(RecipeEditorSamples.editingWithPicture(keep = true)) }),
    EDITING_PICTURE_REMOVED({ Editor(RecipeEditorSamples.editingWithPicture(keep = false)) }),

    // Fas 4 (#22): a step with its flags, sub-recipe and file; the recipe's own nutrition; the sub-recipe sheet.
    EDITING_OPTIONS({ Editor(RecipeEditorSamples.editingOptions()) }),
    LINK_PICKER({ StepLinkContent(RecipeEditorSamples.linkPicker, onAction = {}) }),
    LINK_PICKER_EMPTY({ StepLinkContent(StepLinkPicker(step = 0, query = "fisk"), onAction = {}) }),
}

@Composable
private fun Editor(state: RecipeEditorUiState, keyboardOpen: Boolean = false) = RecipeEditorLayout(
    state = state,
    onAction = {},
    onTakePhoto = {},
    onPickPhoto = {},
    onBack = {},
    keyboardOpen = keyboardOpen,
    originalView = { _, modifier -> FakeOriginal(modifier) },
)
