package casa.moller.tandem.feature.cook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemButtonSize
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.Check
import casa.moller.tandem.core.designsystem.icon.Cooking
import casa.moller.tandem.core.designsystem.icon.NotificationsActive
import casa.moller.tandem.core.designsystem.icon.Star
import casa.moller.tandem.core.designsystem.icon.StarFilled
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.recipe.IconLabel
import casa.moller.tandem.core.ui.recipe.RecipeImage
import casa.moller.tandem.feature.cook.CookAction
import casa.moller.tandem.feature.cook.CookLogSendState
import casa.moller.tandem.feature.cook.CookUiState
import casa.moller.tandem.feature.cook.R

/**
 * The last page, "Klart!" (plan 6.2, feature #8, Stitch kokl_ge_klart with the corrections in
 * docs/design/README.md: no quick-impression chips or "share a photo", which Tandoor does not
 * have). Servings, an optional rating, a comment, and "Spara i kokloggen", which works offline
 * (sent later) and in demo mode (stays on the device).
 */
@Composable
internal fun CookDonePage(state: CookUiState, onAction: (CookAction) -> Unit, onClose: () -> Unit) {
    val spacing = TandemTheme.spacing
    val done = state.done
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = TandemTheme.sizes.maxReadingWidth)
                .fillMaxWidth()
                .padding(horizontal = spacing.screenHorizontal, vertical = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            DoneHero(state)
            Card {
                // The recipe's own servings text ("12 bullar") like on the step pages, else "4 portioner".
                val servingsText = state.servings?.text.orEmpty()
                StepperRow(
                    label = stringResource(R.string.cook_done_servings_label),
                    valueText = if (servingsText.isNotBlank()) {
                        stringResource(R.string.cook_servings_with_text, done.servings.toString(), servingsText)
                    } else {
                        pluralStringResource(R.plurals.cook_servings, done.servings, done.servings.toString())
                    },
                    onDecrease = { onAction(CookAction.DecreaseLogServings) },
                    onIncrease = { onAction(CookAction.IncreaseLogServings) },
                    decreaseEnabled = done.canDecreaseServings,
                    decreaseDescription = stringResource(R.string.cook_fewer_servings),
                    increaseDescription = stringResource(R.string.cook_more_servings),
                )
            }
            Card { RatingPicker(rating = done.rating, onRate = { onAction(CookAction.SetRating(it)) }) }
            CommentField(initial = done.comment, onChange = { onAction(CookAction.SetComment(it)) })
            // Where the entry goes, in the app's 0.1.0 terms: sent now, or saved and sent later (and why).
            IconLabel(
                icon = when {
                    state.demo -> TandemIcons.Info
                    state.sendState == CookLogSendState.NOW -> TandemIcons.CheckCircle
                    state.sendState == CookLogSendState.OFFLINE -> TandemIcons.CloudOff
                    else -> TandemIcons.Schedule
                },
                text = stringResource(if (state.demo) R.string.cook_done_demo else state.sendState.text()),
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(COOK_DONE_SEND_STATE_TAG),
            )
            TandemPrimaryButton(
                text = stringResource(R.string.cook_done_save),
                onClick = { onAction(CookAction.SaveCookLog) },
                enabled = !done.saving,
                leadingIcon = TandemIcons.Check,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TandemTheme.sizes.kitchenTouchTarget),
            )
            TandemTextButton(
                text = stringResource(R.string.cook_done_leave),
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .heightIn(min = TandemTheme.sizes.kitchenTouchTarget),
            )
        }
    }
}

