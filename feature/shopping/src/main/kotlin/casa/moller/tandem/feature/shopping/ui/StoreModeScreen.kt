package casa.moller.tandem.feature.shopping.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import casa.moller.tandem.core.designsystem.component.TandemButtonSize
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.Share
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.window.KeepScreenOn
import casa.moller.tandem.feature.shopping.R
import casa.moller.tandem.feature.shopping.ShoppingFormat
import casa.moller.tandem.feature.shopping.ShoppingSection
import casa.moller.tandem.feature.shopping.StoreModeAction
import casa.moller.tandem.feature.shopping.StoreModeUiState

/** Test tag of the store mode list. */
const val STORE_MODE_LIST_TAG = "shopping:store:list"

/**
 * Store mode (plan 6.2 "Butiksläge", 6.6, feature #13, Stitch butiksl_ge), stateless: big text,
 * rows at least 64 dp high, only what is still to buy, the aisle headings stick to the top while
 * scrolling, and the screen stays on (core:ui [KeepScreenOn], reference counted with cook mode's,
 * fix-ui). Tap or swipe puts an item in the cart ("Ångra" in the
 * snackbar). Full screen on every width ([casa.moller.tandem.feature.shopping.navigation.StoreModeKey]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StoreModeScreen(
    state: StoreModeUiState,
    onAction: (StoreModeAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    KeepScreenOn()
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale, state.amountStyle) { ShoppingFormat(locale, state.amountStyle) }
    val other = stringResource(R.string.shopping_other_category)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TandemTopAppBar(
                title = stringResource(R.string.shopping_store_mode_title),
                subtitle = state.store?.name,
                onNavigateBack = { onAction(StoreModeAction.Close) },
                actions = {
                    if (state.toBuy > 0) {
                        IconButton(onClick = { onAction(StoreModeAction.Share) }) {
                            Icon(TandemIcons.Share, contentDescription = stringResource(R.string.shopping_share))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.loading -> LoadingState(modifier = Modifier.fillMaxSize().padding(padding))

            state.sections.isEmpty() -> EmptyState(
                icon = TandemIcons.CheckCircle,
                title = stringResource(R.string.shopping_store_mode_done_title),
                body = stringResource(R.string.shopping_store_mode_done_body),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                TandemPrimaryButton(
                    text = stringResource(R.string.shopping_store_mode_done),
                    onClick = { onAction(StoreModeAction.Close) },
                    size = TandemButtonSize.Kitchen,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .testTag(STORE_MODE_LIST_TAG),
                contentPadding = PaddingValues(bottom = TandemTheme.spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item(key = "progress") {
                    StoreProgress(
                        state = state,
                        modifier = Modifier
                            .widthIn(max = TandemTheme.sizes.maxReadingWidth)
                            .padding(horizontal = TandemTheme.spacing.screenHorizontal, vertical = TandemTheme.spacing.sm),
                    )
                }
                state.sections.forEach { section ->
                    stickyHeader(key = "header-${section.key}") {
                        AisleHeader(section = section, title = section.name ?: other)
                    }
                    items(section.entries, key = { it.id }) { entry ->
                        val check = RowActions(onToggle = { onAction(StoreModeAction.Check(entry.id)) })
                        ShoppingEntryRow(
                            entry = entry,
                            format = format,
                            categoryName = section.name ?: other,
                            delayed = false,
                            actions = if (state.readOnly) RowActions() else check,
                            large = true,
                            modifier = Modifier
                                .widthIn(max = TandemTheme.sizes.maxReadingWidth)
                                .padding(horizontal = TandemTheme.spacing.md, vertical = TandemTheme.spacing.xxs),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreProgress(state: StoreModeUiState, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(modifier = Modifier.padding(TandemTheme.spacing.lg), verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm)) {
            Text(
                text = stringResource(R.string.shopping_store_mode_progress, state.inCart, state.total),
                style = MaterialTheme.typography.titleLarge,
            )
            LinearProgressIndicator(
                progress = { if (state.total == 0) 0f else state.inCart.toFloat() / state.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm)) {
                Icon(TandemIcons.Info, contentDescription = null, modifier = Modifier.size(TandemTheme.sizes.iconSmall))
                Text(text = stringResource(R.string.shopping_store_mode_screen_on), style = MaterialTheme.typography.bodyMedium)
            }
            if (state.pendingCount > 0) {
                Text(
                    text = pluralStringResource(R.plurals.shopping_pending_description, state.pendingCount, state.pendingCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A sticky aisle heading: big, on the background so the rows scroll under it. */
@Composable
private fun AisleHeader(section: ShoppingSection, title: String) {
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .widthIn(max = TandemTheme.sizes.maxReadingWidth)
                .heightIn(min = TandemTheme.sizes.minTouchTarget)
                .padding(horizontal = TandemTheme.spacing.screenHorizontal, vertical = TandemTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            Surface(shape = RoundedCornerShape(percent = 50), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    text = pluralStringResource(R.plurals.shopping_section_count, section.entries.size, section.entries.size),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = TandemTheme.spacing.md, vertical = TandemTheme.spacing.xs),
                )
            }
        }
    }
}
