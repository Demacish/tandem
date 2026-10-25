package casa.moller.tandem.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Phase 2 (inköp-data lane): the shopping list, its recipes, named lists, stores and aisles and
// the ingredient parser (plan 3.3, FACTS "Fas 2" b-e). Tolerant like every DTO (plan 3.6): a
// default on every field, unknown keys ignored, dates as raw strings (TandoorDates).

/**
 * One entry of `GET /api/shopping-list-entry/` (also the answer to POST and PATCH of one entry).
 * Recorded in `fas2/shopping-list-entry-*.json` for 2.6.13 and 2.7.1 (the same shape).
 *
 * @property amount a decimal (`2.0`); 0 when the parser found no amount.
 * @property completedAt set by the server when [checked] flips to true, null otherwise (FACTS "Fas 2 b").
 * @property delayUntil "köp senare"; Tandoor's undo value is `1970-01-01T00:00:00Z`.
 */
@Serializable
data class ShoppingListEntryDto(
    val id: Long? = null,
    @SerialName("list_recipe") val listRecipe: Long? = null,
    @SerialName("shopping_lists") val shoppingLists: List<ShoppingListDto> = emptyList(),
    val food: ShoppingFoodDto? = null,
    val unit: UnitDto? = null,
    val amount: Double = 0.0,
    val order: Int = 0,
    val checked: Boolean = false,
    val ingredient: Long? = null,
    @SerialName("list_recipe_data") val listRecipeData: ShoppingListRecipeDto? = null,
    @SerialName("created_by") val createdBy: UserDto? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("delay_until") val delayUntil: String? = null,
)

/** The food of a list entry (Tandoor `FoodShopping`): the aisle is nested as an object or null. */
@Serializable
data class ShoppingFoodDto(
    val id: Long? = null,
    val name: String = "",
    @SerialName("plural_name") val pluralName: String? = null,
    @SerialName("supermarket_category") val supermarketCategory: SupermarketCategoryDto? = null,
    @SerialName("shopping_lists") val shoppingLists: JsonElement? = null,
)

/**
 * A recipe or meal on the list (`/api/shopping-list-recipe/`, an entry's `list_recipe_data`).
 *
 * @property mealplan the meal the entries belong to, if any.
 * @property recipeData the recipe as a list element (its name is the entry's small label).
 * @property mealPlanData the whole meal, kept raw (the meal plan lane owns its shape).
 */
@Serializable
data class ShoppingListRecipeDto(
    val id: Long? = null,
    val name: String = "",
    val recipe: Long? = null,
    @SerialName("recipe_data") val recipeData: RecipeOverviewDto? = null,
    @SerialName("meal_plan_data") val mealPlanData: JsonElement? = null,
    val mealplan: Long? = null,
    val servings: Double = 1.0,
    @SerialName("created_by") val createdBy: UserDto? = null,
)

/** A named shopping list (`/api/shopping-list/`), e.g. "Veckohandling". */
@Serializable
data class ShoppingListDto(
    val id: Long? = null,
    val name: String = "",
    val description: String = "",
    val color: String? = null,
)

/**
 * `POST /api/shopping-list-entry/bulk/` `{ids, checked}` → 200 with the same fields and the
 * server's `timestamp`. An unknown or deleted id is ignored (still 200, FACTS "Fas 2 b").
 */
@Serializable
data class ShoppingBulkCheckDto(
    val ids: List<Long> = emptyList(),
    val checked: Boolean = false,
    val timestamp: String? = null,
)

/** A store (`/api/supermarket/`) with its aisle order (`category_to_supermarket`, sorted by `order`). */
@Serializable
data class SupermarketDto(
    val id: Long? = null,
    val name: String = "",
    val description: String? = null,
    @SerialName("shopping_lists") val shoppingLists: List<ShoppingListDto> = emptyList(),
    @SerialName("category_to_supermarket") val categoryToSupermarket: List<SupermarketCategoryRelationDto> = emptyList(),
    @SerialName("open_data_slug") val openDataSlug: String? = null,
)

/** An aisle / category (`/api/supermarket-category/`), e.g. "Mejeri". */
@Serializable
data class SupermarketCategoryDto(
    val id: Long? = null,
    val name: String = "",
    val description: String? = null,
    @SerialName("open_data_slug") val openDataSlug: String? = null,
)

/** A category's place in a store (`/api/supermarket-category-relation/`): ascending [order] = walking order. */
@Serializable
data class SupermarketCategoryRelationDto(
    val id: Long? = null,
    val category: SupermarketCategoryDto? = null,
    val supermarket: Long? = null,
    val order: Int = 0,
)

/** `POST /api/ingredient-parser/post/`: one line ([ingredient]) or several ([ingredients]) in one call. */
@Serializable
data class IngredientParserRequestDto(
    val ingredient: String? = null,
    val ingredients: List<String>? = null,
)

/** The parser's answer: [ingredient] for a single line, [ingredients] (in order) for several. */
@Serializable
data class IngredientParserResponseDto(
    val ingredient: ParsedIngredientDto? = null,
    val ingredients: List<ParsedIngredientDto> = emptyList(),
)

/**
 * One parsed line. Tandoor CREATES the food (and unit) when it does not know it (FACTS "Fas 2 e"),
 * so [food] has an id even for new names.
 */
@Serializable
data class ParsedIngredientDto(
    val food: ParsedFoodDto? = null,
    val unit: UnitDto? = null,
    val amount: Double = 0.0,
    val note: String = "",
    @SerialName("no_amount") val noAmount: Boolean = false,
    @SerialName("original_text") val originalText: String = "",
)

/** The food of a parsed line (`{id, name, plural_name}`). */
@Serializable
data class ParsedFoodDto(
    val id: Long? = null,
    val name: String = "",
    @SerialName("plural_name") val pluralName: String? = null,
)
