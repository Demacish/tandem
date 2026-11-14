package casa.moller.tandem.feature.shopping.storereminder

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.toggleable
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
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTonalButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.ChevronRight
import casa.moller.tandem.core.designsystem.icon.NotificationsActive
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.shopping.StoreReminderAvailability
import casa.moller.tandem.core.model.shopping.StoreReminderProblem
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.feature.shopping.R

/** Test tag of the switch row "Påminn mig nära butiken". */
const val STORE_REMINDER_SWITCH_TAG = "shopping:store-reminder:switch"

/** Test tag of the scrolling column. */
const val STORE_REMINDER_CONTENT_TAG = "shopping:store-reminder:content"

/** Test tag of a store's row: the prefix and the store's id. */
const val STORE_REMINDER_STORE_TAG = "shopping:store-reminder:store:"

/**
 * "Påminnelse nära butiken" (Fas 5, butik lane, plan §7 #34), stateless. One column at the reading
 * width (a detail pane beside Mer on wide windows), like the other settings pages:
 *
 * - the switch (one TalkBack item with the role "switch") with what it does and how many stores
 *   are watched (a polite live region);
 * - cards for a refused or lost permission, the phone's location being off and Play services
 *   refusing, each with the way out (ask again, the app's settings, the location settings);
 * - Tandoor's stores, each one item with its place and distance; a tap opens its sheet
 *   ([StoreReminderSheet]) to set the place, the distance and the store's own switch;
 * - that the places stay on the phone.
 *
 * Demo mode, no account and a phone without Play services get an explanation instead.
 */
@Composable
fun StoreReminderScreen(
    state: StoreReminderUiState,
    onAction: (StoreReminderAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val spacing = TandemTheme.spacing
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TandemTopAppBar(title = stringResource(R.string.store_reminder_title), onNavigateBack = onBack) },
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
                .testTag(STORE_REMINDER_CONTENT_TAG)
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
                    text = stringResource(R.string.store_reminder_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (state.availability) {
                    StoreReminderAvailability.DEMO -> InfoCard(
                        title = stringResource(R.string.store_reminder_demo_title),
                        body = stringResource(R.string.store_reminder_demo_body),
                    )

                    StoreReminderAvailability.NO_ACCOUNT, StoreReminderAvailability.LOADING -> InfoCard(
                        title = stringResource(R.string.store_reminder_no_account_title),
                        body = stringResource(R.string.store_reminder_no_account_body),
                    )

                    StoreReminderAvailability.NO_PLAY_SERVICES -> InfoCard(
                        title = stringResource(R.string.store_reminder_no_play_title),
                        body = stringResource(R.string.store_reminder_no_play_body),
                    )

                    StoreReminderAvailability.AVAILABLE -> {
                        SwitchGroup(state = state, onAction = onAction)
                        Notices(state = state, onAction = onAction)
                        Stores(state = state, onAction = onAction)
                        InfoCard(
                            title = stringResource(R.string.store_reminder_privacy_title),
                            body = stringResource(R.string.store_reminder_privacy_body),
                            icon = TandemIcons.Lock,
                        )
                    }
                }
            }
        }
    }
    val sheet = state.sheet
    if (sheet != null) StoreReminderSheet(sheet = sheet, canOpenMaps = state.canOpenMaps, onAction = onAction)
    if (state.rationale) BackgroundRationale(onAction = onAction)
}

@Composable
private fun SwitchGroup(state: StoreReminderUiState, onAction: (StoreReminderAction) -> Unit) {
    val spacing = TandemTheme.spacing
    Group {
        SwitchRow(
            title = stringResource(R.string.store_reminder_switch),
            body = stringResource(R.string.store_reminder_switch_body),
            checked = state.enabled,
            enabled = !state.busy,
            onChange = { onAction(StoreReminderAction.SetEnabled(it)) },
            modifier = Modifier.testTag(STORE_REMINDER_SWITCH_TAG),
        )
        if (state.enabled) {
            GroupDivider()
            val status = when {
                state.watched > 0 -> pluralStringResource(R.plurals.store_reminder_watching, state.watched, state.watched)
                !state.hasEnabledPlace -> stringResource(R.string.store_reminder_no_places)
                else -> stringResource(R.string.store_reminder_starting)
            }
            Row(
                modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (state.busy) TandemLoadingIndicator()
            }
        }
    }
}

