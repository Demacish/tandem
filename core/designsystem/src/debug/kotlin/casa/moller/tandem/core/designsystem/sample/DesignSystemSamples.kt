package casa.moller.tandem.core.designsystem.sample

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.R
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemButtonSize
import casa.moller.tandem.core.designsystem.component.TandemLoadingIndicator
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTextField
import casa.moller.tandem.core.designsystem.component.TandemTextFieldType
import casa.moller.tandem.core.designsystem.component.TandemTonalButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.preview.ThemePreviews
import casa.moller.tandem.core.designsystem.theme.TandemTheme

/*
 * Component samples for @Preview (Android Studio) and the Roborazzi screenshot tests in
 * src/test. Debug source set only, so neither the samples nor their texts ship in release.
 */

/** Test tag of the sample root; screenshot tests capture this node. */
const val SAMPLE_ROOT_TAG = "sample-root"

/** Theme + background + padding around a sample. */
@Composable
fun SampleSurface(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable ColumnScope.() -> Unit) {
    TandemTheme(darkTheme = darkTheme) {
        Surface(
            modifier = Modifier.testTag(SAMPLE_ROOT_TAG),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun TypographySample() {
    val title = stringResource(R.string.ds_sample_recipe_title)
    val body = stringResource(R.string.ds_sample_recipe_body)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.ds_sample_overline).uppercase(),
            style = TandemTheme.extendedTypography.overline,
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(title, style = MaterialTheme.typography.displaySmall)
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyLarge)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.ds_sample_step), style = TandemTheme.extendedTypography.cookStep)
    }
}

@Composable
internal fun ButtonsSample() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TandemPrimaryButton(
                text = stringResource(R.string.ds_sample_cook),
                onClick = {},
                leadingIcon = TandemIcons.Schedule,
                modifier = Modifier.weight(1f),
            )
            TandemSecondaryButton(
                text = stringResource(R.string.ds_sample_show_items),
                onClick = {},
                leadingIcon = TandemIcons.ShoppingCart,
                modifier = Modifier.weight(1f),
            )
        }
        TandemPrimaryButton(
            text = stringResource(R.string.ds_sample_connect),
            onClick = {},
            trailingIcon = TandemIcons.ArrowForward,
            modifier = Modifier.fillMaxWidth(),
        )
        TandemTonalButton(
            text = stringResource(R.string.ds_sample_add_to_list),
            onClick = {},
            leadingIcon = TandemIcons.ShoppingCart,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TandemPrimaryButton(
                text = stringResource(R.string.ds_sample_connect),
                onClick = {},
                size = TandemButtonSize.Small,
            )
            TandemPrimaryButton(
                text = stringResource(R.string.ds_sample_connect),
                onClick = {},
                enabled = false,
                size = TandemButtonSize.Small,
            )
        }
        TandemTextButton(
            text = stringResource(R.string.ds_sample_read_guide),
            onClick = {},
            leadingIcon = TandemIcons.MenuBook,
        )
    }
}

@Composable
internal fun TopAppBarSample() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TandemTopAppBar(
            title = stringResource(R.string.ds_sample_app_name),
            actions = {
                IconButton(onClick = {}) {
                    Icon(TandemIcons.Settings, contentDescription = stringResource(R.string.ds_sample_settings))
                }
            },
        )
        TandemTopAppBar(
            title = stringResource(R.string.ds_sample_screen_title),
            subtitle = stringResource(R.string.ds_sample_screen_subtitle),
            onNavigateBack = {},
        )
    }
}

