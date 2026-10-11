package casa.moller.tandem.core.ui.recipe

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import casa.moller.tandem.core.designsystem.icon.Star
import casa.moller.tandem.core.designsystem.icon.StarFilled
import casa.moller.tandem.core.designsystem.icon.StarHalfFilled
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.R
import kotlin.math.roundToInt

/**
 * Five stars for a rating 0..5 (Tandoor ratings may have decimals; rounded to half stars).
 * Read-only in Phase 1a; rating a recipe comes with cook mode's "Done" page (Phase 1b).
 * TalkBack reads "Rated 4,5 of 5"; an unrated recipe reads "Not rated".
 */
@Composable
fun RatingStars(rating: Double?, modifier: Modifier = Modifier, starSize: Dp = TandemTheme.sizes.iconSmall) {
    val halves = ((rating ?: 0.0).coerceIn(0.0, MAX_STARS.toDouble()) * 2).roundToInt()
    val description = if (rating == null || rating <= 0.0) {
        stringResource(R.string.ui_recipe_not_rated)
    } else {
        stringResource(R.string.ui_recipe_rating_description, formatRating(rating, currentLocale()))
    }
    Row(modifier = modifier.clearAndSetSemantics { contentDescription = description }) {
        repeat(MAX_STARS) { index ->
            val filledHalves = halves - index * 2
            val icon = when {
                filledHalves >= 2 -> TandemIcons.StarFilled
                filledHalves == 1 -> TandemIcons.StarHalfFilled
                else -> TandemIcons.Star
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (filledHalves > 0) TandemTheme.extendedColors.warning else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(starSize),
            )
        }
    }
}

private const val MAX_STARS = 5
