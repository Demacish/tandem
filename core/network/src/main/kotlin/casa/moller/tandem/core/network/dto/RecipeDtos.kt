package casa.moller.tandem.core.network.dto

import casa.moller.tandem.core.network.json.TandoorJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Recipe DTOs for Phase 1 (plan 3.2, FACTS "Fas 1 b-d"). Tolerant per plan 3.6: every field has
// a default, enum-like values stay String, and parts whose shape is untyped in the schema or may
// change (nutrition, properties, food_properties, conversions, step_recipe_data, files) are kept
// as JsonElement and read through helpers that never throw.

/** One element of `GET /api/recipe/flat/`: the cheap index of every recipe the user may see. */
@Serializable
data class RecipeFlatDto(
    val id: Long? = null,
    val name: String = "",
    /** A RELATIVE path such as `/media/recipes/<uuid>_<id>.png`, or null (FACTS "Fas 1 b"). */
    val image: String? = null,
)

/** `{id, label}`: a keyword as embedded in [RecipeOverviewDto]. */
@Serializable
data class KeywordLabelDto(val id: Long? = null, val label: String = "")

/**
 * One result of `GET /api/recipe/` (and the recipe inside a meal plan or book entry).
 *
 * - [rating] is filled in only when the list is sorted by rating (FACTS "Fas 1 c").
 * - [lastCooked] is always null on 2.6.13; compute it from the cook log.
 * - [isNew] means "created less than 7 days ago"; `new=true` only sorts, it does not filter.
 * - [recent] is only sent with `num_recent`: the id of the latest view log as a string, `"0"`
 *   when the recipe is not among the recently viewed ones.
 */
@Serializable
data class RecipeOverviewDto(
    val id: Long? = null,
    val name: String = "",
    val description: String? = null,
    /** Absolute URL built from the proxy headers; see [casa.moller.tandem.core.network.image.MediaUrls]. */
    val image: String? = null,
    val keywords: List<KeywordLabelDto> = emptyList(),
    @SerialName("working_time") val workingTime: Int = 0,
    @SerialName("waiting_time") val waitingTime: Int = 0,
    @SerialName("created_by") val createdBy: UserDto? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("internal") val isInternal: Boolean = true,
    @SerialName("private") val isPrivate: Boolean = false,
    val servings: Int = 1,
    @SerialName("servings_text") val servingsText: String = "",
    val rating: Double? = null,
    @SerialName("last_cooked") val lastCooked: String? = null,
    @SerialName("new") val isNew: Boolean = false,
    val recent: String? = null,
)

/** `GET /api/keyword/` and the full keywords inside [RecipeDto]. A tree: [parent] is read-only. */
@Serializable
data class KeywordDto(
    val id: Long? = null,
    val name: String = "",
    val label: String = "",
    val description: String = "",
    val parent: Long? = null,
    val numchild: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    /** e.g. `Huvudrätt > Pasta`. */
    @SerialName("full_name") val fullName: String = "",
)

/**
 * `GET /api/unit/` and an ingredient's unit. [pluralName] is often equal to [name] or null
 * (FACTS "Fas 1 a"); show it as it is.
 */
@Serializable
data class UnitDto(
    val id: Long? = null,
    val name: String = "",
    @SerialName("plural_name") val pluralName: String? = null,
    val description: String? = null,
    /** An open-data base unit key such as `g`, `ml`, `tbsp`, `us_cup`; null for custom units. */
    @SerialName("base_unit") val baseUnit: String? = null,
    @SerialName("open_data_slug") val openDataSlug: String? = null,
)

