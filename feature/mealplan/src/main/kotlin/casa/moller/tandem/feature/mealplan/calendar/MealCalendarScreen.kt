package casa.moller.tandem.feature.mealplan.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemLoadingIndicator
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTonalButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.Link
import casa.moller.tandem.core.designsystem.icon.Share
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.mealplan.LocalMealCalendarProblem
import casa.moller.tandem.core.model.mealplan.MealCalendarAvailability
import casa.moller.tandem.core.model.mealplan.MealCalendarLinkBlock
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.feature.mealplan.R
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Instant

/** Test tag of the switch row "Visa i telefonens kalender". */
const val MEAL_CALENDAR_LOCAL_TAG = "mealplan:calendar:local"

/** Test tag of "Skapa länk". */
const val MEAL_CALENDAR_CREATE_LINK_TAG = "mealplan:calendar:create-link"

/** Test tag of the subscription address. */
const val MEAL_CALENDAR_ADDRESS_TAG = "mealplan:calendar:address"

/** Test tag of the scrolling column. */
const val MEAL_CALENDAR_CONTENT_TAG = "mealplan:calendar:content"

/**
 * "Matsedeln i kalendern" (Fas 5, kalender lane, plan §7 #36), stateless. One column at the reading
 * width (a detail pane beside Mer on wide windows), like the other settings pages:
 *
 * - **The phone's calendar** (option b): the switch, what it does, when it was last written and
 *   how many meals, "Uppdatera nu", and cards for a refused or lost calendar permission and for a
 *   calendar provider that did not answer. Guests may use it (it only reads what is stored).
 * - **The subscription link** (option a): what it is and that anyone with it can read the plan;
 *   "Skapa länk", then the address (selectable) with "Kopiera", "Dela", "Öppna i kalenderapp"
 *   (only when an app opens `webcal://`), "Ny länk" and "Stäng av" (both ask first). A guest or a
 *   server that cannot take the key in the address gets an explanation instead.
 * - **Demo mode / no account:** an explanation, no options.
 */
