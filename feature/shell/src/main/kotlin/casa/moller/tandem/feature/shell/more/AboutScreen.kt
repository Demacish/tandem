package casa.moller.tandem.feature.shell.more

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemBrand
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.feature.shell.AppVersion
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.ui.SettingsGroup
import casa.moller.tandem.feature.shell.ui.SettingsRow
import casa.moller.tandem.feature.shell.ui.ShellScreenScaffold

/**
 * Mer → Om Tandem (design om_tandem_juridik with its corrections: real version, no licence
 * claim for Tandem itself until decision B4, no link to the private repository).
 */
@Composable
internal fun AboutScreen(version: AppVersion, onOpenLicenses: () -> Unit, onNavigateBack: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    ShellScreenScaffold(
        title = stringResource(R.string.shell_about),
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
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = spacing.lg)
                        .size(88.dp)
                        .clip(MaterialTheme.shapes.extraLarge)
                        .background(TandemBrand.Tomato),
                ) {
                    Image(
                        painter = painterResource(R.drawable.shell_logo),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    Text(
                        text = stringResource(R.string.shell_app_name),
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.shell_about_version_full, version.name, version.code, version.distribution),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    text = stringResource(R.string.shell_about_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                InfoCard(
                    title = stringResource(R.string.shell_about_independent_title),
                    body = stringResource(R.string.shell_about_independent_body),
                    icon = TandemIcons.Info,
                    tone = TandemTone.Accent,
                )
                InfoCard(
                    title = stringResource(R.string.shell_about_privacy_title),
                    body = stringResource(R.string.shell_about_privacy_body),
                    icon = TandemIcons.Shield,
                    tone = TandemTone.Success,
                )
                SettingsGroup {
                    SettingsRow(
                        icon = TandemIcons.MenuBook,
                        title = stringResource(R.string.shell_about_licenses),
                        supporting = stringResource(R.string.shell_about_licenses_body),
                        onClick = onOpenLicenses,
                    )
                }
            }
        }
    }
}
