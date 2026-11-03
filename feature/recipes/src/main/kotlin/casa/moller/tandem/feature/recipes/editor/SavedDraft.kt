package casa.moller.tandem.feature.recipes.editor

import casa.moller.tandem.core.model.recipe.DraftIngredient
import casa.moller.tandem.core.model.recipe.DraftKeyword
import casa.moller.tandem.core.model.recipe.DraftStep
import casa.moller.tandem.core.model.recipe.RecipeDraft
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
                    DraftIngredient(it.id, it.amount, it.unit, it.food, it.note, it.isHeader, it.noAmount)
                },
                showAsHeader = step.showAsHeader,
                showIngredientsTable = step.showIngredientsTable,
            )
        },
        imageUri = imageUri,
        isPrivate = isPrivate,
        imageUrl = imageUrl,
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
                        SavedIngredient(it.id, it.amount, it.unit, it.food, it.note, it.isHeader, it.noAmount)
                    },
                    showAsHeader = step.showAsHeader,
                    showIngredientsTable = step.showIngredientsTable,
                )
            },
            imageUri = draft.imageUri,
            isPrivate = draft.isPrivate,
            imageUrl = draft.imageUrl,
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
)

@Serializable
internal data class SavedIngredient(
    val id: Long? = null,
    val amount: Double = 0.0,
    val unit: String? = null,
    val food: String? = null,
    val note: String = "",
    val isHeader: Boolean = false,
    val noAmount: Boolean = false,
)
