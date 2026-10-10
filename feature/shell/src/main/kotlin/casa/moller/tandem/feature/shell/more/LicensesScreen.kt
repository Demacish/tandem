package casa.moller.tandem.feature.shell.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.navigation.BundledLicense
import casa.moller.tandem.feature.shell.ui.GroupDivider
import casa.moller.tandem.feature.shell.ui.SectionHeader
import casa.moller.tandem.feature.shell.ui.SettingsGroup
import casa.moller.tandem.feature.shell.ui.SettingsRow
import casa.moller.tandem.feature.shell.ui.ShellScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Mer → Om Tandem → Licenser: bundled fonts and icons, and the open-source libraries. */
@Composable
internal fun LicensesScreen(onOpenLicense: (BundledLicense) -> Unit, onNavigateBack: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    ShellScreenScaffold(
        title = stringResource(R.string.shell_about_licenses),
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
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                SectionHeader(stringResource(R.string.shell_licenses_fonts))
                SettingsGroup {
                    listOf(BundledLicense.BRICOLAGE_GROTESQUE, BundledLicense.DM_SANS, BundledLicense.MATERIAL_SYMBOLS)
                        .forEachIndexed { index, license ->
                            if (index > 0) GroupDivider()
                            LicenseRow(license, onOpenLicense)
                        }
                }
                SectionHeader(stringResource(R.string.shell_licenses_libraries))
                SettingsGroup {
                    LicenseRow(BundledLicense.LIBRARIES, onOpenLicense)
                }
            }
        }
    }
}

@Composable
private fun LicenseRow(license: BundledLicense, onOpen: (BundledLicense) -> Unit) {
    SettingsRow(
        icon = if (license == BundledLicense.LIBRARIES) TandemIcons.MenuBook else TandemIcons.Info,
        title = stringResource(license.title),
        supporting = stringResource(license.licenseName),
        onClick = { onOpen(license) },
    )
}

/** The full licence text, read from core:designsystem's `assets/licenses/`. */
@Composable
internal fun LicenseTextScreen(license: BundledLicense, onNavigateBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val text by produceState<LicenseText>(LicenseText.Loading, license) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(license.assetPath).bufferedReader().use { LicenseText.Loaded(it.readText()) }
            }.getOrDefault(LicenseText.Missing)
        }
    }
    val spacing = TandemTheme.spacing
    ShellScreenScaffold(
        title = stringResource(license.title),
        onNavigateBack = onNavigateBack,
        modifier = modifier,
    ) { padding ->
        when (val current = text) {
            LicenseText.Loading -> LoadingState(modifier = Modifier.padding(padding))

            LicenseText.Missing -> Text(
                text = stringResource(R.string.shell_license_text_unavailable),
                modifier = Modifier.padding(padding).padding(spacing.screenHorizontal),
                style = MaterialTheme.typography.bodyLarge,
            )

            is LicenseText.Loaded -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screenHorizontal, vertical = spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(license.licenseName),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.widthIn(max = TandemTheme.sizes.maxReadingWidth).fillMaxWidth(),
                )
                SelectionContainer(modifier = Modifier.widthIn(max = TandemTheme.sizes.maxReadingWidth)) {
                    Text(
                        text = current.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(top = spacing.md, bottom = spacing.xl),
                    )
                }
            }
        }
    }
}

private sealed interface LicenseText {
    data object Loading : LicenseText

    data object Missing : LicenseText

    data class Loaded(val text: String) : LicenseText
}
