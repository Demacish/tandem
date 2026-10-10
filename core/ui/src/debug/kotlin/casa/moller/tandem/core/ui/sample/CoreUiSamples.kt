package casa.moller.tandem.core.ui.sample

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextField
import casa.moller.tandem.core.designsystem.component.TandemTextFieldType
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.preview.ThemePreviews
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.AdaptiveContentCard
import casa.moller.tandem.core.ui.AppStatus
import casa.moller.tandem.core.ui.AppStatusChip
import casa.moller.tandem.core.ui.DemoBanner
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.ErrorState
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.NoTandoorCallout
import casa.moller.tandem.core.ui.R

/*
 * Samples for @Preview and the Roborazzi screenshot tests in src/test. Debug source set only.
 */

/** Test tag of the sample root; screenshot tests capture this node. */
const val UI_SAMPLE_ROOT_TAG = "ui-sample-root"

/** Theme + background + padding around a sample. */
@Composable
fun UiSampleSurface(darkTheme: Boolean = isSystemInDarkTheme(), fillScreen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    TandemTheme(darkTheme = darkTheme) {
        Surface(
            modifier = Modifier
                .testTag(UI_SAMPLE_ROOT_TAG)
                .then(if (fillScreen) Modifier.fillMaxSize() else Modifier),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(if (fillScreen) 0.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun DemoSample() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ui_sample_greeting),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            AppStatusChip(AppStatus.Demo)
        }
        DemoBanner(onConnect = {}, onDismiss = {})
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppStatusChip(AppStatus.Demo)
            AppStatusChip(AppStatus.Offline)
            AppStatusChip(AppStatus.SafeMode)
        }
        NoTandoorCallout(onTryDemo = {}, onLearnMore = {})
    }
}

@Composable
internal fun EmptyStateSample() {
    EmptyState(
        icon = TandemIcons.MenuBook,
        title = stringResource(R.string.ui_sample_empty_title),
        body = stringResource(R.string.ui_sample_empty_body),
    ) {
        TandemPrimaryButton(
            text = stringResource(R.string.ui_sample_empty_import),
            onClick = {},
            leadingIcon = TandemIcons.ContentPaste,
            modifier = Modifier.fillMaxWidth(),
        )
        TandemSecondaryButton(
            text = stringResource(R.string.ui_sample_empty_samples),
            onClick = {},
            leadingIcon = TandemIcons.Science,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun ErrorStateSample() {
    ErrorState(
        title = stringResource(R.string.ui_sample_error_title),
        body = stringResource(R.string.ui_sample_error_body),
        details = stringResource(R.string.ui_sample_error_details),
        onRetry = {},
        secondaryAction = {
            TandemSecondaryButton(
                text = stringResource(R.string.ui_sample_try_demo_instead),
                onClick = {},
                leadingIcon = TandemIcons.Science,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

@Composable
internal fun LoadingStateSample() {
    LoadingState(message = stringResource(R.string.ui_sample_loading))
}

/** Login-like form inside [AdaptiveContentCard]: full width on phones, a card on tablets. */
@Composable
internal fun AdaptiveContentCardSample() {
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AdaptiveContentCard {
        Text(
            text = stringResource(R.string.ui_sample_login_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.ui_sample_login_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TandemTextField(
            value = user,
            onValueChange = { user = it },
            label = stringResource(R.string.ui_sample_username),
            type = TandemTextFieldType.Username,
            modifier = Modifier.fillMaxWidth(),
        )
        TandemTextField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.ui_sample_password),
            type = TandemTextFieldType.Password,
            imeAction = ImeAction.Done,
            modifier = Modifier.fillMaxWidth(),
        )
        TandemPrimaryButton(
            text = stringResource(R.string.ui_sample_login),
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@ThemePreviews
@Composable
private fun DemoPreview() = UiSampleSurface { DemoSample() }

@ThemePreviews
@Composable
private fun EmptyStatePreview() = UiSampleSurface { EmptyStateSample() }

@ThemePreviews
@Composable
private fun ErrorStatePreview() = UiSampleSurface { ErrorStateSample() }

@ThemePreviews
@Composable
private fun LoadingStatePreview() = UiSampleSurface { LoadingStateSample() }

@Preview(name = "Phone", widthDp = 411, heightDp = 891)
@Preview(name = "Tablet", widthDp = 1280, heightDp = 800)
@Composable
private fun AdaptiveContentCardPreview() = UiSampleSurface(fillScreen = true) { AdaptiveContentCardSample() }
