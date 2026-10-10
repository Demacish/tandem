package casa.moller.tandem.feature.shell.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.DemoBanner
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.ShellUiState
import casa.moller.tandem.feature.shell.navigation.ShellTab

/**
 * Phase 0 content of Idag / Recept / Veckan / Handla: an empty state that says what the tab
 * will hold (Today and Recipes in Phase 1, Week and Shopping in Phase 2). No sample data: demo
 * data arrives with the recipe screens in Phase 1.
 */
@Composable
internal fun PlaceholderTab(
    tab: ShellTab,
    state: ShellUiState,
    showDemoBanner: Boolean,
    onDismissDemoBanner: () -> Unit,
    onConnectTandoor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = tab.placeholder()
    val spacing = TandemTheme.spacing
    val banner = state.isDemo && showDemoBanner
    ShellScreenScaffold(title = stringResource(tab.label), state = state, modifier = modifier) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            val viewport = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screenHorizontal),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (banner) {
                    DemoBanner(
                        onConnect = onConnectTandoor,
                        onDismiss = onDismissDemoBanner,
                        modifier = Modifier
                            .padding(top = spacing.sm)
                            .widthIn(max = TandemTheme.sizes.maxReadingWidth),
                    )
                }
                EmptyState(
                    icon = content.icon,
                    title = stringResource(content.title),
                    body = stringResource(content.body),
                    // Centred in the visible area; scrolls with large fonts or a short window.
                    modifier = Modifier.heightIn(min = if (banner) viewport * 0.7f else viewport),
                )
            }
        }
    }
}

private class PlaceholderContent(val icon: ImageVector, val title: Int, val body: Int)

@Composable
private fun ShellTab.placeholder(): PlaceholderContent = when (this) {
    ShellTab.TODAY -> PlaceholderContent(TandemIcons.Today, R.string.shell_today_title, R.string.shell_today_body)

    ShellTab.RECIPES -> PlaceholderContent(TandemIcons.MenuBook, R.string.shell_recipes_title, R.string.shell_recipes_body)

    ShellTab.WEEK -> PlaceholderContent(TandemIcons.CalendarMonth, R.string.shell_week_title, R.string.shell_week_body)

    ShellTab.SHOPPING -> PlaceholderContent(TandemIcons.ShoppingCart, R.string.shell_shopping_title, R.string.shell_shopping_body)

    // "Mer" has its own screen; never shown.
    ShellTab.MORE -> PlaceholderContent(TandemIcons.MoreHoriz, R.string.shell_tab_more, R.string.shell_tab_more)
}
