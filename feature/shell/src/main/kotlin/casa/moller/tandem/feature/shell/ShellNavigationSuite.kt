package casa.moller.tandem.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldLayout
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldState
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldValue
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import casa.moller.tandem.feature.shell.navigation.ShellTab
import casa.moller.tandem.feature.shell.ui.icon
import casa.moller.tandem.feature.shell.ui.label
import casa.moller.tandem.feature.shell.ui.selectedIcon

// Device test 2026-11-14, round 3 (B21, B22): the tabs on a phone in landscape and at a large font.
//
// - B21: on a phone in landscape (780 × 360 dp) the collapsed navigation rail (44 dp top space and
//   five 64 dp items) pushed "Mer" below the screen edge, so Settings, Diagnostics and logout were
//   out of reach. Below EXPANDED_RAIL_MIN_HEIGHT_DP the shell now draws its own short rail: the same
//   Material 3 rail items, centred without the top space, labels only when they fit (all, then only
//   the selected tab's, then none, the icons then carrying the names for TalkBack), and the column
//   scrolls if even the icons do not fit. Material's adaptive guidance keeps a rail (not a bottom
//   bar) on a short wide window, so the height stays with the content.
// - B22: at font scale 2 on a 320 dp wide phone the bottom bar broke its labels inside the words
//   ("Rece/pt"). A label is now one line or not there: when one of the five does not fit its fifth
//   of the bar, the bar shows icons only, each with its tab's name for TalkBack.

/** How many of the tabs show their label beside the icon. */
internal enum class TabLabels { ALL, SELECTED, NONE }

/** True when the window is wide enough for a rail but too short for the standard one (B21). */
internal fun shellUsesShortRail(navigationSuiteType: NavigationSuiteType, windowSize: DpSize): Boolean =
    navigationSuiteType == NavigationSuiteType.WideNavigationRailCollapsed && windowSize.height < EXPANDED_RAIL_MIN_HEIGHT_DP.dp

/**
 * The shell's navigation around [content]: Material's [NavigationSuiteScaffold] with [navigationSuiteType],
 * or, when [shortRail], the short rail of this file in Material's scaffold layout (same show/hide
 * animation and window insets as the standard rail).
 *
 * @param windowWidth the window's width, for the bottom bar's labels (each tab has a fifth of it).
 */
@Composable
internal fun ShellNavigationSuite(
    navigationSuiteType: NavigationSuiteType,
    shortRail: Boolean,
    windowWidth: Dp,
    state: NavigationSuiteScaffoldState,
    currentTab: ShellTab,
    onSelectTab: (ShellTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val labels = ShellTab.entries.map { stringResource(it.label) }
    if (shortRail) {
        Surface(
            modifier = modifier,
            color = NavigationSuiteScaffoldDefaults.containerColor,
            contentColor = NavigationSuiteScaffoldDefaults.contentColor,
        ) {
            val hidden = state.currentValue == NavigationSuiteScaffoldValue.Hidden && !state.isAnimating
            val railInsets = WideNavigationRailDefaults.windowInsets
            NavigationSuiteScaffoldLayout(
                navigationSuite = { ShortRail(currentTab = currentTab, onSelectTab = onSelectTab, labels = labels) },
                navigationSuiteType = NavigationSuiteType.WideNavigationRailCollapsed,
                state = state,
                content = {
                    // As Material's scaffold: the rail has the start inset, the content does not pad it again.
                    Box(Modifier.consumeWindowInsets(if (hidden) WindowInsets(0) else railInsets.only(WindowInsetsSides.Start))) {
                        content()
                    }
                },
            )
        }
        return
    }
    val barLabels = navigationSuiteType != NavigationSuiteType.ShortNavigationBarCompact ||
        labelsFit(labels, MaterialTheme.typography.labelMedium, windowWidth / ShellTab.entries.size - LABEL_SLACK)
    NavigationSuiteScaffold(
        navigationItems = {
            ShellTab.entries.forEachIndexed { index, tab ->
                val selected = tab == currentTab
                NavigationSuiteItem(
                    selected = selected,
                    onClick = { onSelectTab(tab) },
                    icon = {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon() else tab.icon(),
                            contentDescription = if (barLabels) null else labels[index],
                        )
                    },
                    label = if (barLabels) {
                        { Text(labels[index], maxLines = 1) }
                    } else {
                        null
                    },
                    navigationSuiteType = navigationSuiteType,
                    modifier = Modifier.testTag(shellTabTag(tab)),
                )
            }
        },
        modifier = modifier,
        navigationSuiteType = navigationSuiteType,
        state = state,
        content = content,
    )
}

/**
 * B21: the rail of a short wide window. Material 3 rail items (collapsed, icon above label) in a
 * centred column of [SHORT_RAIL_WIDTH], with the labels of [TabLabels.ALL] tabs, else only the
 * selected one's, else none, whichever is the first to fit the height; it scrolls if none does.
 * A label is never wider than the rail (B22's rule): if one is, no tab shows a label.
 */
