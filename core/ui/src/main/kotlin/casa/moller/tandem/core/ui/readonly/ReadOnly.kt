package casa.moller.tandem.core.ui.readonly

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.TandemButtonSize
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.R

// Phase 2 (books lane, feature #42 "Skrivskyddat läge för gäster", plan 8.6; Stitch
// tandem_g_stl_ge_skrivskyddat): the shared pieces every screen with editing controls uses when
// `SpacePermissions.readOnly` is true. The screens HIDE their write controls (no disabled buttons
// that cannot be explained one by one) and show [ReadOnlyBanner] once, near the top; where a single
// control would be missed (the "Klart!" page, a book's add button) [ReadOnlyNote] says why in place.

/**
 * "Du är gäst: skrivskyddat" (saffron, a lock): what a guest can and cannot do, with "Vad kan jag
 * göra?" opening [ReadOnlySheet]. Stateful only for the sheet (survives rotation).
 *
 * @param compact one line of text instead of the title and the body (lists that need the room).
 */
@Composable
fun ReadOnlyBanner(modifier: Modifier = Modifier, compact: Boolean = false) {
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    CalloutCard(
        title = stringResource(R.string.ui_read_only_title),
        body = stringResource(if (compact) R.string.ui_read_only_short else R.string.ui_read_only_body),
        modifier = modifier,
        icon = TandemIcons.Lock,
        tone = TandemTone.Warning,
        actions = {
            TandemTextButton(
                text = stringResource(R.string.ui_read_only_more),
                onClick = { sheetOpen = true },
                leadingIcon = TandemIcons.Info,
            )
        },
    )
    if (sheetOpen) ReadOnlySheet(onDismiss = { sheetOpen = false })
}

/** One line with a lock, in place of a control a guest does not get ("Gäster kan inte spara betyg"). */
@Composable
fun ReadOnlyNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = TandemIcons.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(TandemTheme.sizes.icon),
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * "Det här kan en gäst göra" (Stitch tandem_g_stl_ge_skrivskyddat, "Vad du kan göra som gäst"):
 * what works (recipes, cook mode, seeing the plan and the list) and what is switched off, and how
 * to get full access. Each row is one TalkBack node ("Laga mat med kokläget, tillåtet").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadOnlySheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ReadOnlySheetContent(onDismiss = onDismiss)
    }
}

/** The sheet's content (also for screenshots and tablets' dialogs). */
@Composable
fun ReadOnlySheetContent(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg)
            .padding(bottom = spacing.lg)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.ui_read_only_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        SectionTitle(stringResource(R.string.ui_read_only_can))
        Capability(stringResource(R.string.ui_read_only_can_recipes), allowed = true)
        Capability(stringResource(R.string.ui_read_only_can_cook), allowed = true)
        Capability(stringResource(R.string.ui_read_only_can_plan), allowed = true)
        SectionTitle(stringResource(R.string.ui_read_only_cannot))
        Capability(stringResource(R.string.ui_read_only_cannot_shopping), allowed = false)
        Capability(stringResource(R.string.ui_read_only_cannot_plan), allowed = false)
        Capability(stringResource(R.string.ui_read_only_cannot_books), allowed = false)
        Capability(stringResource(R.string.ui_read_only_cannot_log), allowed = false)
        CalloutCard(
            title = stringResource(R.string.ui_read_only_title),
            body = stringResource(R.string.ui_read_only_full_access),
            icon = TandemIcons.Key,
            tone = TandemTone.Neutral,
        )
        TandemPrimaryButton(
            text = stringResource(R.string.ui_read_only_done),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            size = TandemButtonSize.Medium,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = TandemTheme.extendedTypography.overline,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(top = TandemTheme.spacing.sm)
            .semantics { heading() },
    )
}

@Composable
private fun Capability(text: String, allowed: Boolean) {
    val state = stringResource(if (allowed) R.string.ui_read_only_allowed else R.string.ui_read_only_locked)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clearAndSetSemantics { contentDescription = "$text, $state" },
        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (allowed) TandemIcons.CheckCircle else TandemIcons.Lock,
            contentDescription = null,
            tint = if (allowed) TandemTheme.extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(TandemTheme.sizes.icon),
        )
        Text(text = text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}
