package casa.moller.tandem.feature.scan.aisettings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemLoadingIndicator
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTextField
import casa.moller.tandem.core.designsystem.component.TandemTextFieldType
import casa.moller.tandem.core.designsystem.component.TandemTonalButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.Delete
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.ai.AiKeyCheck
import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.feature.scan.R

/** Test tag of the key field. */
const val AI_KEY_FIELD_TAG = "scan:ai:key"

/** Test tag of "Spara nyckeln". */
const val AI_SAVE_KEY_TAG = "scan:ai:key:save"

/** Test tag of "Testa nyckeln". */
const val AI_CHECK_KEY_TAG = "scan:ai:key:check"

/** Test tag of "Ta bort nyckeln". */
const val AI_REMOVE_KEY_TAG = "scan:ai:key:remove"

/** Test tag of the own base URL field. */
const val AI_BASE_URL_TAG = "scan:ai:base-url"

/** Test tag of "Spara" for the model and base URL. */
const val AI_SAVE_ADVANCED_TAG = "scan:ai:advanced:save"

/**
 * Settings → AI (experimentellt), B22, stateless: what the feature does and that it is
 * experimental; the service (Claude, Gemini, OpenAI or off); the key (a password field with
 * show/hide and "Klistra in", "Spara nyckeln", "Testa nyckeln", "Ta bort nyckeln"); the model with
 * the service's default; an optional own base URL; and where the photo and the costs go. One
 * column, at most the reading width (a detail pane beside Mer on wide windows).
 *
 * @param key the key field's text: kept by the caller (never in a ViewModel state or a saved state).
 * @param onPaste fills the key field from the clipboard.
 */
