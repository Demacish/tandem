package casa.moller.tandem.feature.shell.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import casa.moller.tandem.core.designsystem.icon.Add
import casa.moller.tandem.core.designsystem.icon.Remove
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.settings.CookTextScale
import casa.moller.tandem.core.model.settings.ThemeMode
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.ui.GroupDivider
import casa.moller.tandem.feature.shell.ui.SettingsGroup
import kotlin.math.roundToInt

/**
 * Mer → Utseende (plan 6.2 "Utseende", 6.4): the theme for the whole app (applied at once),
 * dynamic colour, cook mode's text size and "cook mode always dark".
 */
@Composable
internal fun AppearanceSettingsScreen(
    state: AppearanceUiState,
    onAction: (SettingsAction) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    SettingsPage(
        title = stringResource(R.string.settings_appearance),
        onNavigateBack = onNavigateBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    ) {
        SettingsGroup {
            GroupHeading(text = stringResource(R.string.shell_theme), icon = TandemIcons.DarkMode)
            ThemeChoice(selected = state.themeMode, onSelect = { onAction(SettingsAction.SetThemeMode(it)) })
            GroupDivider()
            SwitchRow(
                title = stringResource(R.string.settings_dynamic_color),
                body = stringResource(R.string.settings_dynamic_color_body),
                checked = state.dynamicColor,
                onCheckedChange = { onAction(SettingsAction.SetDynamicColor(it)) },
            )
        }
        SettingsGroup {
            GroupHeading(text = stringResource(R.string.settings_cooking), icon = TandemIcons.Restaurant)
            CookTextSize(scale = state.cookTextScale, onChange = { onAction(SettingsAction.SetCookTextScale(it)) })
            GroupDivider()
            SwitchRow(
                title = stringResource(R.string.settings_cook_always_dark),
                body = stringResource(R.string.settings_cook_always_dark_body),
                checked = state.cookModeAlwaysDark,
                onCheckedChange = { onAction(SettingsAction.SetCookModeAlwaysDark(it)) },
            )
        }
    }
}

/** System / Light / Dark as one segmented choice (plan 6.4). */
@Composable
private fun ThemeChoice(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val spacing = TandemTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.lg, end = spacing.lg, bottom = spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Text(
            text = stringResource(R.string.settings_theme_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val modes = ThemeMode.entries
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = mode == selected,
                    onClick = { onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    modifier = Modifier.weight(1f).heightIn(min = TandemTheme.sizes.minTouchTarget),
                    label = { Text(text = stringResource(mode.label())) },
                )
            }
        }
    }
}

internal fun ThemeMode.label(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.shell_theme_system
    ThemeMode.LIGHT -> R.string.shell_theme_light
    ThemeMode.DARK -> R.string.shell_theme_dark
}

/**
 * Cook mode's text size: A− and A+ (the same steps as in cook mode) around the value and a sample
 * sentence drawn at that size, so the effect is visible before cooking.
 */
@Composable
private fun CookTextSize(scale: Float, onChange: (Float) -> Unit) {
    val spacing = TandemTheme.spacing
    val current = CookTextScale.coerce(scale)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        SettingText(
            title = stringResource(R.string.settings_cook_text_size),
            body = stringResource(R.string.settings_cook_text_size_body),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = { onChange(CookTextScale.smaller(current)) },
                enabled = current > CookTextScale.MIN,
                modifier = Modifier.size(TandemTheme.sizes.minTouchTarget),
            ) {
                Icon(TandemIcons.Remove, contentDescription = stringResource(R.string.settings_cook_text_smaller))
            }
            Text(
                text = stringResource(R.string.settings_cook_text_size_value, (current * PERCENT).roundToInt()),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            FilledTonalIconButton(
                onClick = { onChange(CookTextScale.larger(current)) },
                enabled = current < CookTextScale.MAX,
                modifier = Modifier.size(TandemTheme.sizes.minTouchTarget),
            ) {
                Icon(TandemIcons.Add, contentDescription = stringResource(R.string.settings_cook_text_larger))
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            val base = MaterialTheme.typography.bodyLarge
            Text(
                text = stringResource(R.string.settings_cook_text_size_preview),
                style = base.copy(
                    fontSize = base.fontSize.scaled(current),
                    lineHeight = base.lineHeight.scaled(current),
                ),
                modifier = Modifier.padding(spacing.lg),
            )
        }
    }
}

private fun TextUnit.scaled(factor: Float): TextUnit = if (isSpecified) this * factor else this

private const val PERCENT = 100
