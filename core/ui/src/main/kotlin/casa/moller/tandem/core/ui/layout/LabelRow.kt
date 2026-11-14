package casa.moller.tandem.core.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import kotlin.math.roundToInt

/*
 * Text never breaks inside a word (docs/design/README.md "Textstorlek och radbrytning", enhetstestet
 * 2026-11-14 B18/B22). A Row with `weight(1f)` on its label measures the trailing element FIRST, so
 * at font scale 2.0 on a 320 dp phone the label got what was left: "Ingredienser" one letter per
 * line beside "Tryck för att bocka av", "3 recept" squeezed to 16 dp beside the offline hint. The
 * layouts here give the label priority and move the other element to its own line instead.
 */

/**
 * A [label] with a [trailing] element (a hint, a count, a button, a stepper) on one row when both
 * fit, otherwise [trailing] on its own line under the label ([stackedAlignment]).
 *
 * "Fit" means: [trailing] on ONE line, and beside it the label gets its whole one-line width, or at
 * least [minLabelShare] of the row and never less than its longest word. So a short label beside a
 * short hint stays one row exactly as a `Row` with `weight(1f)` would draw it, and nothing ever
 * breaks inside a word. Like that `Row`, it takes the whole width it is given. Each slot emits one
 * element (it is wrapped in a Box); an empty slot is left out. The label comes first for TalkBack
 * in both forms.
 *
 * @param gap the space between the label and [trailing] on one row.
 * @param stackedGap the space between them when [trailing] is under the label.
 */
@Composable
fun LabelRow(
    label: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    gap: Dp = TandemTheme.spacing.sm,
    stackedGap: Dp = TandemTheme.spacing.xs,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    stackedAlignment: Alignment.Horizontal = Alignment.Start,
    minLabelShare: Float = DEFAULT_MIN_LABEL_SHARE,
) {
    val policy = remember(gap, stackedGap, verticalAlignment, stackedAlignment, minLabelShare) {
        LabelRowPolicy(
            gap = gap,
            stackedGap = stackedGap,
            verticalAlignment = verticalAlignment,
            stackedAlignment = stackedAlignment,
            minLabelShare = minLabelShare,
        )
    }
    Layout(
        contents = listOf<@Composable () -> Unit>({ Slot(label) }, { Slot(trailing) }),
        modifier = modifier,
        measurePolicy = policy,
    )
}

/**
 * A row of [leading] (a photo), [content] (texts) and [trailing] (icons, buttons) as a list row,
 * while [content] gets at least [minContentWidth] × the font scale. When it would get less (large
 * text on a narrow phone, B22 "Banan taco…", the time one word per line), [leading] and [trailing]
 * share the first line and [content] gets the whole width under them.
 *
 * @param gap the space after [leading]; [endGap] the space before [trailing] (an empty slot gets none).
 * @param minContentWidth the narrowest the texts may be beside the others at font scale 1.0; it
 *   grows with the font scale, so the switch happens before words would break.
 */
@Composable
fun MediaRow(
    leading: @Composable () -> Unit,
    content: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    gap: Dp = TandemTheme.spacing.md,
    endGap: Dp = gap,
    stackedGap: Dp = TandemTheme.spacing.sm,
    minContentWidth: Dp = DEFAULT_MIN_CONTENT_WIDTH,
) {
    val fontScale = LocalDensity.current.fontScale
    val policy = remember(gap, endGap, stackedGap, minContentWidth, fontScale) {
        MediaRowPolicy(gap = gap, endGap = endGap, stackedGap = stackedGap, minContentWidth = minContentWidth * fontScale.coerceAtLeast(1f))
    }
    Layout(
        contents = listOf<@Composable () -> Unit>({ Slot(leading) }, { Slot(content) }, { Slot(trailing) }),
        modifier = modifier,
        measurePolicy = policy,
    )
}

/**
 * [lines] at ordinary text sizes, twice as many from font scale [LARGE_FONT_SCALE] on, so a
 * recipe name on a card is not cut after two short lines at the largest text (B22).
 */
@Composable
@ReadOnlyComposable
fun scaledMaxLines(lines: Int): Int = if (LocalDensity.current.fontScale >= LARGE_FONT_SCALE) lines * 2 else lines

