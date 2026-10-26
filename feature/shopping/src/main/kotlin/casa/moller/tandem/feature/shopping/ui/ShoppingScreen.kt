package casa.moller.tandem.feature.shopping.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.Add
import casa.moller.tandem.core.designsystem.icon.ExpandMore
import casa.moller.tandem.core.designsystem.icon.History
import casa.moller.tandem.core.designsystem.icon.MoreVert
import casa.moller.tandem.core.designsystem.icon.Share
import casa.moller.tandem.core.designsystem.icon.Sync
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.diagnostics.ReportContentReady
import casa.moller.tandem.core.ui.navigation.AppChromeDemoBanner
import casa.moller.tandem.core.ui.navigation.AppChromeStatusChip
import casa.moller.tandem.feature.shopping.R
import casa.moller.tandem.feature.shopping.ShoppingAction
import casa.moller.tandem.feature.shopping.ShoppingFormat
import casa.moller.tandem.feature.shopping.ShoppingSyncNotice
import casa.moller.tandem.feature.shopping.ShoppingUiState

/** Test tag of the list (scrolling in tests). */
const val SHOPPING_LIST_TAG = "shopping:list"

/** Test tag of the tablet's side pane. */
const val SHOPPING_SIDE_PANE_TAG = "shopping:pane"

/** From this width the list gets the side pane with "Lägg till", lists and stores (plan 6.5). */
private val SidePaneMinWidth = 600.dp

/** From this width of the list itself the aisles stand in two columns (masonry, plan 6.5). */
private val TwoColumnMinWidth = 640.dp

private val SidePaneWidth = 340.dp

