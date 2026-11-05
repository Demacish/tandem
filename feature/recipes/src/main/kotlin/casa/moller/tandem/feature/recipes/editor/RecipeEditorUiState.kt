package casa.moller.tandem.feature.recipes.editor

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import casa.moller.tandem.core.model.recipe.DraftKeyword
import casa.moller.tandem.core.model.recipe.RecipeDraft

/**
 * What the recipe editor shows (Fas 3, redigerare lane; #22, plan §8.9; the Stitch sketch
 * `redigera_recept_kr_mig_svamppasta`), built by [RecipeEditorViewModel].
 *
 * @property loaded the draft is ready (a stored recipe was read, or a new one started).
 * @property isNew a new recipe ("Nytt recept") rather than an edit.
 * @property draft what is being edited; amounts are kept as text in [amountTexts] while typing.
 * @property amountTexts the amount fields' text by ingredient key ([amountKey]), so "1," and
 *   "0.2" stay as typed until they parse.
 * @property units the stored units, for the unit suggestions.
 * @property keywordSuggestions the stored keywords not on the recipe, for the keyword chips.
 * @property keptEdit the draft is an edit held back by a conflict ("Ändrad på två ställen"): the
 *   editor says so and offers to start from the server's version instead.
 * @property nameMissing "Spara" was tapped without a name.
 * @property unknownRecipe the recipe is not stored on the device (edit needs the detail first).
 * @property readOnly a guest: everything shown, nothing can be saved.
 */
@Immutable
data class RecipeEditorUiState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val draft: RecipeDraft = RecipeDraft(),
    val amountTexts: Map<String, String> = emptyMap(),
    val units: List<String> = emptyList(),
    val keywordSuggestions: List<DraftKeyword> = emptyList(),
    val keptEdit: Boolean = false,
    val nameMissing: Boolean = false,
    val unknownRecipe: Boolean = false,
    val saving: Boolean = false,
    val readOnly: Boolean = false,
) {
    /** The text of an amount field: what was typed, else the draft's number ("" for 0). */
    fun amountText(step: Int, ingredient: Int): String = amountTexts[amountKey(step, ingredient)] ?: run {
        val amount = draft.steps.getOrNull(step)?.ingredients?.getOrNull(ingredient)?.amount ?: 0.0
        if (amount == 0.0) "" else amount.toString().removeSuffix(".0")
    }

    companion object {
        fun amountKey(step: Int, ingredient: Int): String = "$step:$ingredient"
    }
}

/** One change of the form; indices are positions in [RecipeDraft.steps] and a step's ingredients. */
sealed interface RecipeEditorAction {
    data class SetName(val name: String) : RecipeEditorAction

    data class SetDescription(val description: String) : RecipeEditorAction

    data class SetServings(val servings: Int) : RecipeEditorAction

    data class SetServingsText(val text: String) : RecipeEditorAction

    data class SetWorkingMinutes(val minutes: Int) : RecipeEditorAction

    data class SetWaitingMinutes(val minutes: Int) : RecipeEditorAction

    data class SetSourceUrl(val url: String) : RecipeEditorAction

    /** Adds a keyword: a suggestion (with its id) or a new one typed by name. */
    data class AddKeyword(val keyword: DraftKeyword) : RecipeEditorAction

    data class RemoveKeyword(val index: Int) : RecipeEditorAction

    data object AddStep : RecipeEditorAction

    data class RemoveStep(val step: Int) : RecipeEditorAction

    /** Moves a step one place up ([up]) or down. */
    data class MoveStep(val step: Int, val up: Boolean) : RecipeEditorAction

    data class SetStepName(val step: Int, val name: String) : RecipeEditorAction

    data class SetStepInstruction(val step: Int, val instruction: String) : RecipeEditorAction

    data class SetStepMinutes(val step: Int, val minutes: Int) : RecipeEditorAction

    /** "Ny ingrediens": an empty line, or one parsed from [text] ("2 dl grädde, kall"). */
    data class AddIngredient(val step: Int, val text: String = "") : RecipeEditorAction

    /** A heading line among the ingredients ("Deg", "Fyllning"). */
    data class AddHeader(val step: Int) : RecipeEditorAction

    data class RemoveIngredient(val step: Int, val ingredient: Int) : RecipeEditorAction

    data class MoveIngredient(val step: Int, val ingredient: Int, val up: Boolean) : RecipeEditorAction

    /** The amount as typed (decimal comma or point; blank = no amount). */
    data class SetIngredientAmount(val step: Int, val ingredient: Int, val text: String) : RecipeEditorAction

    data class SetIngredientUnit(val step: Int, val ingredient: Int, val unit: String) : RecipeEditorAction

    data class SetIngredientFood(val step: Int, val ingredient: Int, val food: String) : RecipeEditorAction

    data class SetIngredientNote(val step: Int, val ingredient: Int, val note: String) : RecipeEditorAction

    data class ToggleNoAmount(val step: Int, val ingredient: Int) : RecipeEditorAction

    /** A photo from the camera or the photo picker (`content://`), null keeps the recipe's image. */
    data class SetPhoto(val uri: String?) : RecipeEditorAction

    /** Fas 3 (import lane): the recipe gets no picture from the source after all. */
    data object RemoveWebImage : RecipeEditorAction

    /** Fas 3's leftovers: "Ta bort bilden" (false) removes the recipe's stored picture when it is saved; "Behåll bilden" (true). */
    data class KeepImage(val keep: Boolean) : RecipeEditorAction

    /** "Börja om från webbens version": drops the kept edit. */
    data object DiscardKeptEdit : RecipeEditorAction

    data object Save : RecipeEditorAction
}

sealed interface RecipeEditorEffect {
    /** Saved: show [text] (with the recipe's name) and close; [recipeId] is the recipe to show (temporary for a new one offline). */
    data class Saved(@StringRes val text: Int, val name: String, val recipeId: Long, val isNew: Boolean) : RecipeEditorEffect

    data class Message(@StringRes val text: Int) : RecipeEditorEffect
}
