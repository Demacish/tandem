package casa.moller.tandem.core.ui.recipe

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * Full width and [ratio] (width / height), but never taller than [maxHeight]: on wide columns a
 * photo is cropped instead of growing. `heightIn` followed by `aspectRatio` cannot express this:
 * with a fixed width the ratio wins and the photo overflows its slot into the content below
 * (tablet two-column layouts of Today and the recipe detail).
 */
fun Modifier.cappedAspectRatio(ratio: Float, maxHeight: Dp): Modifier = layout { measurable, constraints ->
    val cap = maxHeight.roundToPx()
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else (cap * ratio).toInt()
    val height = (width / ratio).toInt().coerceAtMost(cap).coerceIn(constraints.minHeight, constraints.maxHeight)
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) { placeable.place(0, 0) }
}
