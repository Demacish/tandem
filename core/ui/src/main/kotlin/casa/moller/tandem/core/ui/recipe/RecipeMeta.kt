package casa.moller.tandem.core.ui.recipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.Cloud
import casa.moller.tandem.core.designsystem.icon.DownloadForOfflineFilled
import casa.moller.tandem.core.designsystem.icon.StarFilled
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.R
import java.text.NumberFormat
import java.util.Locale

/**
 * "25 min", "1 h 30 min" ("1 tim 30 min" in Swedish) for a recipe or step time. Times come from
 * Tandoor in whole minutes.
 */
@Composable
fun formatMinutes(minutes: Int): String {
    val hours = minutes / MINUTES_PER_HOUR
    val rest = minutes % MINUTES_PER_HOUR
    return when {
        hours == 0 -> stringResource(R.string.ui_recipe_minutes, minutes)
        rest == 0 -> stringResource(R.string.ui_recipe_hours, hours)
        else -> stringResource(R.string.ui_recipe_hours_minutes, hours, rest)
    }
}

/** A rating such as 4.5 with one decimal in the app's locale ("4,5" in Swedish). */
fun formatRating(rating: Double, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
    minimumFractionDigits = 1
    maximumFractionDigits = 1
}.format(rating)

/** The locale of the app's current configuration (follows the per-app language). */
@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** Icon plus a short text, e.g. a clock and "25 min". TalkBack reads [contentDescription] or the text. */
@Composable
fun IconLabel(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    contentDescription: String = text,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier.clearAndSetSemantics { this.contentDescription = contentDescription },
        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(TandemTheme.sizes.iconSmall))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Total time of a recipe: clock icon and "25 min". Nothing when the time is unknown (0). */
@Composable
fun RecipeTimeLabel(totalMinutes: Int, modifier: Modifier = Modifier) {
    if (totalMinutes <= 0) return
    val text = formatMinutes(totalMinutes)
    IconLabel(
        icon = TandemIcons.Schedule,
        text = text,
        contentDescription = stringResource(R.string.ui_recipe_total_time_description, text),
        modifier = modifier,
    )
}

/** Compact rating: one filled star and "4,5". Nothing for unrated recipes. */
@Composable
fun RecipeRatingLabel(rating: Double?, modifier: Modifier = Modifier) {
    if (rating == null || rating <= 0.0) return
    val text = formatRating(rating, currentLocale())
    IconLabel(
        icon = TandemIcons.StarFilled,
        text = text,
        contentDescription = stringResource(R.string.ui_recipe_rating_description, text),
        iconTint = TandemTheme.extendedColors.warning,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/** "Downloaded": the recipe opens offline (plan 6.2, feature #5). Icon + text, never colour alone. */
@Composable
fun OfflineBadge(modifier: Modifier = Modifier) {
    StatusChip(
        label = stringResource(R.string.ui_recipe_offline_badge),
        contentDescription = stringResource(R.string.ui_recipe_offline_badge_description),
        icon = TandemIcons.DownloadForOfflineFilled,
        tone = TandemTone.Success,
        modifier = modifier,
    )
}

/** "Not downloaded": shown on dimmed recipes while offline (plan 6.2 "Offline"). */
@Composable
fun NotDownloadedBadge(modifier: Modifier = Modifier) {
    StatusChip(
        label = stringResource(R.string.ui_recipe_not_downloaded),
        contentDescription = stringResource(R.string.ui_recipe_not_downloaded_description),
        icon = TandemIcons.Cloud,
        tone = TandemTone.Neutral,
        modifier = modifier,
    )
}

/** A keyword as a small pill (not clickable). Names come from the server and are shown as they are. */
@Composable
fun KeywordPill(name: String, modifier: Modifier = Modifier, tone: TandemTone = TandemTone.Neutral) {
    Surface(
        modifier = modifier.heightIn(min = 28.dp),
        shape = CircleShape,
        color = when (tone) {
            TandemTone.Neutral -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = TandemTheme.spacing.md, vertical = 6.dp),
        )
    }
}

private const val MINUTES_PER_HOUR = 60