/** At most one card: what stands in the way right now, with the way out. */
@Composable
private fun Notices(state: StoreReminderUiState, onAction: (StoreReminderAction) -> Unit) {
    val permissions = state.permissions
    val denied = state.denied
    when {
        denied != null -> DeniedCard(step = denied, approximate = permissions.approximateOnly, onAction = onAction)

        state.enabled && state.hasEnabledPlace && (!permissions.locationOn || state.problem == StoreReminderProblem.LOCATION_OFF) ->
            CalloutCard(
                title = stringResource(R.string.store_reminder_location_off_title),
                body = stringResource(R.string.store_reminder_location_off_body),
                icon = TandemIcons.Warning,
                tone = TandemTone.Warning,
            ) {
                TandemTonalButton(
                    text = stringResource(R.string.store_reminder_open_location_settings),
                    onClick = { onAction(StoreReminderAction.OpenLocationSettings) },
                )
            }

        state.enabled && (!permissions.complete || state.problem == StoreReminderProblem.PERMISSION) -> CalloutCard(
            title = stringResource(R.string.store_reminder_lost_title),
            body = stringResource(R.string.store_reminder_lost_body),
            icon = TandemIcons.Warning,
            tone = TandemTone.Warning,
        ) {
            val missing = when {
                !permissions.precise -> PermissionStep.LOCATION
                !permissions.background -> PermissionStep.BACKGROUND
                else -> PermissionStep.NOTIFICATIONS
            }
            PermissionButtons(step = missing, onAction = onAction)
        }

        state.enabled && state.problem == StoreReminderProblem.FAILED -> InfoCard(
            title = stringResource(R.string.store_reminder_failed_title),
            body = stringResource(R.string.store_reminder_failed_body),
            icon = TandemIcons.Warning,
            tone = TandemTone.Warning,
        )
    }
}

@Composable
private fun DeniedCard(step: PermissionStep, approximate: Boolean, onAction: (StoreReminderAction) -> Unit) {
    val (title, body) = when {
        step == PermissionStep.LOCATION && approximate ->
            R.string.store_reminder_denied_precise_title to R.string.store_reminder_denied_precise_body

        step == PermissionStep.LOCATION ->
            R.string.store_reminder_denied_location_title to R.string.store_reminder_denied_location_body

        step == PermissionStep.BACKGROUND ->
            R.string.store_reminder_denied_background_title to R.string.store_reminder_denied_background_body

        else -> R.string.store_reminder_denied_notifications_title to R.string.store_reminder_denied_notifications_body
    }
    CalloutCard(title = stringResource(title), body = stringResource(body), icon = TandemIcons.Info, tone = TandemTone.Neutral) {
        PermissionButtons(step = step, onAction = onAction)
    }
}

/** "Tillåt" (the system dialog again; for "Tillåt alltid" only the settings help) and "Öppna inställningar". */
@Composable
private fun PermissionButtons(step: PermissionStep, onAction: (StoreReminderAction) -> Unit) {
    if (step != PermissionStep.BACKGROUND) {
        TandemTonalButton(text = stringResource(R.string.store_reminder_allow), onClick = { onAction(StoreReminderAction.AskAgain(step)) })
    }
    TandemSecondaryButton(
        text = stringResource(R.string.store_reminder_open_settings),
        onClick = { onAction(StoreReminderAction.OpenAppSettings) },
    )
}

@Composable
private fun Stores(state: StoreReminderUiState, onAction: (StoreReminderAction) -> Unit) {
    if (state.stores.isEmpty()) {
        InfoCard(
            title = stringResource(R.string.store_reminder_no_stores_title),
            body = stringResource(R.string.store_reminder_no_stores_body),
            icon = TandemIcons.ShoppingCart,
        )
        return
    }
    Group {
        GroupHeading(stringResource(R.string.store_reminder_stores_heading), TandemIcons.ShoppingCart)
        state.stores.forEachIndexed { index, store ->
            if (index > 0) GroupDivider()
            StoreRow(store = store, onClick = { onAction(StoreReminderAction.OpenStore(store.id)) })
        }
    }
}

/** One store: its name and where it stands; TalkBack reads both as one button ("Ange platsen"). */
@Composable
private fun StoreRow(store: StoreReminderStoreRow, onClick: () -> Unit) {
    val spacing = TandemTheme.spacing
    val spot = store.spot
    val status = when {
        spot == null -> stringResource(R.string.store_reminder_store_no_place)
        spot.enabled -> stringResource(R.string.store_reminder_store_on, spot.radiusMeters)
        else -> stringResource(R.string.store_reminder_store_off)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
            .clickable(onClickLabel = stringResource(R.string.store_reminder_store_open), role = Role.Button, onClick = onClick)
            .testTag(STORE_REMINDER_STORE_TAG + store.id)
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = TandemIcons.NotificationsActive,
            contentDescription = null,
            tint = if (spot?.enabled == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(text = store.name, style = MaterialTheme.typography.titleSmall)
            Text(text = status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(imageVector = TandemIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Before Android's settings page for "Tillåt alltid": why, and what to choose there. */
@Composable
private fun BackgroundRationale(onAction: (StoreReminderAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(StoreReminderAction.DismissRationale) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        icon = { Icon(TandemIcons.NotificationsActive, contentDescription = null) },
        title = { Text(stringResource(R.string.store_reminder_background_title)) },
        text = { Text(stringResource(R.string.store_reminder_background_body)) },
        confirmButton = {
            TextButton(onClick = { onAction(StoreReminderAction.ContinueToBackground) }) {
                Text(stringResource(R.string.store_reminder_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(StoreReminderAction.DismissRationale) }) {
                Text(stringResource(R.string.store_reminder_not_now))
            }
        },
    )
}

/** One switch; TalkBack reads title, body and state as one item with the role "switch". */
@Composable
internal fun SwitchRow(
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
internal fun GroupDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = TandemTheme.spacing.lg), color = MaterialTheme.colorScheme.outlineVariant)
}
