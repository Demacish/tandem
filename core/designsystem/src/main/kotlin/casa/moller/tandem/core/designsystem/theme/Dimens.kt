package casa.moller.tandem.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing scale (4 dp grid). Read it with `TandemTheme.spacing`.
 * [screenHorizontal] is the default side padding of a compact screen (design: 20-24 px).
 */
@Immutable
data class TandemSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 48.dp,
    val screenHorizontal: Dp = 20.dp,
)

/**
 * Tonal elevation levels (M3: surfaces get lighter/tinted, not shadowed). Read them with
 * `TandemTheme.elevation`. Shadows are reserved for floating elements (FAB, sheets).
 */
@Immutable
data class TandemElevation(
    val level0: Dp = 0.dp,
    val level1: Dp = 1.dp,
    val level2: Dp = 3.dp,
    val level3: Dp = 6.dp,
    val level4: Dp = 8.dp,
    val level5: Dp = 12.dp,
)

/**
 * Fixed sizes from the plan. Read them with `TandemTheme.sizes`.
 *
 * - [minTouchTarget]: 48 dp everywhere (plan 6.6).
 * - [kitchenTouchTarget]: 64 dp in cooking mode and the shopping list (wet hands, plan 6.6).
 * - [maxContentWidth]: centred content such as login or forms on large screens (plan 6.5).
 * - [maxReadingWidth]: roughly 70 characters of body text per line (plan 6.5).
 */
@Immutable
data class TandemSizes(
    val minTouchTarget: Dp = 48.dp,
    val kitchenTouchTarget: Dp = 64.dp,
    val maxContentWidth: Dp = 480.dp,
    val maxReadingWidth: Dp = 640.dp,
    val iconSmall: Dp = 18.dp,
    val icon: Dp = 24.dp,
    val iconLarge: Dp = 32.dp,
    val iconHero: Dp = 56.dp,
)
