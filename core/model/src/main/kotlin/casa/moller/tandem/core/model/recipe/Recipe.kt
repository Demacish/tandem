package casa.moller.tandem.core.model.recipe

import kotlin.time.Instant

/**
 * The full recipe for the detail screen and cook mode (plan 3.2, Tandoor `Recipe`). Comes from
 * Room (the stored raw JSON mapped by the data layer); never parsed in the UI.
 *
 * @property id Tandoor recipe id.
 * @property name recipe name, shown as is.
 * @property description description (plain text), if any.
 * @property imageUrl absolute image URL, or null.
 * @property servings the servings the amounts are written for (`servings`); at least 1 after
 *   mapping. Scaling starts from this value.
 * @property servingsText the unit of [servings] as the user wrote it (`servings_text`, e.g.
 *   "portioner", "pieces"); empty when not set.
 * @property workingMinutes active time in minutes.
 * @property waitingMinutes passive time in minutes.
 * @property rating the user's rating 0..5, null when never rated.
 * @property lastCooked last cook log entry, null when never cooked.
 * @property keywords the recipe's keywords.
 * @property steps the steps in server order ([Step.order]); ingredients live inside the steps.
 * @property sourceUrl where the recipe was imported from, if any.
 * @property isPrivate Tandoor's `private` flag.
 * @property showIngredientOverview Tandoor's `show_ingredient_overview`: show all ingredients in
 *   one list above the steps (otherwise only per step).
 * @property nutrition which kind of nutrition data the server holds (shown in a later phase).
 * @property diameter the baking form diameter (`diameter`), 0 when not used.
 * @property diameterText unit/label of [diameter] (`diameter_text`, e.g. "cm").
 * @property createdAt server `created_at`, if known.
 * @property updatedAt server `updated_at` (unreliable for change detection, plan 3.5).
 */
data class Recipe(
    val id: Long,
    val name: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val servings: Int = 1,
    val servingsText: String = "",
    val workingMinutes: Int = 0,
    val waitingMinutes: Int = 0,
    val rating: Double? = null,
    val lastCooked: Instant? = null,
    val keywords: List<Keyword> = emptyList(),
    val steps: List<Step> = emptyList(),
    val sourceUrl: String? = null,
    val isPrivate: Boolean = false,
    val showIngredientOverview: Boolean = true,
    val nutrition: NutritionPresence = NutritionPresence.NONE,
    val diameter: Int = 0,
    val diameterText: String = "",
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    /** Working plus waiting time in minutes. */
    val totalMinutes: Int
        get() = workingMinutes + waitingMinutes

    /** Every ingredient of every step, in step order and then ingredient order (headers included). */
    val ingredients: List<Ingredient>
        get() = steps.sortedBy { it.order }.flatMap { step -> step.ingredients.sortedBy { it.order } }
}

/** Which nutrition data a recipe has on the server (plan 3.2: `properties` first, legacy `nutrition` as fallback). */
enum class NutritionPresence {
    /** No nutrition data. */
    NONE,

    /** Tandoor 2.x `properties` (per property type, e.g. energy, protein). */
    PROPERTIES,

    /** Only the older `NutritionInformation` (`nutrition`: calories, carbohydrates, fats, proteins). */
    LEGACY,
}

/**
 * One step of a recipe (Tandoor `Step`).
 *
 * @property id Tandoor step id.
 * @property name optional step title; empty when not set.
 * @property instruction the step text as stored on the server: Markdown that may contain Tandoor
 *   templates such as `{{ scale(100) }}` or `{{ ingredients[0] }}` (rendered by the client with
 *   its own scaling, plan 8.1). Never pre-rendered HTML.
 * @property timeMinutes the step's time in minutes (`time`), 0 when not set. Cook mode offers a
 *   timer for it in addition to times found in the text.
 * @property order position of the step within the recipe (ascending).
 * @property ingredients the ingredients used in this step, in order.
 * @property showAsHeader Tandoor's `show_as_header`: show [name] as a section heading.
 * @property showIngredientsTable Tandoor's `show_ingredients_table`: show this step's ingredients
 *   next to its text.
 * @property stepRecipe a recipe used as a sub-recipe in this step (`step_recipe`), if any.
 * @property imageUrl the step's photo (Tandoor `file.preview`, an absolute URL like the recipe
 *   image), null when the step has no file or the file is no image. Cook mode shows it under the
 *   step text (plan 6.2 "Stegbild om den finns").
 */