/**
 * The Handla tab (plan 6.2 "Handla", 6.5, Stitch ink_pslista_*), stateless.
 *
 * - **Phones** (< 600 dp): one column. The list and store chips, a "Lägg till vara…" field that
 *   opens the add sheet (also the "+ Vara" button), the aisles as cards, then "Köp senare" and
 *   the folded "Avbockat".
 * - **Wider windows** (tablets in both orientations, phones in landscape): a side pane with "Lägg
 *   till vara", lists, stores, store mode and history beside the list; the aisles stand in two
 *   columns when the list is at least 640 dp wide (Stitch ink_pslista_handla_surfplatta).
 * - **States:** empty, offline / paused (changes wait, with the count), guest (#42: no edit
 *   controls at all), demo (the shell's banner).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(
    state: ShoppingUiState,
    onAction: (ShoppingAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    ReportContentReady(ready = !state.loading)
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale, state.amountStyle) { ShoppingFormat(locale, state.amountStyle) }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= SidePaneMinWidth
        val showAddButton = !wide && state.canEdit && !state.loading
        Scaffold(
            topBar = {
                TandemTopAppBar(
                    title = state.lists.firstOrNull { it.id == state.selectedListId }?.name ?: stringResource(R.string.shopping_title),
                    actions = { TopBarActions(state = state, onAction = onAction) },
                )
            },
            floatingActionButton = {
                if (showAddButton && !state.grouped.isEmpty) {
                    val label = stringResource(R.string.shopping_add_fab)
                    ExtendedFloatingActionButton(
                        text = { Text(label) },
                        icon = { Icon(TandemIcons.Add, contentDescription = null) },
                        onClick = { onAction(ShoppingAction.OpenAdd) },
                        // Phase 2 (integration lane): on the device the button reached TalkBack
                        // without its label (the release smoke test's window dump showed a
                        // nameless clickable); the label is set on the button itself.
                        modifier = Modifier.semantics { contentDescription = label },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            if (state.loading) {
                LoadingState(modifier = Modifier.fillMaxSize().padding(padding))
            } else {
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = { onAction(ShoppingAction.Refresh) },
                    modifier = Modifier.fillMaxSize().padding(padding),
                ) {
                    if (wide) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            SidePane(
                                state = state,
                                format = format,
                                onAction = onAction,
                                modifier = Modifier.width(SidePaneWidth).fillMaxHeight(),
                            )
                            ShoppingList(state = state, format = format, onAction = onAction, phone = false, modifier = Modifier.weight(1f))
                        }
                    } else {
                        ShoppingList(state = state, format = format, onAction = onAction, phone = true, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        if (!wide && state.addSheetOpen) {
            val sheetState = rememberExpandedSheetState()
            ModalBottomSheet(onDismissRequest = { onAction(ShoppingAction.CloseAdd) }, sheetState = sheetState) {
                AddItemPanel(
                    state = state.add,
                    format = format,
                    onAction = onAction,
                    autoFocus = true,
                    // Phase 2 (integration lane): the keyboard covered "Lägg till" below the preview
                    // and the suggestions with no way to scroll to it (release smoke test).
                    modifier = Modifier
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = TandemTheme.spacing.screenHorizontal)
                        .padding(bottom = TandemTheme.spacing.xl),
                )
            }
        }
        val edit = state.edit
        if (edit != null) {
            val sheetState = rememberExpandedSheetState()
            ModalBottomSheet(onDismissRequest = { onAction(ShoppingAction.CloseEdit) }, sheetState = sheetState) {
                EditEntryPanel(
                    state = edit,
                    onAction = onAction,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = TandemTheme.spacing.screenHorizontal)
                        .padding(bottom = TandemTheme.spacing.xl),
                )
            }
        }
        if (state.confirmClearChecked) {
            val count = state.grouped.checked.size
            AlertDialog(
                onDismissRequest = { onAction(ShoppingAction.ConfirmClearChecked(false)) },
                title = { Text(stringResource(R.string.shopping_clear_confirm_title)) },
                text = { Text(pluralStringResource(R.plurals.shopping_clear_confirm_body, count, count)) },
                confirmButton = {
                    TextButton(onClick = { onAction(ShoppingAction.ConfirmClearChecked(true)) }) {
                        Text(stringResource(R.string.shopping_clear_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onAction(ShoppingAction.ConfirmClearChecked(false)) }) {
                        Text(stringResource(R.string.shopping_cancel))
                    }
                },
            )
        }
    }
}

/** A sheet that opens fully expanded (the field and the first suggestions at once). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberExpandedSheetState() = rememberBottomSheetState(
    initialValue = SheetValue.Hidden,
    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
)

@Composable
private fun TopBarActions(state: ShoppingUiState, onAction: (ShoppingAction) -> Unit) {
    if (state.pendingCount > 0) {
        StatusChip(
            label = pluralStringResource(R.plurals.shopping_pending, state.pendingCount, state.pendingCount),
            contentDescription = pluralStringResource(R.plurals.shopping_pending_description, state.pendingCount, state.pendingCount),
            icon = TandemIcons.Sync,
            tone = TandemTone.Neutral,
        )
    }
    AppChromeStatusChip(Modifier.padding(start = TandemTheme.spacing.xs))
    if (!state.loading && state.grouped.toBuyCount > 0) {
        IconButton(onClick = { onAction(ShoppingAction.Share) }) {
            Icon(TandemIcons.Share, contentDescription = stringResource(R.string.shopping_share))
        }
    }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { menuOpen = true }) {
        Icon(TandemIcons.MoreVert, contentDescription = stringResource(R.string.shopping_more))
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.shopping_store_mode_title)) },
            leadingIcon = { Icon(TandemIcons.ShoppingCart, contentDescription = null) },
            onClick = {
                menuOpen = false
                onAction(ShoppingAction.OpenStoreMode)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.shopping_history_title)) },
            leadingIcon = { Icon(TandemIcons.History, contentDescription = null) },
            onClick = {
                menuOpen = false
                onAction(ShoppingAction.OpenHistory)
            },
        )
        if (state.canEdit && state.grouped.checked.isNotEmpty()) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.shopping_clear_checked_menu)) },
                onClick = {
                    menuOpen = false
                    onAction(ShoppingAction.AskClearChecked)
                },
            )
        }
    }
}

/** The list: notices, (phones) chips and the add field, then aisles, "Köp senare" and "Avbockat". */
@Composable
private fun ShoppingList(
    state: ShoppingUiState,
    format: ShoppingFormat,
    onAction: (ShoppingAction) -> Unit,
    phone: Boolean,
    modifier: Modifier,
) {
    val spacing = TandemTheme.spacing
    val horizontal = if (phone) spacing.screenHorizontal else spacing.xl
    val categoryNames = remember(state.categories) { state.categories.associate { it.id to it.name } }
    val other = stringResource(R.string.shopping_other_category)
    val categoryOf: (ShoppingEntry) -> String = { entry -> entry.food.categoryId?.let(categoryNames::get) ?: other }
    BoxWithConstraints(modifier = modifier) {
        val columns = if (!phone && maxWidth >= TwoColumnMinWidth) 2 else 1
        val grouped = state.grouped
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize().testTag(SHOPPING_LIST_TAG),
            contentPadding = PaddingValues(
                start = horizontal,
                end = horizontal,
                top = spacing.sm,
                // Room for the "+ Vara" button on phones.
                bottom = if (phone) 96.dp else spacing.xxl,
            ),
            verticalItemSpacing = spacing.md,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item(key = "notices", span = StaggeredGridItemSpan.FullLine) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    AppChromeDemoBanner()
                    if (state.readOnly) {
                        InfoCard(
                            title = stringResource(R.string.shopping_guest_title),
                            body = stringResource(R.string.shopping_guest_body),
                            icon = TandemIcons.Lock,
                        )
                    }
                    state.syncNotice?.let { SyncNoticeCard(notice = it, pendingCount = state.pendingCount, onAction = onAction) }
                    if (phone) {
                        Filters(state = state, onAction = onAction)
                        if (state.canEdit) AddFieldButton(onClick = { onAction(ShoppingAction.OpenAdd) })
                    }
                    if (state.progressTotal > 0) Progress(done = state.progressDone, total = state.progressTotal)
                }
            }
            if (grouped.isEmpty) {
                item(key = "empty", span = StaggeredGridItemSpan.FullLine) {
                    EmptyList(state = state, onAction = onAction)
                }
            } else if (grouped.sections.isEmpty() && grouped.checked.isNotEmpty()) {
                item(key = "done", span = StaggeredGridItemSpan.FullLine) {
                    InfoCard(
                        title = stringResource(R.string.shopping_all_done_title),
                        body = stringResource(R.string.shopping_all_done_body),
                        icon = TandemIcons.CheckCircle,
                        tone = TandemTone.Success,
                    )
                }
            }
            grouped.sections.forEach { section ->
                item(key = section.key) {
                    val title = section.name ?: other
                    val checkAll = { onAction(ShoppingAction.CheckSection(section.key)) }
                    AisleCard(
                        section = section,
                        title = title,
                        onCheckAll = if (state.canEdit && section.entries.size > 1) checkAll else null,
                        modifier = Modifier.testTag(shoppingSectionTag(section.key)),
                    ) {
                        section.entries.forEach { entry ->
                            ShoppingEntryRow(entry, format, title, delayed = false, actions = rowActions(state, entry, onAction))
                        }
                    }
                }
            }
            if (grouped.later.isNotEmpty()) {
                item(key = "later", span = StaggeredGridItemSpan.FullLine) {
                    FoldingCard(
                        title = stringResource(R.string.shopping_later_title),
                        count = grouped.later.size,
                        expanded = state.laterExpanded,
                        onToggle = { onAction(ShoppingAction.ToggleLaterExpanded) },
                        modifier = Modifier.testTag(shoppingSectionTag("later")),
                    ) {
                        grouped.later.forEach { entry ->
                            ShoppingEntryRow(entry, format, categoryOf(entry), delayed = true, actions = rowActions(state, entry, onAction))
                        }
                    }
                }
            }
            if (grouped.checked.isNotEmpty()) {
                item(key = "checked", span = StaggeredGridItemSpan.FullLine) {
                    FoldingCard(
                        title = stringResource(R.string.shopping_checked_title),
                        count = grouped.checked.size,
                        expanded = state.checkedExpanded,
                        onToggle = { onAction(ShoppingAction.ToggleCheckedExpanded) },
                        modifier = Modifier.testTag(shoppingSectionTag("checked")),
                        action = if (state.canEdit) {
                            {
                                val description = stringResource(R.string.shopping_clear_checked_description)
                                TandemTextButton(
                                    text = stringResource(R.string.shopping_clear_checked),
                                    onClick = { onAction(ShoppingAction.AskClearChecked) },
                                    modifier = Modifier.semantics { contentDescription = description },
                                )
                            }
                        } else {
                            null
                        },
                    ) {
                        grouped.checked.forEach { entry ->
                            val actions = rowActions(state, entry, onAction)
                            ShoppingEntryRow(entry, format, categoryOf(entry), delayed = false, actions = actions)
                        }
                    }
                }
            }
        }
    }
}

