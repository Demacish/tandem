package casa.moller.tandem.core.domain.recipe

import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.scaling.ServingsScaling
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.model.recipe.StepRecipeRef

/**
 * Everything the recipe detail (and later cook mode) shows that depends on the servings: the
 * ingredient list and the steps with rendered templates. Pure data; the UI only lays it out.
 *
 * @property recipeId the recipe.
 * @property servings the servings it is scaled to.
 * @property factor `servings / max(recipe.servings, 1)`.
 * @property ingredients every ingredient of every step in step order, headers included (the
 *   overview list; Tandoor's `show_ingredient_overview`).
 * @property steps the steps in `order`.
 */
data class RecipeDisplayModel(
    val recipeId: Long,
    val servings: Double,
    val factor: Double,
    val ingredients: List<IngredientRow>,
    val steps: List<StepDisplay>,
) {
    /** [ingredients] grouped under their headers. */
    val ingredientGroups: List<IngredientGroup>
        get() = IngredientDisplay.groups(ingredients)
}

/**
 * One step, ready to show.
 *
 * @property id Tandoor step id.
 * @property number 1-based position among the steps ("Steg 2").
 * @property name the step title; empty when not set.
 * @property instruction the instruction as Markdown with templates rendered ([RenderedInstruction.text]).
 * @property hasBrokenTemplate the templates could not be parsed and [instruction] is the raw text.
 * @property ingredients the step's ingredients (in `order`), scaled like [RecipeDisplayModel.ingredients].
 * @property timeMinutes the step's time (`time`), 0 when not set.
 * @property showAsHeader Tandoor's `show_as_header`.
 * @property showIngredientsTable Tandoor's `show_ingredients_table`: show [ingredients] with the step.
 * @property stepRecipe a sub-recipe used in this step, if any.
 */
data class StepDisplay(
    val id: Long,
    val number: Int,
    val name: String,
    val instruction: String,
    val hasBrokenTemplate: Boolean,
    val ingredients: List<IngredientRow>,
    val timeMinutes: Int,
    val showAsHeader: Boolean,
    val showIngredientsTable: Boolean,
    val stepRecipe: StepRecipeRef?,
)

/** The one call the recipe UI makes: [build]. */
object RecipeDisplay {
    /**
     * Scales [recipe] to [servings] and formats it with the user's Tandoor [preferences]
     * (`use_fractions`, `ingredient_decimals`) in [style] (from the UI's locale with
     * `Locale.toNumberStyle()`; [NumberStyle.Web] gives the web's exact text). Cheap enough to
     * call on every servings change; never throws.
     */
    fun build(recipe: Recipe, servings: Double, preferences: UserPreferences, style: NumberStyle): RecipeDisplayModel {
        val factor = ServingsScaling.factor(recipe, servings)
        val steps = recipe.steps.sortedBy { it.order }
        val stepDisplays = steps.mapIndexed { index, step ->
            val ingredients = step.ingredients.sortedBy { it.order }
            val rendered = TemplateRenderer.render(step.instruction, ingredients, factor, preferences, style)
            StepDisplay(
                id = step.id,
                number = index + 1,
                name = step.name,
                instruction = rendered.text,
                hasBrokenTemplate = rendered.hasBrokenTemplate,
                ingredients = IngredientDisplay.rows(ingredients, factor, preferences, style),
                timeMinutes = step.timeMinutes,
                showAsHeader = step.showAsHeader,
                showIngredientsTable = step.showIngredientsTable,
                stepRecipe = step.stepRecipe,
            )
        }
        return RecipeDisplayModel(
            recipeId = recipe.id,
            servings = if (servings.isFinite() && servings > 0.0) servings else ServingsScaling.baseServings(recipe.servings).toDouble(),
            factor = factor,
            ingredients = stepDisplays.flatMap { it.ingredients },
            steps = stepDisplays,
        )
    }
}
