package casa.moller.tandem.feature.shell.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.feature.shell.R

/**
 * Frame of a settings sub-screen: the top bar with a back arrow (also in the detail pane of a
 * tablet's list-detail layout), a snackbar host and one scrolling column with readable width.
 */
@Composable
internal fun SettingsPage(
    title: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = TandemTheme.spacing
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TandemTopAppBar(title = title, onNavigateBack = onNavigateBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenHorizontal, vertical = spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = TandemTheme.sizes.maxReadingWidth)
                    .fillMaxWidth()
                    .padding(bottom = spacing.xl),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
                content = content,
            )
        }
    }
}

/** Title and an optional explanation, read by TalkBack as one item. */
@Composable
internal fun SettingText(title: String, modifier: Modifier = Modifier, body: String? = null, enabled: Boolean = true) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xxs)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A setting that is on or off: the whole row toggles (one TalkBack item with the switch state). */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val spacing = TandemTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        SettingText(title = title, body = body, enabled = enabled, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** One choice of a single-choice group (put the rows in a `selectableGroup()`). */
@Composable
internal fun RadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    enabled: Boolean = true,
) {
    val spacing = TandemTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        SettingText(title = title, body = body, enabled = enabled, modifier = Modifier.weight(1f))
    }
}

/** A heading inside a settings group (TalkBack heading). */
@Composable
internal fun GroupHeading(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val spacing = TandemTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = spacing.lg, end = spacing.lg, top = spacing.lg, bottom = spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/** "Allowed" / "Not allowed" as a chip: colour, icon AND text (never colour alone, plan 6.6). */
@Composable
internal fun StateChip(ok: Boolean, okText: String, notOkText: String, modifier: Modifier = Modifier) {
    StatusChip(
        label = if (ok) okText else notOkText,
        icon = if (ok) TandemIcons.CheckCircle else TandemIcons.Warning,
        tone = if (ok) TandemTone.Success else TandemTone.Warning,
        modifier = modifier,
    )
}

/** The detail pane beside the More list before a page is picked (medium and expanded windows). */
@Composable
internal fun SettingsPanePlaceholder(modifier: Modifier = Modifier) {
    EmptyState(
        icon = TandemIcons.Settings,
        title = stringResource(R.string.settings_pick_one),
        body = stringResource(R.string.settings_pick_one_body),
        modifier = modifier.fillMaxSize(),
    )
}
