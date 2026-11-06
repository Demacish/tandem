package casa.moller.tandem.feature.mealplan.ui

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.recipe.RecipeOverview

/**
 * Fas 3 (dra och släpp, plan 6.2 "Lång tryckning → flytta; dra och släpp i Fas 3"): a meal card
 * or tile is a drag source (a long press lifts it; the system draws the shadow), a day in the
 * agenda and a cell of the tablet's grid are drop targets. The transfer carries only the meal's
 * id as a private MIME type, so nothing from other apps is ever dropped and nothing of ours lands
 * elsewhere. TalkBack keeps "Flytta" as a custom action (the sheet); the semantics long click
 * opens the sheet too.
 *
 * #21 (plan 8.3): a recipe in "Vill laga" or the tablet's search is a drag source too, with its own
 * MIME type: dropping it plans a new meal. TalkBack plans the same recipes through "+" on a day.
 */
internal object MealDragAndDrop {
    const val MIME: String = "application/x-tandem-meal"
    const val RECIPE_MIME: String = "application/x-tandem-recipe"

    /** The meal id a drop carries, or null when the drop is not a meal of ours. */
    fun mealIdOf(event: DragAndDropEvent): Long? = idOf(event, MIME)

    /** The recipe id a drop carries, or null when the drop is not a recipe of ours. */
    fun recipeIdOf(event: DragAndDropEvent): Long? = idOf(event, RECIPE_MIME)

    private fun idOf(event: DragAndDropEvent, mime: String): Long? {
        val clip = event.toAndroidDragEvent().clipData ?: return null
        if (!clip.description.hasMimeType(mime)) return null
        return clip.getItemAt(0)?.text?.toString()?.toLongOrNull()
    }

    fun isOurs(event: DragAndDropEvent): Boolean = event.mimeTypes().let { MIME in it || RECIPE_MIME in it }
}

/** Lifts [meal] on a long press (when [enabled]), with a tick the hand feels. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.mealDragSource(meal: MealPlanEntry, enabled: Boolean): Modifier {
    if (!enabled) return this
    val haptics = LocalHapticFeedback.current
    return dragAndDropSource { _ ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val clip = ClipData(ClipDescription("meal", arrayOf(MealDragAndDrop.MIME)), ClipData.Item(meal.id.toString()))
        DragAndDropTransferData(clip)
    }
}

/** Lifts [recipe] on a long press (when [enabled]): "Vill laga", the tablet's search. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.recipeDragSource(recipe: RecipeOverview, enabled: Boolean): Modifier {
    if (!enabled) return this
    val haptics = LocalHapticFeedback.current
    return dragAndDropSource { _ ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val clip = ClipData(ClipDescription("recipe", arrayOf(MealDragAndDrop.RECIPE_MIME)), ClipData.Item(recipe.id.toString()))
        DragAndDropTransferData(clip)
    }
}

/**
 * Takes a dragged meal or recipe (when [enabled]): [onHover] says whether one is over the target
 * (the highlight), [onDrop] gets the meal's id and [onDropRecipe] the recipe's.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.mealDropTarget(
    enabled: Boolean,
    onHover: (Boolean) -> Unit,
    onDropRecipe: (recipeId: Long) -> Unit,
    onDrop: (mealId: Long) -> Unit,
): Modifier {
    if (!enabled) return this
    val currentOnHover by rememberUpdatedState(onHover)
    val currentOnDrop by rememberUpdatedState(onDrop)
    val currentOnDropRecipe by rememberUpdatedState(onDropRecipe)
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                currentOnHover(false)
                MealDragAndDrop.recipeIdOf(event)?.let { id ->
                    currentOnDropRecipe(id)
                    return true
                }
                val id = MealDragAndDrop.mealIdOf(event) ?: return false
                currentOnDrop(id)
                return true
            }

            override fun onEntered(event: DragAndDropEvent) = currentOnHover(true)

            override fun onExited(event: DragAndDropEvent) = currentOnHover(false)

            override fun onEnded(event: DragAndDropEvent) = currentOnHover(false)
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { MealDragAndDrop.isOurs(it) }, target = target)
}