@Composable
private fun ShortRail(currentTab: ShellTab, onSelectTab: (ShellTab) -> Unit, labels: List<String>) {
    val colors = WideNavigationRailDefaults.colors()
    val fits = labelsFit(labels, MaterialTheme.typography.labelMedium, SHORT_RAIL_WIDTH - LABEL_SLACK)
    val modes = if (fits) TabLabels.entries else listOf(TabLabels.NONE)
    Surface(color = colors.containerColor, contentColor = colors.contentColor) {
        BoxWithConstraints(
            Modifier
                .fillMaxHeight()
                .windowInsetsPadding(WideNavigationRailDefaults.windowInsets)
                .width(SHORT_RAIL_WIDTH)
                .testTag(SHELL_SHORT_RAIL_TAG),
        ) {
            val railHeight = maxHeight
            FirstThatFitsHeight(
                variants = modes.map { mode ->
                    { scroll: Boolean ->
                        ShortRailItems(
                            mode = mode,
                            currentTab = currentTab,
                            onSelectTab = onSelectTab,
                            labels = labels,
                            modifier = if (scroll) {
                                Modifier
                                    .verticalScroll(rememberScrollState())
                                    .heightIn(min = railHeight)
                            } else {
                                Modifier
                            },
                        )
                    }
                },
            )
        }
    }
}

/** The five rail items with the labels of [mode]; one selectable group and one traversal group for TalkBack. */
@Composable
private fun ShortRailItems(
    mode: TabLabels,
    currentTab: ShellTab,
    onSelectTab: (ShellTab) -> Unit,
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup()
            .semantics { isTraversalGroup = true },
        verticalArrangement = Arrangement.spacedBy(SHORT_RAIL_ITEM_GAP, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShellTab.entries.forEachIndexed { index, tab ->
            val selected = tab == currentTab
            val showLabel = mode == TabLabels.ALL || (mode == TabLabels.SELECTED && selected)
            WideNavigationRailItem(
                selected = selected,
                onClick = { onSelectTab(tab) },
                icon = {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon() else tab.icon(),
                        contentDescription = if (showLabel) null else labels[index],
                    )
                },
                label = if (showLabel) {
                    { Text(labels[index], maxLines = 1) }
                } else {
                    null
                },
                railExpanded = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(shellTabTag(tab)),
            )
        }
    }
}

/**
 * Draws the first of [variants] whose content is not taller than the height it gets, else the last
 * one, which then scrolls (its argument is true). The candidates are measured without semantics,
 * so TalkBack and tests see only the one shown.
 */
@Composable
private fun FirstThatFitsHeight(variants: List<@Composable (scroll: Boolean) -> Unit>, modifier: Modifier = Modifier) {
    SubcomposeLayout(modifier) { constraints ->
        val probeConstraints = Constraints(maxWidth = constraints.maxWidth)
        val chosen = variants.indices.firstOrNull { index ->
            index < variants.lastIndex &&
                subcompose("probe-$index") { Box(Modifier.clearAndSetSemantics { }) { variants[index](false) } }
                    .sumOf { it.measure(probeConstraints).height } <= constraints.maxHeight
        } ?: variants.lastIndex
        val scroll = chosen == variants.lastIndex
        val placeables = subcompose("shown-$chosen") { variants[chosen](scroll) }
            .map { it.measure(constraints.copy(minHeight = 0)) }
        val width = (placeables.maxOfOrNull { it.width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, constraints.maxHeight) {
            // Centred when it fits, from the top when it scrolls (it is as tall as the rail then).
            placeables.forEach { it.placeRelative(0, ((constraints.maxHeight - it.height) / 2).coerceAtLeast(0)) }
        }
    }
}

/** True when each of [labels] fits [width] on one line in [style] at the current font scale. */
@Composable
private fun labelsFit(labels: List<String>, style: TextStyle, width: Dp): Boolean {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(labels, style, width, density, measurer) {
        val widest = labels.maxOfOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width } ?: 0
        with(density) { widest.toDp() } <= width
    }
}

/** Test tag of a tab's item in the bar or the rail. */
internal fun shellTabTag(tab: ShellTab): String = "shell:tab:${tab.name.lowercase()}"

/** Test tag of the short rail (B21). */
internal const val SHELL_SHORT_RAIL_TAG = "shell:short-rail"

/** As wide as Material's collapsed rail. */
private val SHORT_RAIL_WIDTH = 96.dp

/** Material's gap between collapsed rail items. */
private val SHORT_RAIL_ITEM_GAP = 4.dp

/** A label must leave this much of its item free (rounding, the selected label's weight). */
private val LABEL_SLACK = 4.dp
