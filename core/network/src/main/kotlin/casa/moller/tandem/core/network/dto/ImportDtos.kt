package casa.moller.tandem.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Fas 3 (import lane): `POST /api/recipe-from-source/` and `POST /api/ai-import/` answer the same
// shape (schema `RecipeFromSourceResponse`): the parsed recipe (schema `SourceImportRecipe`, a
// recipe without ids: foods, units and keywords by name), the pictures the source offered, an error
// flag with a message, and the space's recipes that look like duplicates. Every field has a
// default: the server leaves out what it did not find.

/** `RecipeFromSourceResponse`. */
@Serializable
data class RecipeFromSourceResponseDto(
    val recipe: SourceImportRecipeDto? = null,
    @SerialName("recipe_id") val recipeId: Long? = null,
    val images: List<String> = emptyList(),
    val error: Boolean = false,
    val msg: String = "",
    /** `[{id, name}]` of the space's recipes with the same source; raw, since the recordings carry none (DtoFixturesTest). */
    val duplicates: JsonElement? = null,
)

/** `SourceImportRecipe`. */
@Serializable
data class SourceImportRecipeDto(
    val name: String = "",
    val description: String = "",
    val servings: Int = 1,
    @SerialName("servings_text") val servingsText: String = "",
    @SerialName("working_time") val workingTime: Int = 0,
    @SerialName("waiting_time") val waitingTime: Int = 0,
    @SerialName("source_url") val sourceUrl: String = "",
    @SerialName("image_url") val imageUrl: String = "",
    val internal: Boolean = true,
    val keywords: List<SourceImportKeywordDto> = emptyList(),
    val steps: List<SourceImportStepDto> = emptyList(),
)

/**
 * `SourceImportKeyword`: [label] is what the web shows, [name] what is sent; [importKeyword] is
 * the web's checkbox, false for every keyword in the recordings (FACTS "Fas 3 e"), so the app
 * does not go by it.
 */
@Serializable
data class SourceImportKeywordDto(
    val id: Long? = null,
    val label: String = "",
    val name: String = "",
    @SerialName("import_keyword") val importKeyword: Boolean = true,
)

/** `SourceImportStep`. */
@Serializable
data class SourceImportStepDto(
    val instruction: String = "",
    val ingredients: List<SourceImportIngredientDto> = emptyList(),
    @SerialName("show_ingredients_table") val showIngredientsTable: Boolean = true,
)

/** `SourceImportIngredient`: [originalText] is the line as the source had it (the editor's note when nothing was parsed). */
@Serializable
data class SourceImportIngredientDto(
    val amount: Double = 0.0,
    val food: SourceImportNameDto? = null,
    val unit: SourceImportNameDto? = null,
    val note: String = "",
    @SerialName("original_text") val originalText: String = "",
)

/** `SourceImportFood` / `SourceImportUnit`: a name only. */
@Serializable
data class SourceImportNameDto(val name: String = "")
