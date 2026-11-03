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
import casa.moller.tandem.core.model.mealplan.MealPlanEntry

/**
 * Fas 3 (dra och släpp, plan 6.2 "Lång tryckning → flytta; dra och släpp i Fas 3"): a meal card
 * or tile is a drag source (a long press lifts it; the system draws the shadow), a day in the
 * agenda and a cell of the tablet's grid are drop targets. The transfer carries only the meal's
 * id as a private MIME type, so nothing from other apps is ever dropped and nothing of ours lands
 * elsewhere. TalkBack keeps "Flytta" as a custom action (the sheet); the semantics long click
 * opens the sheet too.
 */
internal object MealDragAndDrop {
    const val MIME: String = "application/x-tandem-meal"

    /** The meal id a drop carries, or null when the drop is not one of ours. */
    fun mealIdOf(event: DragAndDropEvent): Long? {
        val clip = event.toAndroidDragEvent().clipData ?: return null
        if (!clip.description.hasMimeType(MIME)) return null
        return clip.getItemAt(0)?.text?.toString()?.toLongOrNull()
    }

    fun isMeal(event: DragAndDropEvent): Boolean = event.mimeTypes().contains(MIME)
}

/** Lifts [meal] on a long press (when [enabled]). */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.mealDragSource(meal: MealPlanEntry, enabled: Boolean): Modifier = if (!enabled) {
    this
} else {
    dragAndDropSource { _ ->
        val clip = ClipData(ClipDescription("meal", arrayOf(MealDragAndDrop.MIME)), ClipData.Item(meal.id.toString()))
        DragAndDropTransferData(clip)
    }
}

/**
 * Takes a dragged meal (when [enabled]): [onHover] says whether one is over the target (the
 * highlight), [onDrop] gets the meal's id.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.mealDropTarget(enabled: Boolean, onHover: (Boolean) -> Unit, onDrop: (mealId: Long) -> Unit): Modifier {
    if (!enabled) return this
    val currentOnHover by rememberUpdatedState(onHover)
    val currentOnDrop by rememberUpdatedState(onDrop)
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                currentOnHover(false)
                val id = MealDragAndDrop.mealIdOf(event) ?: return false
                currentOnDrop(id)
                return true
            }

            override fun onEntered(event: DragAndDropEvent) = currentOnHover(true)

            override fun onExited(event: DragAndDropEvent) = currentOnHover(false)

            override fun onEnded(event: DragAndDropEvent) = currentOnHover(false)
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { MealDragAndDrop.isMeal(it) }, target = target)
}
