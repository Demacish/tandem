package casa.moller.tandem.feature.pantry.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.Sync
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.household.HouseholdMissingCard
import casa.moller.tandem.core.ui.navigation.AppChromeDemoBanner
import casa.moller.tandem.feature.pantry.PantrySyncNotice
import casa.moller.tandem.feature.pantry.R

// Fas S (hemma-ui lane): pieces every pantry screen uses, in the style of Handla (Phase 2).

/**
 * The notices above a pantry screen: the demo banner, why a guest cannot change anything (#42),
 * no household (plan 8.6), and offline / paused with how many changes wait. A guest changes
 * nothing, so the waiting text is not shown to one.
 */
@Composable
internal fun PantryNotices(
    readOnly: Boolean,
    householdMissing: Boolean,
    syncNotice: PantrySyncNotice?,
    pendingCount: Int,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm)) {
        AppChromeDemoBanner()
        if (readOnly) {
            InfoCard(
                title = stringResource(R.string.pantry_guest_title),
                body = stringResource(R.string.pantry_guest_body),
                icon = TandemIcons.Lock,
            )
        }
        if (householdMissing) HouseholdMissingCard()
        if (!readOnly && syncNotice != null) SyncNoticeCard(notice = syncNotice, pendingCount = pendingCount, onRetry = onRetry)
    }
}

@Composable
private fun SyncNoticeCard(notice: PantrySyncNotice, pendingCount: Int, onRetry: (() -> Unit)?) {
    val (title, body) = when (notice) {
        PantrySyncNotice.OFFLINE -> R.string.pantry_offline_title to R.string.pantry_offline_body
        PantrySyncNotice.PAUSED -> R.string.pantry_paused_title to R.string.pantry_paused_body
        PantrySyncNotice.SAFE_MODE -> R.string.pantry_safe_mode_title to R.string.pantry_safe_mode_body
    }
    val pending = if (pendingCount > 0) {
        " " + pluralStringResource(R.plurals.pantry_pending_description, pendingCount, pendingCount) + "."
    } else {
        ""
    }
    CalloutCard(
        title = stringResource(title),
        body = stringResource(body) + pending,
        icon = if (notice == PantrySyncNotice.OFFLINE) TandemIcons.CloudOff else TandemIcons.Warning,
        tone = TandemTone.Neutral,
        actions = if (onRetry != null && notice != PantrySyncNotice.SAFE_MODE) {
            {
                TandemTextButton(text = stringResource(R.string.pantry_try_again), onClick = onRetry, leadingIcon = TandemIcons.Refresh)
            }
        } else {
            null
        },
    )
}

/** "3 ändringar väntar" in a top bar (nothing when none wait). */
@Composable
internal fun PendingChip(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    StatusChip(
        label = pluralStringResource(R.plurals.pantry_pending, count, count),
        contentDescription = pluralStringResource(R.plurals.pantry_pending_description, count, count),
        icon = TandemIcons.Sync,
        tone = TandemTone.Neutral,
        modifier = modifier,
    )
}

/** A heading inside a pantry screen (TalkBack heading), with an optional text at the end. */
@Composable
internal fun PantryHeading(title: String, modifier: Modifier = Modifier, end: String? = null) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (end != null) {
            Text(text = end, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A rounded group of rows on the low container colour (like Handla's aisles). */
@Composable
internal fun PantryCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(vertical = TandemTheme.spacing.xs)) { content() }
    }
}
