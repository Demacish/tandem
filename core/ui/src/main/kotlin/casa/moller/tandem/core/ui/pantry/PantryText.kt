package casa.moller.tandem.core.ui.pantry

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.containerColor
import casa.moller.tandem.core.designsystem.component.contentColor
import casa.moller.tandem.core.designsystem.component.iconColor
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.pantry.InventoryEntry
import casa.moller.tandem.core.ui.R
import casa.moller.tandem.core.ui.recipe.currentLocale
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toJavaLocalDate

// Fas S (hemma-ui lane): how the pantry's best-before days and amounts read (plan §8.8 "Utgångsdatum
// som är nära visas i färg (inom 3 dagar)"). Shared by Hemma (feature:pantry), Idag's "Går ut snart"
// (feature:home) and "Tog något slut?" after "Klart!" (feature:cook), so the three say the same.

/** How close a best-before day is; the colour of [ExpiryLabel] (never the only signal: text and icon too). */
enum class ExpiryUrgency {
    /** No date. */
    NONE,

    /** More than [InventoryEntry.EXPIRING_SOON_DAYS] days left. */
    LATER,

    /** 1 to 3 days left ("går ut i morgon"). */
    SOON,

    /** The last day. */
    TODAY,

    /** Past its day ("gick ut i går"). */
    EXPIRED,
    ;

    /** Within three days or past: coloured, and lifted by "Använd upp" (#28). */
    val isUrgent: Boolean
        get() = this == SOON || this == TODAY || this == EXPIRED

    companion object {
        /** The urgency of [daysLeft] (days from today, negative = past), [NONE] without a date. */
        fun of(daysLeft: Int?): ExpiryUrgency = when {
            daysLeft == null -> NONE
            daysLeft < 0 -> EXPIRED
            daysLeft == 0 -> TODAY
            daysLeft <= InventoryEntry.EXPIRING_SOON_DAYS -> SOON
            else -> LATER
        }

        /** The urgency of [expires] seen from [today]. */
        fun of(expires: LocalDate?, today: LocalDate): ExpiryUrgency = of(expires?.let { today.daysUntil(it) })
    }
}

/** Days within which the text counts ("går ut om 5 dagar"); after that it names the day. */
private const val COUNTED_DAYS = 6

/**
 * "går ut i morgon", "gick ut i går", "går ut om 5 dagar", "bäst före 4 nov." (lower case, so it
 * also reads inside a sentence: "Grädde, 2 dl, Kyl, går ut i morgon").
 */
@Composable
fun expiryText(expires: LocalDate, today: LocalDate): String {
    val days = today.daysUntil(expires)
    return when {
        days < -1 -> pluralStringResource(R.plurals.ui_pantry_expired_days_ago, -days, -days)
        days == -1 -> stringResource(R.string.ui_pantry_expired_yesterday)
        days == 0 -> stringResource(R.string.ui_pantry_expires_today)
        days == 1 -> stringResource(R.string.ui_pantry_expires_tomorrow)
        days <= COUNTED_DAYS -> pluralStringResource(R.plurals.ui_pantry_expires_in_days, days, days)
        else -> stringResource(R.string.ui_pantry_best_before, formatDay(expires, today, currentLocale()))
    }
}

/** "4 nov." in the app's language, with the year when it is not this year's ("15 jan. 2027"). */
fun formatDay(day: LocalDate, today: LocalDate, locale: Locale): String {
    val skeleton = if (day.year == today.year) "dMMM" else "dMMMy"
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    return DateTimeFormatter.ofPattern(pattern, locale).format(day.toJavaLocalDate())
}

/** "2 dl", "1,5 l", "12 st" in the app's language (decimal comma in Swedish, at most two decimals). */
fun formatPantryAmount(amount: Double, unit: String?, locale: Locale): String {
    val number = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
    }.format(amount)
    return listOfNotNull(number, unit?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" ")
}

/** A food's name with a capital first letter, as a row title ("grädde" → "Grädde"). */
fun foodTitle(name: String, locale: Locale): String =
    name.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

/**
 * The best-before day of a pantry item: a coloured pill with an icon within three days or past it
 * (warning tone, error tone once expired), plain text with a clock otherwise. The text wraps (font
 * scale 2). Merged into the row's own description by callers; on its own TalkBack reads [text].
 */
@Composable
fun ExpiryLabel(text: String, urgency: ExpiryUrgency, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    if (!urgency.isUrgent) {
        Row(
            modifier = modifier.clearAndSetSemantics { contentDescription = text },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Icon(
                imageVector = TandemIcons.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(TandemTheme.sizes.iconSmall),
            )
            Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val tone = if (urgency == ExpiryUrgency.EXPIRED) TandemTone.Error else TandemTone.Warning
    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = text },
        shape = MaterialTheme.shapes.small,
        color = tone.containerColor(),
        contentColor = tone.contentColor(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Icon(
                imageVector = if (urgency == ExpiryUrgency.EXPIRED) TandemIcons.Warning else TandemIcons.Schedule,
                contentDescription = null,
                tint = tone.iconColor(),
                modifier = Modifier.size(TandemTheme.sizes.iconSmall),
            )
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}
