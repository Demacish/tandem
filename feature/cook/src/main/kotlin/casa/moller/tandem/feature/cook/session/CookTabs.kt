package casa.moller.tandem.feature.cook.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import casa.moller.tandem.core.designsystem.icon.Alarm
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.feature.cook.CookUiState
import casa.moller.tandem.feature.cook.R

/**
 * One tab of a two-recipe cook mode (Fas 4, plan 8.1).
 *
 * @property stepCount 0 while the recipe is not loaded yet.
 * @property ringing one of its timers rings (the tab shows the alarm icon and says so).
 * @property failed the recipe cannot be shown (not stored, offline): the tab says so.
 */
@Immutable
data class CookTab(
    val recipeId: Long,
    val name: String,
    val page: Int,
    val stepCount: Int,
    val selected: Boolean,
    val ringing: Boolean = false,
    val failed: Boolean = false,
) {
    val loaded: Boolean
        get() = stepCount > 0

    val done: Boolean
        get() = loaded && page >= stepCount
}

/**
 * What cook mode shows of its session (Fas 4): the tabs when two recipes are open, and whether
 * "Laga ett recept till" is offered.
 *
 * @property closedRecipeId the recipe ✕ just closed: a timer of it brings it back.
 */
@Immutable
data class CookSessionUi(val tabs: List<CookTab> = emptyList(), val canAdd: Boolean = false, val closedRecipeId: Long? = null) {
    /** Two recipes: tabs at the top, ✕ closes the shown recipe only, timers say their recipe. */
    val isTwo: Boolean
        get() = tabs.size > 1

    val recipeIds: Set<Long>
        get() = tabs.mapTo(LinkedHashSet()) { it.recipeId }

    companion object {
        /**
         * The tabs of [session] from each recipe's cook state ([states] by recipe id; a recipe
         * whose state has not arrived yet shows as loading).
         */
        fun of(session: CookSession, states: Map<Long, CookUiState>, closedRecipeId: Long? = null): CookSessionUi = CookSessionUi(
            tabs = session.recipes.map { recipe ->
                val state = states[recipe.recipeId]
                CookTab(
                    recipeId = recipe.recipeId,
                    name = state?.recipeName.orEmpty(),
                    page = state?.page ?: recipe.startStep,
                    stepCount = if (state?.hasDetail == true) state.steps.size else 0,
                    selected = recipe.recipeId == session.activeRecipeId,
                    ringing = state?.timers.orEmpty().any { it.recipeId == recipe.recipeId && it.state == TimerState.RINGING },
                    failed = state != null && state.error != null && !state.hasDetail && !state.loading,
                )
            },
            canAdd = session.canAdd,
            closedRecipeId = closedRecipeId,
        )
    }
}

/**
 * The two recipes as tabs above cook mode (Fas 4): name, "Steg 2 av 6" or "Klart!", and the alarm
 * icon while one of its timers rings. Each tab is one TalkBack item ("Krämig svamppasta, steg 2
 * av 6, en timer larmar"), at least 64 dp high (cook mode, plan 6.6); a long name is cut with an
 * ellipsis instead of growing the row (font scale 2).
 *
 * Left and right on a tab go to the other tab: the screen's own key handler turns the page, and
 * it would otherwise take them before the focus could move (plan 6.5).
 *
 * @param announceStep the header's "Steg 3 av 6" is not shown (a low window, the tabs stand in its
 *   place): the selected tab's description, whose text changes, tells TalkBack about a page turn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CookRecipeTabs(
    session: CookSessionUi,
    onAction: (CookSessionAction) -> Unit,
    modifier: Modifier = Modifier,
    announceStep: Boolean = false,
) {
    val selected = session.tabs.indexOfFirst { it.selected }.coerceAtLeast(0)
    val focusManager = LocalFocusManager.current
    PrimaryTabRow(
        selectedTabIndex = selected,
        modifier = modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                when {
                    event.type != KeyEventType.KeyDown -> false
                    event.key == Key.DirectionLeft -> true.also { focusManager.moveFocus(FocusDirection.Left) }
                    event.key == Key.DirectionRight -> true.also { focusManager.moveFocus(FocusDirection.Right) }
                    else -> false
                }
            }
            .testTag(COOK_TABS_TAG),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        session.tabs.forEach { tab ->
            val description = tabDescription(tab)
            Tab(
                selected = tab.selected,
                onClick = { onAction(CookSessionAction.SelectRecipe(tab.recipeId)) },
                modifier = Modifier
                    .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
                    .testTag(cookTabTag(tab.recipeId))
                    .semantics {
                        contentDescription = description
                        if (announceStep && tab.selected) liveRegion = LiveRegionMode.Polite
                    },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = TandemTheme.spacing.sm, vertical = TandemTheme.spacing.xs)
                        .clearAndSetSemantics { },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xs),
                    ) {
                        if (tab.ringing) {
                            Icon(
                                imageVector = TandemIcons.Alarm,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(TandemTheme.sizes.iconSmall),
                            )
                        }
                        Text(
                            text = tab.name.ifEmpty { stringResource(R.string.cook_title) },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = tabProgress(tab),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** "Steg 2 av 6", "Klart!", "Läser in …", or that it cannot be shown. */
@Composable
private fun tabProgress(tab: CookTab): String = when {
    tab.failed -> stringResource(R.string.cook_tab_failed)
    !tab.loaded -> stringResource(R.string.cook_tab_loading)
    tab.done -> stringResource(R.string.cook_done_page)
    else -> stringResource(R.string.cook_step_of, tab.page + 1, tab.stepCount)
}

@Composable
internal fun tabDescription(tab: CookTab): String {
    val name = tab.name.ifEmpty { stringResource(R.string.cook_title) }
    val progress = tabProgress(tab)
    return if (tab.ringing) {
        stringResource(R.string.cook_tab_ringing_description, name, progress)
    } else {
        stringResource(R.string.cook_tab_description, name, progress)
    }
}

/** Test tags. */
internal const val COOK_TABS_TAG = "cook:tabs"

internal fun cookTabTag(recipeId: Long): String = "cook:tab:$recipeId"