data class Step(
    val id: Long,
    val name: String = "",
    val instruction: String = "",
    val timeMinutes: Int = 0,
    val order: Int = 0,
    val ingredients: List<Ingredient> = emptyList(),
    val showAsHeader: Boolean = false,
    val showIngredientsTable: Boolean = true,
    val stepRecipe: StepRecipeRef? = null,
    val imageUrl: String? = null,
)

/**
 * A recipe referenced from a step (Tandoor `step_recipe` + `step_recipe_data`).
 *
 * @property recipeId the referenced recipe's id; the UI opens it like any other recipe.
 * @property name its name, when the server sent it.
 */
data class StepRecipeRef(val recipeId: Long, val name: String? = null)

/**
 * One ingredient row (Tandoor `Ingredient`). Header rows ([isHeader]) only carry [note] or the
 * food name as section title and are never scaled; [noAmount] rows ("salt to taste") are shown
 * without an amount.
 *
 * @property id Tandoor ingredient id.
 * @property food the food, null for a header or a free-text row.
 * @property unit the unit, null when the amount has no unit ("3 eggs").
 * @property amount the amount for [Recipe.servings]; scale it with core:domain's ScaleIngredients.
 * @property note free text after the food ("finely chopped"), if any.
 * @property isHeader Tandoor's `is_header`.
 * @property noAmount Tandoor's `no_amount`.
 * @property originalText the line as it was imported, if known (fallback display).
 * @property order position within the step.
 * @property conversions the same amount in other units, computed by the server (`conversions`);
 *   tapping an amount cycles through them (plan 6.2).
 */
data class Ingredient(
    val id: Long,
    val food: Food? = null,
    val unit: MeasurementUnit? = null,
    val amount: Double = 0.0,
    val note: String? = null,
    val isHeader: Boolean = false,
    val noAmount: Boolean = false,
    val originalText: String? = null,
    val order: Int = 0,
    val conversions: List<UnitConversion> = emptyList(),
) {
    /** True when the amount is scaled with the servings (not a header, not "no amount"). */
    val isScalable: Boolean
        get() = !isHeader && !noAmount
}

/**
 * A food (Tandoor `Food`; a tree on the server, flat here).
 *
 * @property id Tandoor food id.
 * @property name singular name.
 * @property pluralName plural name, if the user entered one (used for amounts other than 1).
 * @property onHand Tandoor's `food_onhand` ("have at home", shared by the household).
 */
data class Food(val id: Long, val name: String, val pluralName: String? = null, val onHand: Boolean = false)

/**
 * A unit of measurement (Tandoor `Unit`; called MeasurementUnit to avoid a clash with
 * [kotlin.Unit]).
 *
 * @property id Tandoor unit id.
 * @property name singular name, e.g. "dl", "msk", "cup".
 * @property pluralName plural name, if any.
 * @property baseUnit Tandoor's `base_unit` (open data slug of the base unit, e.g. "gram"), if set.
 */
data class MeasurementUnit(val id: Long, val name: String, val pluralName: String? = null, val baseUnit: String? = null)

/**
 * One entry of an ingredient's server-computed `conversions`: the ingredient's amount expressed
 * in another unit.
 *
 * @property amount the amount in [unitName] for the recipe's own servings (scale it like [Ingredient.amount]).
 * @property unitName the unit's name as the server sent it.
 * @property unitPluralName the unit's plural name, if the server sent one.
 */
data class UnitConversion(val amount: Double, val unitName: String, val unitPluralName: String? = null)
