package casa.moller.tandem.wear.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CheckboxButton
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import casa.moller.tandem.core.watchlink.MirroredTimer
import casa.moller.tandem.core.watchlink.MirroredTimerState
import casa.moller.tandem.wear.R
import casa.moller.tandem.wear.data.WearProblem
import casa.moller.tandem.wear.shopping.WearRow
import casa.moller.tandem.wear.timer.TimerText
import java.util.Date

/**
 * The watch's one screen (plan §7 #33, B15): the phone's timers first, then the shopping list by
 * aisle in the store's walking order, checked rows last. Rotary input scrolls the list (the
 * ScalingLazyColumn's default), every row is a 48 dp+ checkbox button with one TalkBack node, and
 * the layout works on round and square watches. Stateless: [WearViewModel] holds the state.
 *
 * @param showTime the system clock on top; off in screenshot tests (it would change every minute).
 */
@Composable
fun ShoppingScreen(
    state: WearUiState,
    onAction: (WearAction) -> Unit,
    onAllowNotifications: () -> Unit,
    modifier: Modifier = Modifier,
    showTime: Boolean = true,
) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    AppScaffold(modifier = modifier, timeText = { if (showTime) TimeText() }) {
        ScreenScaffold(scrollState = listState) { contentPadding ->
            ScalingLazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
                // Rows never shrink or fade at the edges: every row stays 48 dp and readable (ATF).
                scalingParams = ScalingLazyColumnDefaults.scalingParams(edgeScale = 1f, edgeAlpha = 1f),
                // The list starts at the top (the title), not with its first row in the middle.
                autoCentering = null,
            ) {
                item(key = "title") {
                    ListHeader(modifier = Modifier.semantics { heading() }) { Text(stringResource(R.string.shopping_title)) }
                }
                timers(state, onAction, onAllowNotifications)
                when (state.mode) {
                    WearMode.LOADING -> item(key = "loading") { Loading() }

                    WearMode.NOT_PAIRED -> item(key = "pair") {
                        Notice(stringResource(R.string.pair_title), stringResource(R.string.pair_body))
                    }

                    WearMode.SIGNED_OUT -> item(key = "signed-out") {
                        Notice(stringResource(R.string.signed_out_title), stringResource(R.string.signed_out_body))
                    }

                    WearMode.READY -> list(state, onAction)
                }
            }
        }
    }
}

private fun ScalingLazyListScope.timers(state: WearUiState, onAction: (WearAction) -> Unit, onAllowNotifications: () -> Unit) {
    val shown = state.timers.filter { it.state != MirroredTimerState.FINISHED }
    if (shown.isEmpty()) return
    item(key = "timers-header") {
        ListSubHeader(modifier = Modifier.semantics { heading() }) { Text(stringResource(R.string.timers_header)) }
    }
    items(shown, key = { "timer-${it.id}" }) { timer -> TimerItem(timer, state.nowMillis, onAction) }
    if (state.askNotifications) {
        item(key = "timers-notifications") {
            OutlinedButton(
                onClick = onAllowNotifications,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.timer_notifications_allow)) },
                secondaryLabel = { Text(stringResource(R.string.timer_notifications_off), maxLines = 3) },
            )
        }
    }
}

