package casa.moller.tandem.feature.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import casa.moller.tandem.core.designsystem.R as DesignR
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemIconHero
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.iconColor
import casa.moller.tandem.core.designsystem.icon.Sync
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.sync.SyncPhase
import casa.moller.tandem.core.ui.toErrorText
import casa.moller.tandem.feature.onboarding.ConnectedSummary
import casa.moller.tandem.feature.onboarding.FirstSyncState
import casa.moller.tandem.feature.onboarding.PhaseStatus
import casa.moller.tandem.feature.onboarding.R
import kotlin.math.roundToInt

/** Test tag of the first sync's progress bar. */
internal const val FIRST_SYNC_PROGRESS_TAG = "onboarding:first-sync-progress"

/**
 * The first sync after a login (plan 6.2 "Efter inloggning", Stitch intro_f_rsta_synk):
 * "Fetching 312 recipes…", overall progress, the phases with their state, and a way on. Leaving
 * early ("Continue in the background") never cancels the sync. After an error: what happened,
 * "Try again" and "Continue anyway".
 */
@Composable
internal fun ColumnScope.FirstSyncStep(
    sync: FirstSyncState,
    connected: ConnectedSummary?,
    onRetry: () -> Unit,
    onContinueInBackground: () -> Unit,
) {
    val spacing = TandemTheme.spacing
    val error = sync.error
    val done = sync.progress?.phase == SyncPhase.DONE
    TandemIconHero(
        icon = if (error == null) TandemIcons.Sync else TandemIcons.CloudOff,
        modifier = Modifier.align(Alignment.CenterHorizontally),
        tone = if (error == null) TandemTone.Accent else TandemTone.Warning,
    )
    if (connected != null) {
        StatusChip(
            label = stringResource(R.string.onboarding_sync_connected, connected.serverVersion),
            icon = TandemIcons.CheckCircle,
            tone = TandemTone.Success,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
    StepTitle(
        text = when {
            error != null -> stringResource(R.string.onboarding_sync_failed_title)
            done -> stringResource(R.string.onboarding_sync_done_title)
            sync.recipeCount != null -> pluralStringResource(R.plurals.onboarding_sync_title_count, sync.recipeCount, sync.recipeCount)
            else -> stringResource(R.string.onboarding_sync_title)
        },
        modifier = Modifier.fillMaxWidth(),
    )
    StepBody(
        text = if (connected != null) {
            stringResource(R.string.onboarding_sync_body_server, connected.serverAddress)
        } else {
            stringResource(R.string.onboarding_sync_body)
        },
    )
    ProgressCard(sync)
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        FirstSyncState.VisiblePhases.forEach { phase ->
            PhaseRow(phase = phase, status = sync.statusOf(phase), recipeCount = sync.recipeCount)
        }
    }
    if (error != null) {
        val text = error.toErrorText()
        CalloutCard(
            title = text.title,
            body = stringResource(R.string.onboarding_sync_failed_body, text.body),
            icon = text.icon,
            tone = TandemTone.Warning,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        TandemPrimaryButton(
            text = stringResource(DesignR.string.tandem_action_retry),
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = TandemIcons.Refresh,
        )
        TandemTextButton(
            text = stringResource(R.string.onboarding_sync_continue_anyway),
            onClick = onContinueInBackground,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    } else {
        InfoCard(
            title = stringResource(R.string.onboarding_sync_tip_title),
            body = stringResource(R.string.onboarding_sync_tip_body),
            icon = TandemIcons.Info,
            tone = TandemTone.Neutral,
        )
        TandemPrimaryButton(
            text = stringResource(R.string.onboarding_sync_continue_background),
            onClick = onContinueInBackground,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = TandemIcons.ArrowForward,
        )
    }
}

/** "SYNC STATUS 78 %", the bar and "214 of 312 recipes". */
@Composable
private fun ProgressCard(sync: FirstSyncState) {
    val spacing = TandemTheme.spacing
    val fraction = sync.overallFraction
    val progress = sync.progress
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.onboarding_sync_status),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (fraction != null) {
                    Text(
                        text = stringResource(R.string.onboarding_sync_percent, (fraction * PERCENT).roundToInt()),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(FIRST_SYNC_PROGRESS_TAG),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(FIRST_SYNC_PROGRESS_TAG),
                )
            }
            val total = progress?.total
            if (progress != null && progress.phase == SyncPhase.RECIPE_INDEX && total != null) {
                Text(
                    text = stringResource(R.string.onboarding_sync_recipes_progress, progress.done, total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One phase: icon, name and its state ("Done", "In progress", "Waiting"), read as one item. */
@Composable
private fun PhaseRow(phase: SyncPhase, status: PhaseStatus, recipeCount: Int?) {
    val spacing = TandemTheme.spacing
    val name = phaseName(phase, recipeCount)
    val statusText = stringResource(
        when (status) {
            PhaseStatus.WAITING -> R.string.onboarding_sync_status_waiting
            PhaseStatus.RUNNING -> R.string.onboarding_sync_status_running
            PhaseStatus.DONE -> R.string.onboarding_sync_status_done
            PhaseStatus.FAILED -> R.string.onboarding_sync_status_failed
        },
    )
    val description = stringResource(R.string.onboarding_sync_phase_description, name, statusText)
    val (icon, tone) = statusIcon(status)
    val active = status == PhaseStatus.RUNNING || status == PhaseStatus.FAILED
    val waiting = status == PhaseStatus.WAITING
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = description
                if (active) liveRegion = LiveRegionMode.Polite
            },
        shape = MaterialTheme.shapes.medium,
        color = if (active) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = TandemTheme.sizes.minTouchTarget)
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tone.iconColor(), modifier = Modifier.size(TandemTheme.sizes.icon))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (waiting) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StatusChip(label = statusText, tone = if (waiting) TandemTone.Neutral else tone)
        }
    }
}

@Composable
private fun phaseName(phase: SyncPhase, recipeCount: Int?): String = when (phase) {
    SyncPhase.SERVER_CHECK -> stringResource(R.string.onboarding_sync_phase_server)

    SyncPhase.REFERENCE_DATA -> stringResource(R.string.onboarding_sync_phase_reference)

    SyncPhase.RECIPE_INDEX -> if (recipeCount != null) {
        pluralStringResource(R.plurals.onboarding_sync_phase_index_count, recipeCount, recipeCount)
    } else {
        stringResource(R.string.onboarding_sync_phase_index)
    }

    SyncPhase.RECIPE_DETAILS -> stringResource(R.string.onboarding_sync_phase_details)

    SyncPhase.IMAGES -> stringResource(R.string.onboarding_sync_phase_images)

    SyncPhase.MEAL_PLAN, SyncPhase.OUTBOX, SyncPhase.DONE, SyncPhase.FAILED -> stringResource(R.string.onboarding_sync_phase_meal_plan)
}

@Composable
private fun statusIcon(status: PhaseStatus): Pair<ImageVector, TandemTone> = when (status) {
    PhaseStatus.WAITING -> TandemIcons.Schedule to TandemTone.Neutral
    PhaseStatus.RUNNING -> TandemIcons.Sync to TandemTone.Accent
    PhaseStatus.DONE -> TandemIcons.CheckCircle to TandemTone.Success
    PhaseStatus.FAILED -> TandemIcons.Warning to TandemTone.Warning
}

private const val PERCENT = 100