/** A row's actions: none for a guest; check, "köp senare" (not for checked rows) and edit otherwise. */
private fun rowActions(state: ShoppingUiState, entry: ShoppingEntry, onAction: (ShoppingAction) -> Unit): RowActions {
    if (!state.canEdit) return RowActions()
    val later = { onAction(ShoppingAction.ToggleLater(entry.id)) }
    return RowActions(
        onToggle = { onAction(ShoppingAction.ToggleChecked(entry.id)) },
        onLater = if (entry.checked) null else later,
        onEdit = { onAction(ShoppingAction.OpenEdit(entry.id)) },
    )
}

/** Named lists ("Alla", "Veckohandling") and the store the list is sorted by. */
@Composable
private fun Filters(state: ShoppingUiState, onAction: (ShoppingAction) -> Unit) {
    val spacing = TandemTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StoreChip(state = state, onClick = { onAction(ShoppingAction.OpenStoreChoice) })
        if (state.lists.isNotEmpty()) ListChips(state = state, onAction = onAction)
    }
}

@Composable
private fun ListChips(state: ShoppingUiState, onAction: (ShoppingAction) -> Unit) {
    FilterChip(
        selected = state.selectedListId == null,
        onClick = { onAction(ShoppingAction.SelectList(null)) },
        label = { Text(stringResource(R.string.shopping_lists_all)) },
    )
    state.lists.forEach { list ->
        FilterChip(
            selected = state.selectedListId == list.id,
            onClick = { onAction(ShoppingAction.SelectList(list.id)) },
            label = { Text(list.name) },
        )
    }
}

