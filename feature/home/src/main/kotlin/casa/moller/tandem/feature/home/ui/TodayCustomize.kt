package casa.moller.tandem.feature.home.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.icon.ArrowDownward
import casa.moller.tandem.core.designsystem.icon.ArrowUpward
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.home.HomeLayout
import casa.moller.tandem.core.model.home.HomeSectionType
import casa.moller.tandem.core.ui.accessibility.announce
import casa.moller.tandem.feature.home.HomeAction
import casa.moller.tandem.feature.home.R

/**
 * Fas 3's leftovers (plan §10 Fas 3 "Hemsektioner som användaren konfigurerar"): "Anpassa Idag",
 * a sheet that shows or hides each recipe carousel and moves it up or down. Saved on the device
 * at once (no server call); "Återställ" brings back the default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TodayCustomizeSheet(layout: HomeLayout, onAction: (HomeAction) -> Unit) {
    ModalBottomSheet(onDismissRequest = { onAction(HomeAction.Customize(open = false)) }) {
        TodayCustomizeContent(layout = layout, onAction = onAction)
    }
}

/** The sheet's content (also shown by the screenshot tests). */
@Composable
internal fun TodayCustomizeContent(layout: HomeLayout, onAction: (HomeAction) -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg)
            .padding(bottom = spacing.lg)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text = stringResource(R.string.home_customize_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.home_customize_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val types = layout.types
        types.forEachIndexed { index, type ->
            // Keyed by the section: focus follows it when it moves, instead of staying on a slot
            // whose buttons now act on another section.
            key(type) { CustomizeRow(type = type, index = index, count = types.size, layout = layout, onAction = onAction) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            TandemTextButton(text = stringResource(R.string.home_customize_reset), onClick = { onAction(HomeAction.ResetSections) })
            TandemPrimaryButton(
                text = stringResource(R.string.home_customize_done),
                onClick = { onAction(HomeAction.Customize(open = false)) },
            )
        }
    }
}

/**
 * One carousel: its switch and "Flytta upp/ned". TalkBack hears the new place after a move, and at
 * the top or bottom, where the pressed button turns off under the user, focus goes to the other one.
 */
@Composable
private fun CustomizeRow(type: HomeSectionType, index: Int, count: Int, layout: HomeLayout, onAction: (HomeAction) -> Unit) {
    val spacing = TandemTheme.spacing
    val title = stringResource(type.texts().first)
    val visible = layout.isVisible(type)
    val view = LocalView.current
    val resources = LocalResources.current
    val up = remember { FocusRequester() }
    val down = remember { FocusRequester() }
    var moved by remember { mutableIntStateOf(0) }
    val move: (Int) -> Unit = { delta ->
        moved = delta
        onAction(HomeAction.MoveSection(type, delta))
        view.announce(resources.getString(R.string.home_customize_position, title, index + 1 + delta, count))
    }
    LaunchedEffect(index) {
        if (moved < 0 && index == 0) runCatching { down.requestFocus() }
        if (moved > 0 && index == count - 1) runCatching { up.requestFocus() }
        moved = 0
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        // One node per carousel: "Ofta lagade, på" with the switch's role.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .toggleable(value = visible, role = Role.Switch) { onAction(HomeAction.ShowSection(type, it)) },
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = spacing.sm))
            Switch(checked = visible, onCheckedChange = null)
        }
        IconButton(onClick = { move(-1) }, enabled = index > 0, modifier = Modifier.focusRequester(up)) {
            Icon(TandemIcons.ArrowUpward, contentDescription = stringResource(R.string.home_customize_move_up, title))
        }
        IconButton(onClick = { move(1) }, enabled = index < count - 1, modifier = Modifier.focusRequester(down)) {
            Icon(TandemIcons.ArrowDownward, contentDescription = stringResource(R.string.home_customize_move_down, title))
        }
    }
}