private fun ScalingLazyListScope.list(state: WearUiState, onAction: (WearAction) -> Unit) {
    item(key = "status") { Status(state) }
    if (!state.firstSyncDone && state.list.toBuy == 0 && state.list.checked.isEmpty()) {
        if (state.problem == null) item(key = "loading") { Loading() }
    } else if (state.list.toBuy == 0) {
        item(key = "empty") {
            Text(
                text = stringResource(R.string.shopping_empty),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
    state.list.aisles.forEach { aisle ->
        item(key = "aisle-${aisle.categoryId}") {
            ListSubHeader(modifier = Modifier.semantics { heading() }) { Text(aisle.name ?: stringResource(R.string.shopping_no_aisle)) }
        }
        items(aisle.rows, key = { "row-${it.key}" }) { row -> RowButton(row, onAction) }
    }
    if (state.list.checked.isNotEmpty()) {
        item(key = "checked-header") {
            ListSubHeader(modifier = Modifier.semantics { heading() }) {
                Text(pluralStringResource(R.plurals.shopping_checked_header, state.list.checked.size, state.list.checked.size))
            }
        }
        items(state.list.checked, key = { "checked-${it.key}" }) { row -> RowButton(row, onAction) }
    }
    if (state.storeCount > 1 && state.storeName != null) {
        item(key = "store") {
            FilledTonalButton(
                onClick = { onAction(WearAction.NextStore) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.shopping_store, state.storeName)) },
                secondaryLabel = { Text(stringResource(R.string.shopping_store_change)) },
            )
        }
    }
    item(key = "refresh") {
        Button(
            onClick = { onAction(WearAction.Refresh) },
            modifier = Modifier.fillMaxWidth(),
            icon = { Icon(painterResource(R.drawable.ic_sync), contentDescription = null) },
            label = { Text(stringResource(R.string.shopping_refresh)) },
        )
    }
    if (state.accountLabel.isNotBlank()) {
        item(key = "account") {
            Text(
                text = stringResource(R.string.shopping_account, state.accountLabel),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RowButton(row: WearRow, onAction: (WearAction) -> Unit) {
    val details = listOfNotNull(
        row.quantity,
        row.recipe,
        if (row.entryCount > 1) pluralStringResource(R.plurals.shopping_merged, row.entryCount, row.entryCount) else null,
        if (row.pending) stringResource(R.string.shopping_row_waiting) else null,
    ).joinToString(" · ")
    CheckboxButton(
        checked = row.checked,
        onCheckedChange = { onAction(WearAction.Toggle(row)) },
        modifier = Modifier.fillMaxWidth(),
        secondaryLabel = if (details.isEmpty()) null else ({ Text(details, maxLines = 2) }),
        label = { Text(row.food, maxLines = 3) },
    )
}

@Composable
private fun TimerItem(timer: MirroredTimer, nowMillis: Long, onAction: (WearAction) -> Unit) {
    val left = TimerText.clock(TimerText.remainingMillis(timer, nowMillis))
    val title = listOfNotNull(timer.label, timer.recipeName).joinToString(" · ")
    when (timer.state) {
        MirroredTimerState.RINGING -> Button(
            onClick = { onAction(WearAction.StopTimer(timer.id)) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
                secondaryContentColor = MaterialTheme.colorScheme.onSecondary,
                iconColor = MaterialTheme.colorScheme.onSecondary,
            ),
            icon = { Icon(painterResource(R.drawable.ic_timer), contentDescription = null) },
            label = { Text(stringResource(R.string.timer_stop_label, timer.label), maxLines = 2) },
            secondaryLabel = { Text(stringResource(R.string.timer_ringing)) },
        )

        else -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_timer),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Column {
                Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 2)
                val time = if (timer.state == MirroredTimerState.PAUSED) R.string.timer_paused else R.string.timer_left
                Text(
                    stringResource(time, left),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Status(state: WearUiState) {
    val context = LocalContext.current
    val time = { millis: Long -> DateFormat.getTimeFormat(context).format(Date(millis)) }
    val retryAt = state.retryAtMillis
    val text = when {
        state.problem == WearProblem.OFFLINE -> stringResource(R.string.shopping_offline)
        state.problem == WearProblem.THROTTLED && retryAt != null -> stringResource(R.string.shopping_throttled, time(retryAt))
        state.problem == WearProblem.SERVER && retryAt != null -> stringResource(R.string.shopping_paused, time(retryAt))
        state.pending > 0 -> pluralStringResource(R.plurals.shopping_pending, state.pending, state.pending)
        state.lastSyncMillis != null -> stringResource(R.string.shopping_updated, time(state.lastSyncMillis))
        else -> null
    }
    val pendingLine = if (state.pending > 0 && state.problem != null) {
        pluralStringResource(R.plurals.shopping_pending, state.pending, state.pending)
    } else {
        null
    }
    val lines = listOfNotNull(text, pendingLine)
    if (lines.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (state.problem != null) {
            Icon(
                painterResource(R.drawable.ic_cloud_off),
                contentDescription = null,
                modifier = Modifier.padding(end = 6.dp).size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = lines.joinToString("\n"),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Notice(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(R.drawable.ic_phone), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Loading() {
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
        Text(stringResource(R.string.shopping_loading), style = MaterialTheme.typography.bodySmall)
    }
}