/** `GET /api/food/` and an ingredient's food. A tree like keywords. */
@Serializable
data class FoodDto(
    val id: Long? = null,
    val name: String = "",
    @SerialName("plural_name") val pluralName: String? = null,
    val description: String = "",
    /** `{id, name, url}` of a recipe that makes this food, or null. */
    val recipe: JsonElement? = null,
    val url: String? = null,
    val properties: JsonElement? = null,
    @SerialName("properties_food_amount") val propertiesFoodAmount: Double? = null,
    @SerialName("properties_food_unit") val propertiesFoodUnit: JsonElement? = null,
    @SerialName("fdc_id") val fdcId: Long? = null,
    /** "Have at home"; shared by the household. */
    @SerialName("food_onhand") val onHand: Boolean? = null,
    @SerialName("supermarket_category") val supermarketCategory: JsonElement? = null,
    val parent: Long? = null,
    val numchild: Int = 0,
    @SerialName("inherit_fields") val inheritFields: JsonElement? = null,
    @SerialName("full_name") val fullName: String = "",
    @SerialName("ignore_shopping") val ignoreShopping: Boolean = false,
    val substitute: JsonElement? = null,
    @SerialName("substitute_siblings") val substituteSiblings: Boolean = false,
    @SerialName("substitute_children") val substituteChildren: Boolean = false,
    @SerialName("substitute_onhand") val substituteOnHand: Boolean = false,
    @SerialName("child_inherit_fields") val childInheritFields: JsonElement? = null,
    @SerialName("open_data_slug") val openDataSlug: String? = null,
    @SerialName("shopping_lists") val shoppingLists: JsonElement? = null,
)

/**
 * One entry of an ingredient's server-computed `conversions` (FACTS "Fas 1 d"): names, no ids.
 * The first entry is the ingredient itself.
 */
@Serializable
data class IngredientConversionDto(val food: String? = null, val unit: String? = null, val amount: Double = 0.0)

/**
 * An ingredient of a step. A header has `is_header`, no food/unit and its text in [note].
 * With [noAmount] the amount is 0 and the unit usually null.
 */
