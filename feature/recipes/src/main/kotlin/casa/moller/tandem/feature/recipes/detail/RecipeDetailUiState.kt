package casa.moller.tandem.feature.recipes.detail

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.recipe.IngredientRow
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.model.recipe.StepRecipeRef
import kotlin.time.Instant

/**
 * What the recipe detail shows (plan 6.2 "Receptdetalj"), built by [RecipeDetailViewModel] from
 * the repository's stale-while-revalidate state, the servings choice and the user's Tandoor
 * preferences.
 *
 * @property loading nothing has been read yet (first frame).
 * @property header name, image, times, rating, keywords: from the stored detail, or from the
 *   list entry while no detail is stored. Null when the recipe is unknown locally.
 * @property hasDetail the full recipe (ingredients, steps) is stored.
 * @property servings the servings shown (the user's choice or the recipe's own), null without detail.
 * @property ingredients the ingredient overview (headers included), scaled and formatted like
 *   Tandoor's web (core:domain `RecipeDisplay`); empty when the recipe shows its ingredients per
 *   step only.
 * @property steps the steps, with their ingredients scaled and their templates rendered.
 * @property preferences the user's Tandoor preferences the amounts were formatted with.
 * @property refreshing a refresh from the server runs (quiet indicator).
 * @property error why the last refresh failed; with a stored detail the screen keeps showing it.
 * @property offline the device is offline (sync paused) or the last refresh could not connect.
 * @property fetchedAt when the stored detail was fetched.
 * @property availableOffline the detail is stored on the device.
 * @property pinned the user chose "Download for offline use".
 * @property canPin the offline toggle is offered (not in demo mode, where everything is local).
 * @property webUrl the recipe in Tandoor's web app ("Open on the web", for editing), null in demo mode.
 * @property struckIngredientIds rows the user struck through (this session only).
 * @property unitChoices which of a row's units is shown, by ingredient id (this session only).
 */
@Immutable
data class RecipeDetailUiState(
    val recipeId: Long,
    val loading: Boolean = true,
    val header: RecipeHeader? = null,
    val hasDetail: Boolean = false,
    val servings: ServingsState? = null,
    val ingredients: List<IngredientRow> = emptyList(),
    val steps: List<StepItem> = emptyList(),
    val sourceUrl: String? = null,
    val preferences: UserPreferences = UserPreferences.Default,
    val refreshing: Boolean = false,
    val error: TandemError? = null,
    val offline: Boolean = false,
    val fetchedAt: Instant? = null,
    val availableOffline: Boolean = false,
    val pinned: Boolean = false,
    val canPin: Boolean = false,
    val webUrl: String? = null,
    val struckIngredientIds: Set<Long> = emptySet(),
    val unitChoices: Map<Long, Int> = emptyMap(),
) {
    /** The server says the recipe no longer exists (deleted or no longer shared). */
    val isDeleted: Boolean
        get() = error == TandemError.NotFound

    /** A stored detail is shown although it could not be refreshed ("Visar sparad version"). */
    val showsSavedVersion: Boolean
        get() = hasDetail && !isDeleted && (offline || error != null)

    /** Nothing at all can be shown: no detail, no list entry. */
    val isEmpty: Boolean
        get() = header == null && !hasDetail
}

/**
 * The top of the recipe detail.
 *
 * @property timesCooked how often it was cooked, when the list entry knows it.
 * @property lastCooked when it was last cooked, if ever.
 */
@Immutable
data class RecipeHeader(
    val name: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val workingMinutes: Int = 0,
    val waitingMinutes: Int = 0,
    val rating: Double? = null,
    val keywords: List<Keyword> = emptyList(),
    val timesCooked: Int? = null,
    val lastCooked: Instant? = null,
    val isPrivate: Boolean = false,
) {
    val totalMinutes: Int
        get() = workingMinutes + waitingMinutes
}

/**
 * The servings picker.
 *
 * @property current the servings the amounts are scaled to (may be fractional).
 * @property original the recipe's own servings (`servings`).
 * @property text the unit of the servings as written in Tandoor (`servings_text`), empty when not set.
 * @property amountText [current] as a number in the UI's language ("4", "2,5"), core:domain `ServingsText.number`.
 */
@Immutable
data class ServingsState(val current: Double, val original: Int, val text: String, val amountText: String) {
    val isChanged: Boolean
        get() = current != original.toDouble()

    val canDecrease: Boolean
        get() = current > MIN_SERVINGS

    companion object {
        const val MIN_SERVINGS: Double = 1.0
    }
}

/**
 * One step of the recipe detail, from core:domain's `StepDisplay`.
 *
 * @property number 1-based position.
 * @property instruction the instruction as Markdown, with Tandoor's templates (`{{ scale(100) }}`,
 *   `{{ ingredients[0] }}`) rendered for the chosen servings (core:domain `TemplateRenderer`).
 * @property hasBrokenTemplate the templates could not be parsed (Tandoor's web shows an error),
 *   so [instruction] is the text as written; the screen says that its amounts are not scaled.
 * @property ingredients the step's ingredients, scaled (shown when [showIngredients]).
 * @property showIngredients show [ingredients] with the step (Tandoor `show_ingredients_table` and
 *   the user preference `show_step_ingredients`, or always when the recipe has no overview).
 */
@Immutable
data class StepItem(
    val id: Long,
    val number: Int,
    val name: String,
    val instruction: String,
    val minutes: Int,
    val ingredients: List<IngredientRow>,
    val showIngredients: Boolean,
    val stepRecipe: StepRecipeRef?,
    val hasBrokenTemplate: Boolean = false,
)

/** What the detail screen can do. */
sealed interface RecipeDetailAction {
    data object IncreaseServings : RecipeDetailAction

    data object DecreaseServings : RecipeDetailAction

    data object ResetServings : RecipeDetailAction

    data class ToggleIngredient(val id: Long) : RecipeDetailAction

    data class SwitchUnit(val id: Long) : RecipeDetailAction

    data object Refresh : RecipeDetailAction

    data object ToggleOffline : RecipeDetailAction

    /**
     * How numbers are written in the UI's language (decimal comma, fraction glyphs). The screen
     * sends it from its configuration's locale, so the text follows a per-app language change.
     */
    data class SetNumberStyle(val style: NumberStyle) : RecipeDetailAction
}

/** One-off events of the detail screen. */
sealed interface RecipeDetailEffect {
    /** Show a short message (snackbar). */
    data class Message(@StringRes val text: Int) : RecipeDetailEffect
}