/** True from font scale [LARGE_FONT_SCALE] on (Android's "large" and "largest" text). */
@Composable
@ReadOnlyComposable
fun isLargeFontScale(): Boolean = LocalDensity.current.fontScale >= LARGE_FONT_SCALE

/** One slot: a Box that passes its minimum width on, so a filled label measures like `weight(1f)`. */
@Composable
private fun Slot(content: @Composable () -> Unit) {
    Box(propagateMinConstraints = true) { content() }
}

/** From this font scale on names get more lines and carousel cards grow. */
const val LARGE_FONT_SCALE = 1.5f

/** A label beside a trailing element keeps at least half the row (unless it is shorter). */
const val DEFAULT_MIN_LABEL_SHARE = 0.5f

private val DEFAULT_MIN_CONTENT_WIDTH = 120.dp

private class LabelRowPolicy(
    private val gap: Dp,
    private val stackedGap: Dp,
    private val verticalAlignment: Alignment.Vertical,
    private val stackedAlignment: Alignment.Horizontal,
    private val minLabelShare: Float,
) : MultiContentMeasurePolicy {

    override fun MeasureScope.measure(measurables: List<List<Measurable>>, constraints: Constraints): MeasureResult {
        val label = measurables[0].first()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        // Its natural size in both forms: one line for a text that fits, the buttons as they are.
        val trailing = measurables[1].first().measure(loose)
        return if (!constraints.hasBoundedWidth || fitsOnOneRow(label, trailing.width, constraints.maxWidth)) {
            measureInline(label, trailing, loose, constraints)
        } else {
            measureStacked(label.measure(loose), trailing, constraints)
        }
    }

    private fun IntrinsicMeasureScope.fitsOnOneRow(label: IntrinsicMeasurable, trailingWidth: Int, width: Int): Boolean {
        if (trailingWidth == 0) return true
        val labelWidth = label.maxIntrinsicWidth(Constraints.Infinity)
        if (labelWidth == 0) return trailingWidth <= width
        val wanted = maxOf(label.minIntrinsicWidth(Constraints.Infinity), minOf(labelWidth, (width * minLabelShare).roundToInt()))
        return width - trailingWidth - gap.roundToPx() >= wanted
    }

    private fun MeasureScope.measureInline(
        label: Measurable,
        trailing: Placeable,
        loose: Constraints,
        constraints: Constraints,
    ): MeasureResult {
        val space = if (trailing.width > 0) gap.roundToPx() else 0
        val labelMaxWidth = if (constraints.hasBoundedWidth) {
            (constraints.maxWidth - trailing.width - space).coerceAtLeast(0)
        } else {
            Constraints.Infinity
        }
        // The label fills what is left, as a weighted label did (its text aligns the same way).
        val labelPlaceable = label.measure(
            if (constraints.hasBoundedWidth) loose.copy(minWidth = labelMaxWidth, maxWidth = labelMaxWidth) else loose,
        )
        val width = rowWidth(labelPlaceable.width + space + trailing.width, constraints)
        val height = maxOf(labelPlaceable.height, trailing.height).coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(width, height) {
            labelPlaceable.placeRelative(0, verticalAlignment.align(labelPlaceable.height, height))
            trailing.placeRelative(width - trailing.width, verticalAlignment.align(trailing.height, height))
        }
    }

    private fun MeasureScope.measureStacked(label: Placeable, trailing: Placeable, constraints: Constraints): MeasureResult {
        val space = if (label.height > 0 && trailing.height > 0) stackedGap.roundToPx() else 0
        val width = rowWidth(maxOf(label.width, trailing.width), constraints)
        val height = (label.height + space + trailing.height).coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(width, height) {
            label.placeRelative(0, 0)
            trailing.placeRelative(stackedAlignment.align(trailing.width, width, layoutDirection), label.height + space)
        }
    }

    /** The whole width when it is bounded, as the `Row` with a weighted label that this replaces. */
    private fun rowWidth(content: Int, constraints: Constraints): Int =
        if (constraints.hasBoundedWidth) constraints.maxWidth else content.coerceAtLeast(constraints.minWidth)

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<List<IntrinsicMeasurable>>, height: Int): Int {
        val label = measurables[0].first().maxIntrinsicWidth(Constraints.Infinity)
        val trailing = measurables[1].first().maxIntrinsicWidth(Constraints.Infinity)
        return label + trailing + if (label > 0 && trailing > 0) gap.roundToPx() else 0
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<List<IntrinsicMeasurable>>, height: Int): Int =
        maxOf(measurables[0].first().minIntrinsicWidth(height), measurables[1].first().minIntrinsicWidth(height))

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<List<IntrinsicMeasurable>>, width: Int): Int =
        intrinsicHeight(measurables, width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<List<IntrinsicMeasurable>>, width: Int): Int =
        intrinsicHeight(measurables, width)

    private fun IntrinsicMeasureScope.intrinsicHeight(measurables: List<List<IntrinsicMeasurable>>, width: Int): Int {
        val label = measurables[0].first()
        val trailing = measurables[1].first()
        val trailingWidth = trailing.maxIntrinsicWidth(Constraints.Infinity)
        if (width == Constraints.Infinity || fitsOnOneRow(label, trailingWidth, width)) {
            val labelWidth = if (width == Constraints.Infinity) width else (width - trailingWidth - gap.roundToPx()).coerceAtLeast(0)
            return maxOf(label.maxIntrinsicHeight(labelWidth), trailing.maxIntrinsicHeight(trailingWidth))
        }
        return label.maxIntrinsicHeight(width) + stackedGap.roundToPx() + trailing.maxIntrinsicHeight(width)
    }
}