@Composable
fun MealCalendarScreen(
    state: MealCalendarUiState,
    onAction: (MealCalendarAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val spacing = TandemTheme.spacing
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TandemTopAppBar(title = stringResource(R.string.calendar_title), onNavigateBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.loading) {
            LoadingState(modifier = Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .testTag(MEAL_CALENDAR_CONTENT_TAG)
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
                Text(
                    text = stringResource(R.string.calendar_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (state.availability) {
                    MealCalendarAvailability.DEMO -> InfoCard(
                        title = stringResource(R.string.calendar_demo_title),
                        body = stringResource(R.string.calendar_demo_body),
                    )

                    MealCalendarAvailability.NO_ACCOUNT, MealCalendarAvailability.LOADING -> InfoCard(
                        title = stringResource(R.string.calendar_no_account_title),
                        body = stringResource(R.string.calendar_no_account_body),
                    )

                    MealCalendarAvailability.AVAILABLE -> {
                        LocalGroup(state = state, onAction = onAction)
                        LinkGroup(state = state, onAction = onAction)
                    }
                }
            }
        }
    }
    val confirm = state.confirm
    if (confirm != null) ConfirmDialog(confirm = confirm, onAction = onAction)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalGroup(state: MealCalendarUiState, onAction: (MealCalendarAction) -> Unit) {
    val spacing = TandemTheme.spacing
    val local = state.local
    val running = state.busy == MealCalendarBusy.LOCAL
    Group {
        GroupHeading(stringResource(R.string.calendar_local_heading), TandemIcons.CalendarMonth)
        SwitchRow(
            title = stringResource(R.string.calendar_local_switch),
            body = stringResource(R.string.calendar_local_body, stringResource(R.string.calendar_local_name)),
            checked = local.enabled,
            enabled = state.busy == null,
            onChange = { onAction(MealCalendarAction.SetLocal(it)) },
            modifier = Modifier.testTag(MEAL_CALENDAR_LOCAL_TAG),
        )
        if (local.enabled) {
            Divider()
            Column(
                modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                val written = local.lastWritten
                val status = when {
                    running || written == null -> stringResource(R.string.calendar_local_waiting)
                    else -> pluralStringResource(R.plurals.calendar_local_status, local.events, local.events, dateTime(written))
                }
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    TandemTonalButton(
                        text = stringResource(R.string.calendar_local_refresh),
                        onClick = { onAction(MealCalendarAction.RefreshLocal) },
                        leadingIcon = TandemIcons.Refresh,
                        enabled = state.busy == null,
                    )
                    if (running) TandemLoadingIndicator()
                }
                Text(
                    text = stringResource(R.string.calendar_local_off_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    when {
        local.enabled && local.problem == LocalMealCalendarProblem.PERMISSION -> CalloutCard(
            title = stringResource(R.string.calendar_permission_lost_title),
            body = stringResource(R.string.calendar_permission_lost_body),
            icon = TandemIcons.Warning,
            tone = TandemTone.Warning,
        ) {
            PermissionButtons(onAction)
        }

        local.enabled && local.problem == LocalMealCalendarProblem.PROVIDER -> CalloutCard(
            title = stringResource(R.string.calendar_provider_title),
            body = stringResource(R.string.calendar_provider_body),
            icon = TandemIcons.Warning,
            tone = TandemTone.Warning,
        )

        state.permissionDenied -> CalloutCard(
            title = stringResource(R.string.calendar_permission_title),
            body = stringResource(R.string.calendar_permission_body),
            icon = TandemIcons.Info,
            tone = TandemTone.Neutral,
        ) {
            PermissionButtons(onAction)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkGroup(state: MealCalendarUiState, onAction: (MealCalendarAction) -> Unit) {
    val spacing = TandemTheme.spacing
    val link = state.link
    when (link.block) {
        MealCalendarLinkBlock.GUEST -> return InfoCard(
            title = stringResource(R.string.calendar_link_guest_title),
            body = stringResource(R.string.calendar_link_guest_body),
            icon = TandemIcons.Link,
        )

        MealCalendarLinkBlock.UNSUPPORTED -> return InfoCard(
            title = stringResource(R.string.calendar_link_unsupported_title),
            body = stringResource(R.string.calendar_link_unsupported_body),
            icon = TandemIcons.Link,
        )

        null -> Unit
    }
    val idle = state.busy == null
    Group {
        GroupHeading(stringResource(R.string.calendar_link_heading), TandemIcons.Link)
        Column(
            modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, bottom = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(text = stringResource(R.string.calendar_link_body), style = MaterialTheme.typography.bodyMedium)
            val url = link.url
            if (url == null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    TandemPrimaryButton(
                        text = stringResource(R.string.calendar_link_create),
                        onClick = { onAction(MealCalendarAction.CreateLink) },
                        leadingIcon = TandemIcons.Link,
                        enabled = idle,
                        modifier = Modifier.testTag(MEAL_CALENDAR_CREATE_LINK_TAG),
                    )
                    if (state.busy == MealCalendarBusy.LINK) TandemLoadingIndicator()
                }
            } else {
                Address(url = url, expires = link.expires)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    TandemTonalButton(
                        text = stringResource(R.string.calendar_link_copy),
                        onClick = { onAction(MealCalendarAction.CopyLink) },
                    )
                    TandemTonalButton(
                        text = stringResource(R.string.calendar_link_share),
                        onClick = { onAction(MealCalendarAction.ShareLink) },
                        leadingIcon = TandemIcons.Share,
                    )
                    if (state.canOpenInCalendarApp) {
                        TandemTonalButton(
                            text = stringResource(R.string.calendar_link_open),
                            onClick = { onAction(MealCalendarAction.OpenInCalendarApp) },
                            leadingIcon = TandemIcons.OpenInNew,
                        )
                    }
                }
                if (!state.canOpenInCalendarApp) {
                    Text(
                        text = stringResource(R.string.calendar_link_no_app),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    TandemSecondaryButton(
                        text = stringResource(R.string.calendar_link_replace),
                        onClick = { onAction(MealCalendarAction.AskReplace) },
                        leadingIcon = TandemIcons.Refresh,
                        enabled = idle,
                    )
                    TandemTextButton(
                        text = stringResource(R.string.calendar_link_disable),
                        onClick = { onAction(MealCalendarAction.AskDisable) },
                        enabled = idle,
                    )
                    if (state.busy == MealCalendarBusy.LINK) TandemLoadingIndicator()
                }
            }
        }
    }
    InfoCard(
        title = stringResource(R.string.calendar_link_warning_title),
        body = stringResource(R.string.calendar_link_warning_body),
        icon = TandemIcons.Warning,
        tone = TandemTone.Warning,
    )
}

/** "Tillåt" (the system dialog again) and "Öppna inställningar" (the app's page, after "never ask again"). */
@Composable
private fun PermissionButtons(onAction: (MealCalendarAction) -> Unit) {
    TandemTonalButton(text = stringResource(R.string.calendar_permission_allow), onClick = { onAction(MealCalendarAction.AskPermission) })
    TandemSecondaryButton(
        text = stringResource(R.string.calendar_permission_settings),
        onClick = { onAction(MealCalendarAction.OpenAppSettings) },
    )
}

/** The address, selectable (long press copies a part), with its expiry. */
@Composable
private fun Address(url: String, expires: Instant?) {
    val spacing = TandemTheme.spacing
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                text = stringResource(R.string.calendar_link_address),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(MEAL_CALENDAR_ADDRESS_TAG),
                )
            }
            if (expires != null) {
                Text(
                    text = stringResource(R.string.calendar_link_expires, date(expires)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConfirmDialog(confirm: LinkConfirm, onAction: (MealCalendarAction) -> Unit) {
    val (title, body, yes) = when (confirm) {
        LinkConfirm.REPLACE -> Triple(R.string.calendar_replace_title, R.string.calendar_replace_body, R.string.calendar_link_replace)
        LinkConfirm.DISABLE -> Triple(R.string.calendar_disable_title, R.string.calendar_disable_body, R.string.calendar_link_disable)
    }
    AlertDialog(
        onDismissRequest = { onAction(MealCalendarAction.Dismiss) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = { TextButton(onClick = { onAction(MealCalendarAction.Confirm) }) { Text(stringResource(yes)) } },
        dismissButton = {
            TextButton(onClick = { onAction(MealCalendarAction.Dismiss) }) { Text(stringResource(R.string.calendar_cancel)) }
        },
    )
}

/** One switch; TalkBack reads title, body and state as one item with the role "switch". */
@Composable
private fun SwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TandemTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(text = body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
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

/** "13 nov. 2026 14:05" in the app's language and the phone's zone. */
@Composable
private fun dateTime(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .format(java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(ZoneId.systemDefault()))
}

/** "13 nov. 2027" in the app's language and the phone's zone. */
@Composable
private fun date(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)
        .format(java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(ZoneId.systemDefault()))
}
