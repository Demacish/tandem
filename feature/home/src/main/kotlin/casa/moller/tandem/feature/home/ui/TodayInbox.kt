package casa.moller.tandem.feature.home.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.inbox.InboxEntry
import casa.moller.tandem.core.ui.layout.MediaRow
import casa.moller.tandem.core.ui.layout.scaledMaxLines
import casa.moller.tandem.core.ui.recipe.RecipeImage
import casa.moller.tandem.feature.home.R
import casa.moller.tandem.feature.home.TodayInboxState

/** Test tag of "Nytt i inkorgen". */
const val TODAY_INBOX_TAG = "home:inbox"

/**
 * Fas 5 (rss lane, plan §7 #35): "Nytt i inkorgen" on Idag: the newest recipe posts not imported yet
 * (at most three), each one TalkBack item that opens its preview in the inbox on Recept; "Öppna
 * inkorgen" beside the heading. Hidden when the inbox is not set up, turned off in "Anpassa Idag", or
 * when nothing is new.
 */
@Composable
internal fun TodayInboxCard(inbox: TodayInboxState, horizontal: Dp, onOpenInbox: (entryId: Long?) -> Unit, modifier: Modifier = Modifier) {
    if (!inbox.visible) return
    val spacing = TandemTheme.spacing
    Column(modifier = modifier.testTag(TODAY_INBOX_TAG), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        CarouselHeading(
            title = stringResource(R.string.home_inbox_title),
            subtitle = pluralStringResource(R.plurals.home_inbox_subtitle, inbox.newCount, inbox.newCount),
            horizontal = horizontal,
            actionText = stringResource(R.string.home_inbox_all),
            onAction = { onOpenInbox(null) },
        )
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(vertical = spacing.xs)) {
                inbox.entries.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InboxPostRow(entry = entry, onOpen = { onOpenInbox(entry.id) })
                }
            }
        }
    }
}

@Composable
private fun InboxPostRow(entry: InboxEntry, onOpen: () -> Unit) {
    val spacing = TandemTheme.spacing
    MediaRow(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.minTouchTarget)
            .clickable(onClick = onOpen)
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        leading = { RecipeImage(imageUrl = entry.imageUrl, modifier = Modifier.size(THUMBNAIL).clip(MaterialTheme.shapes.small)) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = scaledMaxLines(2),
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.feedTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailing = {},
    )
}

private val THUMBNAIL = 56.dp