@Composable
internal fun TextFieldsSample() {
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("anna") }
    var password by remember { mutableStateOf("hunter22") }
    var badUrl by remember { mutableStateOf("recept.example") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TandemTextField(
            value = url,
            onValueChange = { url = it },
            label = stringResource(R.string.ds_sample_server_label),
            type = TandemTextFieldType.Url,
            placeholder = stringResource(R.string.ds_sample_server_placeholder),
            supportingText = stringResource(R.string.ds_sample_server_help),
            modifier = Modifier.fillMaxWidth(),
        )
        TandemTextField(
            value = user,
            onValueChange = { user = it },
            label = stringResource(R.string.ds_sample_username_label),
            type = TandemTextFieldType.Username,
            modifier = Modifier.fillMaxWidth(),
        )
        TandemTextField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.ds_sample_password_label),
            type = TandemTextFieldType.Password,
            imeAction = ImeAction.Done,
            modifier = Modifier.fillMaxWidth(),
        )
        TandemTextField(
            value = badUrl,
            onValueChange = { badUrl = it },
            label = stringResource(R.string.ds_sample_server_label),
            type = TandemTextFieldType.Url,
            isError = true,
            supportingText = stringResource(R.string.ds_sample_server_error),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun CardsSample() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoCard(
            title = stringResource(R.string.ds_sample_info_title),
            body = stringResource(R.string.ds_sample_info_body),
            icon = TandemIcons.Lock,
        )
        CalloutCard(
            title = stringResource(R.string.ds_sample_callout_title),
            body = stringResource(R.string.ds_sample_callout_body),
            icon = TandemIcons.CalendarMonth,
            tone = TandemTone.Accent,
            onDismiss = {},
            actions = {
                TandemPrimaryButton(
                    text = stringResource(R.string.ds_sample_show_items),
                    onClick = {},
                    size = TandemButtonSize.Small,
                )
            },
        )
        InfoCard(
            title = stringResource(R.string.ds_sample_chip_all_at_home),
            body = stringResource(R.string.ds_sample_callout_body),
            icon = TandemIcons.CheckCircle,
            tone = TandemTone.Success,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusChip(
                label = stringResource(R.string.ds_sample_chip_all_at_home),
                icon = TandemIcons.CheckCircle,
                tone = TandemTone.Success,
            )
            StatusChip(label = stringResource(R.string.ds_sample_chip_tonight), tone = TandemTone.Accent)
            StatusChip(
                label = stringResource(R.string.ds_sample_chip_missing),
                icon = TandemIcons.Warning,
                tone = TandemTone.Warning,
            )
            StatusChip(
                label = stringResource(R.string.ds_sample_chip_missing),
                icon = TandemIcons.Error,
                tone = TandemTone.Error,
            )
            StatusChip(
                label = stringResource(R.string.ds_sample_chip_tonight),
                icon = TandemIcons.Schedule,
            )
        }
    }
}

/** Every bundled icon, to check the SVG conversion. */
@Composable
internal fun IconsSample() {
    val icons: List<ImageVector> = listOf(
        TandemIcons.Home, TandemIcons.HomeFilled, TandemIcons.Today, TandemIcons.TodayFilled,
        TandemIcons.MenuBook, TandemIcons.MenuBookFilled, TandemIcons.CalendarMonth,
        TandemIcons.CalendarMonthFilled, TandemIcons.ShoppingCart, TandemIcons.ShoppingCartFilled,
        TandemIcons.MoreHoriz, TandemIcons.Settings, TandemIcons.Language, TandemIcons.DarkMode,
        TandemIcons.Logout, TandemIcons.Info, TandemIcons.InfoFilled, TandemIcons.ArrowBack,
        TandemIcons.ArrowForward, TandemIcons.Visibility, TandemIcons.VisibilityOff,
        TandemIcons.Error, TandemIcons.ErrorFilled, TandemIcons.WifiOff, TandemIcons.CloudOff,
        TandemIcons.Lock, TandemIcons.CheckCircle, TandemIcons.CheckCircleFilled,
        TandemIcons.Science, TandemIcons.Dns, TandemIcons.Key, TandemIcons.ContentPaste,
        TandemIcons.OpenInNew, TandemIcons.Refresh, TandemIcons.Warning, TandemIcons.WarningFilled,
        TandemIcons.Schedule, TandemIcons.Close, TandemIcons.Shield,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icons.forEach { icon ->
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
internal fun LoadingSample() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TandemLoadingIndicator()
        TandemLoadingIndicator(contained = true)
    }
}

@ThemePreviews
@Composable
private fun TypographyPreview() = SampleSurface { TypographySample() }

@ThemePreviews
@Composable
private fun ButtonsPreview() = SampleSurface { ButtonsSample() }

@ThemePreviews
@Composable
private fun TopAppBarPreview() = SampleSurface { TopAppBarSample() }

@ThemePreviews
@Composable
private fun TextFieldsPreview() = SampleSurface { TextFieldsSample() }

@ThemePreviews
@Composable
private fun CardsPreview() = SampleSurface { CardsSample() }

@ThemePreviews
@Composable
private fun IconsPreview() = SampleSurface { IconsSample() }

@ThemePreviews
@Composable
private fun LoadingPreview() = SampleSurface { LoadingSample() }
