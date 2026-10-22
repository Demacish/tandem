package casa.moller.tandem.feature.shell.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.AppStatus
import casa.moller.tandem.core.ui.AppStatusChip
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.ShellUiState
import casa.moller.tandem.feature.shell.navigation.ShellTab

@get:StringRes
internal val ShellTab.label: Int
    get() = when (this) {
        ShellTab.TODAY -> R.string.shell_tab_today
        ShellTab.RECIPES -> R.string.shell_tab_recipes
        ShellTab.WEEK -> R.string.shell_tab_week
        ShellTab.SHOPPING -> R.string.shell_tab_shopping
        ShellTab.MORE -> R.string.shell_tab_more
    }

@Composable
internal fun ShellTab.icon(): ImageVector = when (this) {
    ShellTab.TODAY -> TandemIcons.Today
    ShellTab.RECIPES -> TandemIcons.MenuBook
    ShellTab.WEEK -> TandemIcons.CalendarMonth
    ShellTab.SHOPPING -> TandemIcons.ShoppingCart
    ShellTab.MORE -> TandemIcons.MoreHoriz
}

/** Filled variant for the selected tab (Material: filled icon = selected, besides the indicator). */
@Composable
internal fun ShellTab.selectedIcon(): ImageVector = when (this) {
    ShellTab.TODAY -> TandemIcons.TodayFilled
    ShellTab.RECIPES -> TandemIcons.MenuBookFilled
    ShellTab.WEEK -> TandemIcons.CalendarMonthFilled
    ShellTab.SHOPPING -> TandemIcons.ShoppingCartFilled
    ShellTab.MORE -> TandemIcons.MoreHoriz
}

/**
 * Screen frame inside the shell: Tandem top bar (with the Demo / Safe mode chip on top-level
 * screens, plan 6.2) over the cream background.
 */
@Composable
internal fun ShellScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    state: ShellUiState? = null,
    onNavigateBack: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TandemTopAppBar(
                title = title,
                onNavigateBack = onNavigateBack,
                actions = {
                    when {
                        state == null -> Unit
                        state.isDemo -> AppStatusChip(AppStatus.Demo, Modifier.padding(end = TandemTheme.spacing.md))
                        state.isSafeMode -> AppStatusChip(AppStatus.SafeMode, Modifier.padding(end = TandemTheme.spacing.md))
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}

/** Section heading above a [SettingsGroup]. */
@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .padding(start = TandemTheme.spacing.xs, top = TandemTheme.spacing.sm)
            .semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Rounded group of settings rows on a tinted surface (design "mer_inst_llningar"). */
@Composable
internal fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(content = content)
    }
}

@Composable
internal fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = TandemTheme.spacing.lg),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * One tappable settings row: icon, title, optional supporting text and a chevron. TalkBack reads
 * it as one button. [selected] marks the page shown beside the list on wide windows (Phase 1b,
 * design mer_inst_llningar_surfplatta): a tinted background, and TalkBack says "selected".
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    selected: Boolean = false,
) {
    val spacing = TandemTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
            .semantics { this.selected = selected }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val colors = MaterialTheme.colorScheme
        Icon(icon, contentDescription = null, tint = if (selected) colors.onSecondaryContainer else colors.primary)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) colors.onSecondaryContainer else colors.onSurface,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = TandemIcons.ArrowForward,
            contentDescription = null,
            tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
            modifier = Modifier.size(TandemTheme.sizes.iconSmall + TandemTheme.spacing.xs),
        )
    }
}