@Composable
fun AiSettingsScreen(
    state: AiSettingsUiState,
    key: String,
    onKeyChange: (String) -> Unit,
    onPaste: () -> Unit,
    onAction: (AiSettingsAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val spacing = TandemTheme.spacing
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TandemTopAppBar(title = stringResource(R.string.scan_ai_settings_title), onNavigateBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
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
                Intro(state)
                ProviderGroup(state = state, onAction = onAction)
                val provider = state.provider
                if (provider != null) {
                    KeyGroup(
                        state = state,
                        provider = provider,
                        key = key,
                        onKeyChange = onKeyChange,
                        onPaste = onPaste,
                        onAction = onAction,
                    )
                    AdvancedGroup(state = state, onAction = onAction)
                }
                PrivacyGroup(state)
            }
        }
    }
    if (state.confirmRemove) {
        AlertDialog(
            onDismissRequest = { onAction(AiSettingsAction.CancelRemoveKey) },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            title = { Text(stringResource(R.string.ai_key_remove_title)) },
            text = { Text(stringResource(R.string.ai_key_remove_body)) },
            confirmButton = {
                TextButton(onClick = { onAction(AiSettingsAction.ConfirmRemoveKey) }) { Text(stringResource(R.string.ai_key_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { onAction(AiSettingsAction.CancelRemoveKey) }) { Text(stringResource(R.string.scan_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Intro(state: AiSettingsUiState) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm)) {
        StatusChip(label = stringResource(R.string.fridge_experimental), icon = TandemIcons.Science, tone = TandemTone.Warning)
    }
    InfoCard(
        title = stringResource(R.string.ai_intro_title),
        body = stringResource(R.string.ai_intro_body),
        icon = TandemIcons.Science,
        tone = TandemTone.Warning,
    )
    if (state.demo) {
        InfoCard(title = stringResource(R.string.scan_demo_title), body = stringResource(R.string.ai_demo_body))
    }
}

@Composable
private fun ProviderGroup(state: AiSettingsUiState, onAction: (AiSettingsAction) -> Unit) {
    Group(modifier = Modifier.selectableGroup()) {
        GroupHeading(stringResource(R.string.ai_provider_heading), TandemIcons.Science)
        val options = listOf<AiProvider?>(AiProvider.CLAUDE, AiProvider.GEMINI, AiProvider.OPENAI, null)
        options.forEachIndexed { index, provider ->
            if (index > 0) Divider()
            ChoiceRow(
                title = stringResource(provider?.label() ?: R.string.ai_provider_off),
                body = provider?.let { stringResource(R.string.ai_provider_default_model, it.defaultModel) }
                    ?: stringResource(R.string.ai_provider_off_body),
                selected = state.provider == provider,
                onClick = { onAction(AiSettingsAction.SelectProvider(provider)) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyGroup(
    state: AiSettingsUiState,
    provider: AiProvider,
    key: String,
    onKeyChange: (String) -> Unit,
    onPaste: () -> Unit,
    onAction: (AiSettingsAction) -> Unit,
) {
    val spacing = TandemTheme.spacing
    val service = stringResource(provider.label())
    Group {
        GroupHeading(stringResource(R.string.ai_key_heading, service), TandemIcons.Key)
        Column(
            modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, bottom = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            StatusChip(
                label = stringResource(if (state.hasKey) R.string.ai_key_stored else R.string.ai_key_missing),
                icon = if (state.hasKey) TandemIcons.CheckCircle else TandemIcons.Warning,
                tone = if (state.hasKey) TandemTone.Success else TandemTone.Warning,
            )
            TandemTextField(
                value = key,
                onValueChange = {
                    onKeyChange(it)
                    if (state.keyError != null) onAction(AiSettingsAction.KeyEdited)
                },
                label = stringResource(if (state.hasKey) R.string.ai_key_field_replace else R.string.ai_key_field),
                type = TandemTextFieldType.Password,
                leadingIcon = TandemIcons.Key,
                supportingText = state.keyError?.let { stringResource(it.message()) } ?: stringResource(R.string.ai_key_field_help),
                isError = state.keyError != null,
                imeAction = ImeAction.Done,
                keyboardActions = KeyboardActions(onDone = { if (key.isNotBlank()) onAction(AiSettingsAction.SaveKey(key)) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AI_KEY_FIELD_TAG),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                TandemPrimaryButton(
                    text = stringResource(R.string.ai_key_save),
                    onClick = { onAction(AiSettingsAction.SaveKey(key)) },
                    enabled = key.isNotBlank() && !state.savingKey,
                    leadingIcon = TandemIcons.CheckCircle,
                    modifier = Modifier.testTag(AI_SAVE_KEY_TAG),
                )
                TandemTextButton(text = stringResource(R.string.ai_key_paste), onClick = onPaste)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                TandemTonalButton(
                    text = stringResource(R.string.ai_key_check),
                    onClick = { onAction(AiSettingsAction.CheckKey) },
                    enabled = state.canCheck,
                    modifier = Modifier.testTag(AI_CHECK_KEY_TAG),
                )
                if (state.hasKey) {
                    TandemTextButton(
                        text = stringResource(R.string.ai_key_remove),
                        onClick = { onAction(AiSettingsAction.RemoveKey) },
                        leadingIcon = TandemIcons.Delete,
                        modifier = Modifier.testTag(AI_REMOVE_KEY_TAG),
                    )
                }
            }
            CheckResult(state = state, service = service)
        }
    }
}

@Composable
private fun CheckResult(state: AiSettingsUiState, service: String) {
    val spacing = TandemTheme.spacing
    Column(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (state.checking) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                modifier = Modifier.semantics(mergeDescendants = true) {},
            ) {
                TandemLoadingIndicator()
                Text(stringResource(R.string.ai_check_running, service), style = MaterialTheme.typography.bodyLarge)
            }
        }
        state.check?.let { check ->
            CalloutCard(
                title = stringResource(check.title()),
                body = stringResource(check.body(), service, state.model.ifBlank { state.defaultModel.orEmpty() }),
                icon = if (check == AiKeyCheck.WORKS) TandemIcons.CheckCircle else TandemIcons.Warning,
                tone = if (check == AiKeyCheck.WORKS) TandemTone.Success else TandemTone.Warning,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedGroup(state: AiSettingsUiState, onAction: (AiSettingsAction) -> Unit) {
    val spacing = TandemTheme.spacing
    Group {
        GroupHeading(stringResource(R.string.ai_advanced_heading), TandemIcons.Settings)
        Column(
            modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, bottom = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            TandemTextField(
                value = state.model,
                onValueChange = { onAction(AiSettingsAction.EditModel(it)) },
                label = stringResource(R.string.ai_model_field),
                type = TandemTextFieldType.Username,
                placeholder = state.defaultModel,
                supportingText = if (state.modelError) {
                    stringResource(R.string.ai_model_error)
                } else {
                    stringResource(R.string.ai_model_help, state.defaultModel.orEmpty())
                },
                isError = state.modelError,
                leadingIcon = null,
                modifier = Modifier.fillMaxWidth(),
            )
            TandemTextField(
                value = state.baseUrl,
                onValueChange = { onAction(AiSettingsAction.EditBaseUrl(it)) },
                label = stringResource(R.string.ai_base_url_field),
                type = TandemTextFieldType.Url,
                placeholder = state.officialBaseUrl,
                supportingText = if (state.baseUrlError) {
                    stringResource(R.string.ai_base_url_error)
                } else {
                    stringResource(R.string.ai_base_url_help, state.officialBaseUrl.orEmpty())
                },
                isError = state.baseUrlError,
                imeAction = ImeAction.Done,
                keyboardActions = KeyboardActions(onDone = { onAction(AiSettingsAction.SaveAdvanced) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AI_BASE_URL_TAG),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                TandemTonalButton(
                    text = stringResource(R.string.ai_advanced_save),
                    onClick = { onAction(AiSettingsAction.SaveAdvanced) },
                    enabled = state.advancedChanged,
                    modifier = Modifier.testTag(AI_SAVE_ADVANCED_TAG),
                )
                TandemTextButton(text = stringResource(R.string.ai_advanced_reset), onClick = { onAction(AiSettingsAction.ResetAdvanced) })
            }
        }
    }
}

@Composable
private fun PrivacyGroup(state: AiSettingsUiState) {
    val spacing = TandemTheme.spacing
    // With an own base URL the photo and the key go to that host, so the text names it (fix-ui).
    val service = state.customHost ?: state.provider?.let { stringResource(it.label()) } ?: stringResource(R.string.fridge_service_fallback)
    Group {
        GroupHeading(stringResource(R.string.ai_privacy_heading), TandemIcons.Info)
        Text(
            text = stringResource(R.string.ai_privacy_body, service),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, bottom = spacing.lg),
        )
    }
}

@Composable
private fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(content = content)
    }
}

@Composable
private fun GroupHeading(text: String, icon: ImageVector) {
    val spacing = TandemTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.lg, end = spacing.lg, top = spacing.lg, bottom = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text = text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = TandemTheme.spacing.lg), color = MaterialTheme.colorScheme.outlineVariant)
}

/** One radio choice; TalkBack reads title and body as one item. */
@Composable
private fun ChoiceRow(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
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
            Text(text = body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The service's name (a brand: the same in every language). */
@StringRes
internal fun AiProvider.label(): Int = when (this) {
    AiProvider.CLAUDE -> R.string.ai_provider_claude
    AiProvider.GEMINI -> R.string.ai_provider_gemini
    AiProvider.OPENAI -> R.string.ai_provider_openai
}

@StringRes
private fun AiKeyError.message(): Int = when (this) {
    AiKeyError.EMPTY -> R.string.ai_key_error_empty
    AiKeyError.INVALID -> R.string.ai_key_error_invalid
    AiKeyError.STORAGE -> R.string.ai_key_error_storage
}

@StringRes
private fun AiKeyCheck.title(): Int = when (this) {
    AiKeyCheck.WORKS -> R.string.ai_check_works_title
    AiKeyCheck.INVALID_KEY -> R.string.ai_check_invalid_title
    AiKeyCheck.NO_MODEL_ACCESS -> R.string.ai_check_model_title
    AiKeyCheck.RATE_LIMITED -> R.string.ai_check_rate_title
    AiKeyCheck.NO_NETWORK -> R.string.ai_check_network_title
    AiKeyCheck.NO_KEY -> R.string.ai_key_missing
    AiKeyCheck.DEMO_MODE -> R.string.scan_demo_title
    AiKeyCheck.FAILED -> R.string.ai_check_failed_title
}

/** The body; each takes the service (%1$s) and the model (%2$s), some use only one. */
@StringRes
private fun AiKeyCheck.body(): Int = when (this) {
    AiKeyCheck.WORKS -> R.string.ai_check_works_body
    AiKeyCheck.INVALID_KEY -> R.string.ai_check_invalid_body
    AiKeyCheck.NO_MODEL_ACCESS -> R.string.ai_check_model_body
    AiKeyCheck.RATE_LIMITED -> R.string.ai_check_rate_body
    AiKeyCheck.NO_NETWORK -> R.string.ai_check_network_body
    AiKeyCheck.NO_KEY -> R.string.ai_check_no_key_body
    AiKeyCheck.DEMO_MODE -> R.string.ai_demo_body
    AiKeyCheck.FAILED -> R.string.ai_check_failed_body
}