private class MediaRowPolicy(private val gap: Dp, private val endGap: Dp, private val stackedGap: Dp, private val minContentWidth: Dp) :
    MultiContentMeasurePolicy {

    override fun MeasureScope.measure(measurables: List<List<Measurable>>, constraints: Constraints): MeasureResult {
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val leading = measurables[0].first().measure(loose)
        val trailing = measurables[2].first().measure(loose)
        val sideWidth = leading.width.withGap(gap.roundToPx()) + trailing.width.withGap(endGap.roundToPx())
        val contentSpace = if (constraints.hasBoundedWidth) constraints.maxWidth - sideWidth else Constraints.Infinity
        return if (contentSpace >= minContentWidth.roundToPx()) {
            measureInline(measurables[1].first(), leading, trailing, loose, constraints, contentSpace)
        } else {
            measureStacked(measurables[1].first().measure(loose), leading, trailing, constraints)
        }
    }

    private fun Int.withGap(gapPx: Int): Int = if (this > 0) this + gapPx else 0

    private fun MeasureScope.measureInline(
        content: Measurable,
        leading: Placeable,
        trailing: Placeable,
        loose: Constraints,
        constraints: Constraints,
        contentSpace: Int,
    ): MeasureResult {
        val gapPx = gap.roundToPx()
        val contentWidth = contentSpace.coerceAtLeast(0)
        val contentPlaceable = content.measure(
            if (constraints.hasBoundedWidth) loose.copy(minWidth = contentWidth, maxWidth = contentWidth) else loose,
        )
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            leading.width.withGap(gapPx) + contentPlaceable.width + trailing.width.withGap(endGap.roundToPx())
        }
        val height = maxOf(leading.height, contentPlaceable.height, trailing.height).coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(width, height) {
            leading.placeRelative(0, Alignment.CenterVertically.align(leading.height, height))
            contentPlaceable.placeRelative(leading.width.withGap(gapPx), Alignment.CenterVertically.align(contentPlaceable.height, height))
            trailing.placeRelative(width - trailing.width, Alignment.CenterVertically.align(trailing.height, height))
        }
    }

    private fun MeasureScope.measureStacked(
        content: Placeable,
        leading: Placeable,
        trailing: Placeable,
        constraints: Constraints,
    ): MeasureResult {
        val width = constraints.maxWidth
        val top = maxOf(leading.height, trailing.height)
        val space = if (top > 0) stackedGap.roundToPx() else 0
        val height = (top + space + content.height).coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(width, height) {
            leading.placeRelative(0, Alignment.CenterVertically.align(leading.height, top))
            trailing.placeRelative(width - trailing.width, Alignment.CenterVertically.align(trailing.height, top))
            content.placeRelative(0, top + space)
        }
    }
}