@Serializable
data class IngredientDto(
    val id: Long? = null,
    val food: FoodDto? = null,
    val unit: UnitDto? = null,
    /** Rounded to 4 decimals in JSON (2/3 -> 0.6667). */
    val amount: Double = 0.0,
    /** Untyped in the schema; read with [conversionList]. */
    val conversions: JsonElement? = null,
    val note: String? = null,
    val order: Int = 0,
    @SerialName("is_header") val isHeader: Boolean = false,
    @SerialName("no_amount") val noAmount: Boolean = false,
    @SerialName("original_text") val originalText: String? = null,
    @SerialName("used_in_recipes") val usedInRecipes: JsonElement? = null,
    val checked: Boolean = false,
) {
    /** The conversions that have the expected shape; anything else is skipped, never thrown. */
    fun conversionList(): List<IngredientConversionDto> = (conversions as? JsonArray).orEmpty().mapNotNull { element ->
        try {
            TandoorJson.decodeFromJsonElement(IngredientConversionDto.serializer(), element)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/**
 * A recipe step. [instruction] is the raw Markdown with Jinja templates (`{{ scale(100) }}`,
 * `{{ ingredients[0] }}`); [instructionsMarkdown] is the server-rendered HTML with
 * `<scalable-number>`/`<plural-name>` tags, or the text `Could not parse template code. ...`
 * when the template is broken ([hasBrokenTemplate]; FACTS "Fas 1 d").
 */
@Serializable
data class StepDto(
    val id: Long? = null,
    val name: String = "",
    val instruction: String = "",
    val ingredients: List<IngredientDto> = emptyList(),
    @SerialName("instructions_markdown") val instructionsMarkdown: String = "",
    /** Minutes. */
    val time: Int = 0,
    val order: Int = 0,
    @SerialName("show_as_header") val showAsHeader: Boolean = false,
    val file: JsonElement? = null,
    /** Id of a recipe used as this step (a sub-recipe), or null. */
    @SerialName("step_recipe") val stepRecipe: Long? = null,
    /** The sub-recipe as an object (untyped in the schema); read with [stepRecipeName]. */
    @SerialName("step_recipe_data") val stepRecipeData: JsonElement? = null,
    @SerialName("show_ingredients_table") val showIngredientsTable: Boolean = true,
) {
    /** True when the server could not render the templates; show [instruction] as Markdown then. */
    val hasBrokenTemplate: Boolean
        get() = instructionsMarkdown.trimStart().startsWith(BROKEN_TEMPLATE_PREFIX)

    /**
     * The step file's image link (`file.preview`, `UserFileView`): Tandoor fills it only when the
     * file is an image, else it is "". `file_download` is not used: it is an authenticated download
     * of any file type, not an image to show. Null without a file.
     */
    fun filePreview(): String? = ((file as? JsonObject)?.get("preview") as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }

    /** The sub-recipe's name from [stepRecipeData], or null. */
    fun stepRecipeName(): String? = ((stepRecipeData as? JsonObject)?.get("name") as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull

    companion object {
        /** What `instructions_markdown` starts with when the template syntax is broken (2.6.13). */
        const val BROKEN_TEMPLATE_PREFIX: String = "Could not parse template code."
    }
}

/**
 * `GET /api/recipe/{id}/` (FACTS "Fas 1 d"). Keep the raw JSON as well (plan 3.6: a later app
 * version can map it again without downloading everything): `RecipeService.recipeJson` plus
 * [TandoorJson] decoding gives both from one request.
 *
 * - [rating] is the CALLER's average rating from the cook log (null for others).
 * - [nutrition] (legacy) is only a fallback when [properties] is empty; [foodProperties] has no
 *   type in the schema (`{}` without properties).
 */
@Serializable
data class RecipeDto(
    val id: Long? = null,
    val name: String = "",
    val description: String? = null,
    val image: String? = null,
    val keywords: List<KeywordDto> = emptyList(),
    val steps: List<StepDto> = emptyList(),
    @SerialName("working_time") val workingTime: Int = 0,
    @SerialName("waiting_time") val waitingTime: Int = 0,
    @SerialName("created_by") val createdBy: UserDto? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    @SerialName("internal") val isInternal: Boolean = true,
    @SerialName("show_ingredient_overview") val showIngredientOverview: Boolean = true,
    val nutrition: JsonElement? = null,
    val properties: JsonElement? = null,
    @SerialName("food_properties") val foodProperties: JsonElement? = null,
    val servings: Int = 1,
    @SerialName("file_path") val filePath: String = "",
    @SerialName("servings_text") val servingsText: String = "",
    val diameter: Int = 0,
    @SerialName("diameter_text") val diameterText: String = "",
    val rating: Double? = null,
    @SerialName("last_cooked") val lastCooked: String? = null,
    @SerialName("private") val isPrivate: Boolean = false,
    val shared: List<UserDto> = emptyList(),
) {
    /** True when [properties] is a non-empty array. */
    val hasProperties: Boolean
        get() = (properties as? JsonArray)?.isNotEmpty() == true

    /** True when the legacy [nutrition] object is present. */
    val hasLegacyNutrition: Boolean
        get() = nutrition is JsonObject
}

/** `GET /api/recipe-book/`. [filter] is a saved search (smart book) or null. */
@Serializable
data class RecipeBookDto(
    val id: Long? = null,
    val name: String = "",
    val description: String = "",
    val shared: List<UserDto> = emptyList(),
    @SerialName("created_by") val createdBy: UserDto? = null,
    val filter: JsonElement? = null,
    val order: Int = 0,
)

/** `GET /api/recipe-book-entry/?book=`: a recipe in a book. */
@Serializable
data class RecipeBookEntryDto(
    val id: Long? = null,
    val book: Long? = null,
    @SerialName("book_content") val bookContent: RecipeBookDto? = null,
    val recipe: Long? = null,
    @SerialName("recipe_content") val recipeContent: RecipeOverviewDto? = null,
)

/** `GET /api/share-link/{id}` (Phase 1b; path WITHOUT a trailing slash, plan 4.1). */
@Serializable
data class ShareLinkDto(val pk: Long? = null, val share: String = "", val link: String = "")
