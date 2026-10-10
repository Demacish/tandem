package casa.moller.tandem.feature.shell.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.ui.GroupDivider
import casa.moller.tandem.feature.shell.ui.SettingsGroup
import casa.moller.tandem.feature.shell.ui.ShellScreenScaffold

/**
 * Mer → Språk (plan 6.7, design inst_llningar_v_lj_spr_k with its corrections): "System
 * language" first, then each translation in its own name with how complete it is. Uses the
 * per-app language of Android, so the choice also shows in the phone's settings.
 */
@Composable
internal fun LanguageScreen(
    language: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TandemTheme.spacing
    ShellScreenScaffold(
        title = stringResource(R.string.shell_language),
        onNavigateBack = onNavigateBack,
        modifier = modifier,
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
            ) {
                SettingsGroup(modifier = Modifier.selectableGroup()) {
                    AppLanguage.entries.forEachIndexed { index, option ->
                        if (index > 0) GroupDivider()
                        LanguageOption(
                            title = languageLabel(option),
                            supporting = option.supportingText(),
                            selected = option == language,
                            onClick = { if (option != language) onLanguageSelected(option) },
                        )
                    }
                }
                InfoCard(
                    title = stringResource(R.string.shell_language_recipes_title),
                    body = stringResource(R.string.shell_language_recipes_body),
                    icon = TandemIcons.MenuBook,
                )
                Text(
                    text = stringResource(R.string.shell_language_system_settings),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = spacing.xs),
                )
            }
        }
    }
}

/** Every bundled translation is complete (CI fails on a missing string, plan 6.7). */
@Composable
private fun AppLanguage.supportingText(): String = when (this) {
    AppLanguage.SYSTEM -> stringResource(R.string.shell_language_system_body)

    AppLanguage.ENGLISH -> stringResource(
        R.string.shell_language_fully_translated,
        stringResource(R.string.shell_language_english),
    )

    AppLanguage.SWEDISH -> stringResource(
        R.string.shell_language_fully_translated,
        stringResource(R.string.shell_language_swedish),
    )
}

@Composable
private fun LanguageOption(title: String, supporting: String, selected: Boolean, onClick: () -> Unit) {
    val spacing = TandemTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