@Composable
private fun StoreChip(state: ShoppingUiState, onClick: () -> Unit) {
    val store = state.store
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                if (store != null) {
                    stringResource(R.string.shopping_store_chip, store.name)
                } else {
                    stringResource(R.string.shopping_store_by_category)
                },
            )
        },
        leadingIcon = { Icon(TandemIcons.ShoppingCart, contentDescription = null) },
        trailingIcon = { Icon(TandemIcons.ExpandMore, contentDescription = null) },
    )
}

/** The always visible "Lägg till vara…" on phones; opens the add sheet (plan 6.2). */
@Composable
private fun AddFieldButton(onClick: () -> Unit) {
    val spacing = TandemTheme.spacing
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Icon(TandemIcons.Add, contentDescription = null)
            Text(text = stringResource(R.string.shopping_add_field_hint), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun Progress(done: Int, total: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xs)) {
        Text(
            text = stringResource(R.string.shopping_progress, done, total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
    }
}

/** Offline, paused, login or safe mode: changes are kept on the device and wait (with how many). */
@Composable
private fun SyncNoticeCard(notice: ShoppingSyncNotice, pendingCount: Int, onAction: (ShoppingAction) -> Unit) {
    val (title, body) = when (notice) {
        ShoppingSyncNotice.OFFLINE -> R.string.shopping_offline_title to R.string.shopping_offline_body
        ShoppingSyncNotice.PAUSED -> R.string.shopping_paused_title to R.string.shopping_paused_body
        ShoppingSyncNotice.LOGIN -> R.string.shopping_login_title to R.string.shopping_login_body
        ShoppingSyncNotice.SAFE_MODE -> R.string.shopping_safe_mode_title to R.string.shopping_safe_mode_body
    }
    val pending = if (pendingCount > 0) {
        " " + pluralStringResource(R.plurals.shopping_pending_description, pendingCount, pendingCount) + "."
    } else {
        ""
    }
    CalloutCard(
        title = stringResource(title),
        body = stringResource(body) + pending,
        icon = if (notice == ShoppingSyncNotice.OFFLINE) TandemIcons.CloudOff else TandemIcons.Warning,
        // Neutral also when paused: the action's text is readable on it (saffron is for the app chip).
        tone = TandemTone.Neutral,
        actions = if (notice == ShoppingSyncNotice.OFFLINE || notice == ShoppingSyncNotice.PAUSED) {
            {
                TandemTextButton(
                    text = stringResource(R.string.shopping_try_again),
                    onClick = { onAction(ShoppingAction.Refresh) },
                    leadingIcon = TandemIcons.Refresh,
                )
            }
        } else {
            null
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyList(state: ShoppingUiState, onAction: (ShoppingAction) -> Unit) {
    EmptyState(
        icon = TandemIcons.ShoppingCart,
        title = stringResource(R.string.shopping_empty_title),
        body = stringResource(if (state.canEdit) R.string.shopping_empty_body else R.string.shopping_empty_body_guest),
    ) {
        if (state.canEdit) {
            TandemPrimaryButton(
                text = stringResource(R.string.shopping_add_title),
                onClick = { onAction(ShoppingAction.OpenAdd) },
                leadingIcon = TandemIcons.Add,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.add.recent.isNotEmpty()) {
                SmallHeading(stringResource(R.string.shopping_add_recent_title))
                RecentChips(names = state.add.recent, onAdd = { onAction(ShoppingAction.AddFood(it)) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentChips(names: List<String>, onAdd: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm, Alignment.CenterHorizontally)) {
        names.forEach { name ->
            val description = stringResource(R.string.shopping_add_food_description, name)
            AssistChip(
                onClick = { onAdd(name) },
                label = { Text(name) },
                leadingIcon = { Icon(TandemIcons.Add, contentDescription = null) },
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
    }
}

/**
 * The tablet's side pane (plan 6.5 "'Lägg till vara…' och listval i en fast sidopanel", Stitch
 * ink_pslista_handla_surfplatta): add, lists, stores, store mode and history.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SidePane(state: ShoppingUiState, format: ShoppingFormat, onAction: (ShoppingAction) -> Unit, modifier: Modifier) {
    val spacing = TandemTheme.spacing
    Column(
        modifier = modifier
            .testTag(SHOPPING_SIDE_PANE_TAG)
            .verticalScroll(rememberScrollState())
            // An inline pane is not a window of its own: it keeps clear of the keyboard itself.
            .imePadding()
            .padding(start = spacing.xl, top = spacing.sm, bottom = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        if (state.canEdit) {
            PaneCard {
                AddItemPanel(state = state.add, format = format, onAction = onAction)
            }
        }
        if (state.lists.isNotEmpty()) {
            PaneCard {
                SectionTitle(stringResource(R.string.shopping_lists_heading))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) { ListChips(state = state, onAction = onAction) }
            }
        }
        PaneCard {
            SectionTitle(stringResource(R.string.shopping_store_heading))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                FilterChip(
                    selected = state.store == null,
                    onClick = { onAction(ShoppingAction.SelectStore(null)) },
                    label = { Text(stringResource(R.string.shopping_store_none_title)) },
                )
                state.supermarkets.forEach { store ->
                    FilterChip(
                        selected = state.store?.id == store.id,
                        onClick = { onAction(ShoppingAction.SelectStore(store.id)) },
                        label = { Text(store.name) },
                    )
                }
            }
            TandemSecondaryButton(
                text = stringResource(R.string.shopping_store_mode_title),
                onClick = { onAction(ShoppingAction.OpenStoreMode) },
                leadingIcon = TandemIcons.ShoppingCart,
                modifier = Modifier.fillMaxWidth(),
            )
            TandemTextButton(
                text = stringResource(R.string.shopping_history_title),
                onClick = { onAction(ShoppingAction.OpenHistory) },
                leadingIcon = TandemIcons.History,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(spacing.sm))
    }
}

@Composable
private fun PaneCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(TandemTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md),
        ) { content() }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}
