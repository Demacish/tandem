package casa.moller.tandem.feature.recipes.editor

import casa.moller.tandem.core.model.recipe.DraftIngredient
import casa.moller.tandem.core.model.recipe.DraftKeyword
import casa.moller.tandem.core.model.recipe.DraftStep
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.model.recipe.RecipeProperty
import casa.moller.tandem.core.model.recipe.StepFile
import casa.moller.tandem.core.model.recipe.StepRecipeRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The editor's draft as `SavedStateHandle` keeps it (JSON), so a half-written recipe survives
 * process death (core:model has no serialization annotations, decision B2).
 */
@Serializable
internal data class SavedDraft(
    val name: String = "",
    val description: String = "",
    val servings: Int = 1,
    val servingsText: String = "",
    val workingMinutes: Int = 0,
    val waitingMinutes: Int = 0,
    val sourceUrl: String = "",
    val keywords: List<SavedKeyword> = emptyList(),
    val steps: List<SavedStep> = emptyList(),
    val imageUri: String? = null,
    val isPrivate: Boolean = false,
    val imageUrl: String? = null,
    val keepImage: Boolean = true,
    val currentImageUrl: String? = null,
    val properties: List<SavedProperty>? = null,
) {
    fun toDraft(): RecipeDraft = RecipeDraft(
        name = name,
        description = description,
        servings = servings,
        servingsText = servingsText,
        workingMinutes = workingMinutes,
        waitingMinutes = waitingMinutes,
        sourceUrl = sourceUrl,
        keywords = keywords.map { DraftKeyword(it.id, it.name) },
        steps = steps.map { step ->
            DraftStep(
                id = step.id,
                name = step.name,
                instruction = step.instruction,
                timeMinutes = step.timeMinutes,
                ingredients = step.ingredients.map {
                    DraftIngredient(it.id, it.amount, it.unit, it.food, it.note, it.isHeader, it.noAmount, it.rowKey)
                },
                showAsHeader = step.showAsHeader,
                showIngredientsTable = step.showIngredientsTable,
                stepRecipe = step.stepRecipeId?.let { StepRecipeRef(it, step.stepRecipeName) },
                file = step.fileName?.let { StepFile(step.fileId, it, step.fileUri) },
                rowKey = step.rowKey,
            )
        },
        imageUri = imageUri,
        isPrivate = isPrivate,
        imageUrl = imageUrl,
        keepImage = keepImage,
        currentImageUrl = currentImageUrl,
        properties = properties?.map { RecipeProperty(it.id, it.typeId, it.typeName, it.unit, it.amount) },
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun of(draft: RecipeDraft): SavedDraft = SavedDraft(
            name = draft.name,
            description = draft.description,
            servings = draft.servings,
            servingsText = draft.servingsText,
            workingMinutes = draft.workingMinutes,
            waitingMinutes = draft.waitingMinutes,
            sourceUrl = draft.sourceUrl,
            keywords = draft.keywords.map { SavedKeyword(it.id, it.name) },
            steps = draft.steps.map { step ->
                SavedStep(
                    id = step.id,
                    name = step.name,
                    instruction = step.instruction,
                    timeMinutes = step.timeMinutes,
                    ingredients = step.ingredients.map {
                        SavedIngredient(it.id, it.amount, it.unit, it.food, it.note, it.isHeader, it.noAmount, it.rowKey)
                    },
                    showAsHeader = step.showAsHeader,
                    showIngredientsTable = step.showIngredientsTable,
                    stepRecipeId = step.stepRecipe?.recipeId,
                    stepRecipeName = step.stepRecipe?.name,
                    fileId = step.file?.id,
                    fileName = step.file?.name,
                    fileUri = step.file?.uri,
                    rowKey = step.rowKey,
                )
            },
            imageUri = draft.imageUri,
            isPrivate = draft.isPrivate,
            imageUrl = draft.imageUrl,
            keepImage = draft.keepImage,
            currentImageUrl = draft.currentImageUrl,
            properties = draft.properties?.map { SavedProperty(it.id, it.typeId, it.typeName, it.unit, it.amount) },
        )

        fun encode(draft: RecipeDraft): String = json.encodeToString(serializer(), of(draft))

        fun decode(text: String?): RecipeDraft? =
            text?.let { runCatching { json.decodeFromString(serializer(), it).toDraft() }.getOrNull() }
    }
}

@Serializable
internal data class SavedKeyword(val id: Long? = null, val name: String)

@Serializable
internal data class SavedStep(
    val id: Long? = null,
    val name: String = "",
    val instruction: String = "",
    val timeMinutes: Int = 0,
    val ingredients: List<SavedIngredient> = emptyList(),
    val showAsHeader: Boolean = false,
    val showIngredientsTable: Boolean = true,
    val rowKey: Long = 0,
    val stepRecipeId: Long? = null,
    val stepRecipeName: String? = null,
    val fileId: Long? = null,
    val fileName: String? = null,
    val fileUri: String? = null,
)

@Serializable
internal data class SavedProperty(val id: Long? = null, val typeId: Long, val typeName: String, val unit: String = "", val amount: Double)

@Serializable
internal data class SavedIngredient(
    val id: Long? = null,
    val amount: Double = 0.0,
    val unit: String? = null,
    val food: String? = null,
    val note: String = "",
    val isHeader: Boolean = false,
    val noAmount: Boolean = false,
    val rowKey: Long = 0,
)

/** The highest [DraftStep.rowKey] or [DraftIngredient.rowKey] in the draft; 0 when it has none. */
internal val RecipeDraft.lastRowKey: Long
    get() = steps.maxOfOrNull { step -> maxOf(step.rowKey, step.ingredients.maxOfOrNull { it.rowKey } ?: 0L) } ?: 0L

/**
 * Every row without a key gets the next one after [after], so no row ever gets the key of a row
 * that was (a removed row can come back with "Ångra"). Rows that have one keep it.
 */
internal fun RecipeDraft.withRowKeys(after: Long): RecipeDraft {
    var last = maxOf(after, lastRowKey)
    return copy(
        steps = steps.map { step ->
            step.copy(
                rowKey = step.rowKey.takeIf { it != 0L } ?: ++last,
                ingredients = step.ingredients.map { line -> line.copy(rowKey = line.rowKey.takeIf { it != 0L } ?: ++last) },
            )
        },
    )
}

/** What "has anything changed" compares: no values of its own and an empty list say the same to the user. */
internal fun RecipeDraft.comparable(): RecipeDraft = withoutRowKeys().copy(properties = properties.orEmpty())

/** The draft as the rest of the app knows it: the row keys are the form's alone. */
internal fun RecipeDraft.withoutRowKeys(): RecipeDraft = copy(
    steps = steps.map { step -> step.copy(rowKey = 0, ingredients = step.ingredients.map { it.copy(rowKey = 0) }) },
)