@Composable
private fun DoneHero(state: CookUiState) {
    val spacing = TandemTheme.spacing
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Icon(
                imageVector = TandemIcons.Cooking,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(TandemTheme.sizes.iconHero),
            )
            StatusChip(label = stringResource(R.string.cook_done_badge), icon = TandemIcons.CheckCircle, tone = TandemTone.Success)
            Text(
                text = stringResource(R.string.cook_done_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                RecipeImage(
                    imageUrl = state.imageUrl,
                    modifier = Modifier
                        .size(RECIPE_THUMBNAIL)
                        .clip(MaterialTheme.shapes.medium),
                )
                Text(text = state.recipeName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
            }
            Text(
                // Demo mode keeps count in the demo only; Tandoor never hears of it.
                text = stringResource(if (state.demo) R.string.cook_done_body_demo else R.string.cook_done_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.padding(horizontal = TandemTheme.spacing.lg, vertical = TandemTheme.spacing.md)) { content() }
    }
}

/**
 * 1–5 stars of 64 dp (plan 6.6), a radio group for TalkBack ("3 stjärnor, vald"). The selected
 * star again clears the rating: it is optional (null is left out of the cook log).
 */
@Composable
private fun RatingPicker(rating: Int?, onRate: (Int) -> Unit) {
    val spacing = TandemTheme.spacing
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text = stringResource(R.string.cook_done_rating_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Row(modifier = Modifier.selectableGroup()) {
            repeat(MAX_STARS) { index ->
                val stars = index + 1
                val filled = rating != null && stars <= rating
                val description = pluralStringResource(R.plurals.cook_done_star, stars, stars)
                Box(
                    modifier = Modifier
                        .size(TandemTheme.sizes.kitchenTouchTarget)
                        .selectable(selected = rating == stars, role = Role.RadioButton, onClick = { onRate(stars) })
                        .semantics { contentDescription = description },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (filled) TandemIcons.StarFilled else TandemIcons.Star,
                        contentDescription = null,
                        tint = if (filled) TandemTheme.extendedColors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(STAR_SIZE),
                    )
                }
            }
        }
        Text(
            text = if (rating == null) {
                stringResource(R.string.cook_done_rating_none)
            } else {
                stringResource(R.string.cook_done_rating_value, rating)
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The comment. The text lives in the field's own saveable state (typing never waits for the
 * ViewModel) and is reported on every change; [initial] restores it after process death.
 */
@Composable
private fun CommentField(initial: String, onChange: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        label = { Text(stringResource(R.string.cook_done_comment_label)) },
        placeholder = { Text(stringResource(R.string.cook_done_comment_placeholder)) },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The in-context question before the system's notification dialog (plan 8.7: at the first timer,
 * never at first start; no raw permission names).
 */
@Composable
internal fun NotificationPromptDialog(onContinue: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        icon = { Icon(TandemIcons.NotificationsActive, contentDescription = null) },
        title = { Text(stringResource(R.string.cook_notifications_title)) },
        text = { Text(stringResource(R.string.cook_notifications_body)) },
        // Filled and tonal: the primary text colour is too faint on the dialog's container (ATF).
        confirmButton = {
            TandemPrimaryButton(
                text = stringResource(R.string.cook_notifications_continue),
                onClick = onContinue,
                // 64 dp like every target in cook mode (plan 6.6), also in this dialog.
                size = TandemButtonSize.Kitchen,
            )
        },
        dismissButton = {
            TandemSecondaryButton(
                text = stringResource(R.string.cook_notifications_not_now),
                onClick = onNotNow,
                size = TandemButtonSize.Kitchen,
            )
        },
    )
}

/** Where a saved entry goes, in the app's 0.1.0 terms (plan 4.1). */
private fun CookLogSendState.text(): Int = when (this) {
    CookLogSendState.NOW -> R.string.cook_done_online
    CookLogSendState.OFFLINE -> R.string.cook_done_offline
    CookLogSendState.SAFE_MODE -> R.string.cook_done_safe_mode
    CookLogSendState.NEEDS_LOGIN -> R.string.cook_done_needs_login
    CookLogSendState.HELD -> R.string.cook_done_held
}

private const val MAX_STARS = 5

internal const val COOK_DONE_SEND_STATE_TAG = "cook:done-send-state"
private val STAR_SIZE = 40.dp
private val RECIPE_THUMBNAIL = 56.dp
