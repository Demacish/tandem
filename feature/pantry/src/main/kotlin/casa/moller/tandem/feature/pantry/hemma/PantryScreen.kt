package casa.moller.tandem.feature.pantry.hemma

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.Add
import casa.moller.tandem.core.designsystem.icon.MoreVert
import casa.moller.tandem.core.designsystem.icon.Restaurant
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.diagnostics.ReportContentReady
import casa.moller.tandem.core.ui.navigation.AppChromeStatusChip
import casa.moller.tandem.feature.pantry.R
import casa.moller.tandem.feature.pantry.navigation.PantryScanner
import casa.moller.tandem.feature.pantry.ui.PantryIcons
import casa.moller.tandem.feature.pantry.ui.PantryNotices
import casa.moller.tandem.feature.pantry.ui.PendingChip

/** From this width of the list the places stand in two columns (plan §10 Fas S "Klart när": surfplatta). */
internal val PantryTwoColumnWidth = 600.dp

/** From this width, three columns (a tablet in landscape, a large window). */
internal val PantryThreeColumnWidth = 1100.dp

/**
 * Hemma (plan §8.8 "Skafferiet", 6.5, #26, #28, #46), stateless.
 *
 * - **Phones:** one column: notices, "Kan laga nu" and "Packa upp", the scanners, then a card per
 *   place (Kyl, Frys, Skafferi, the household's own) with its entries, soonest day first, and
 *   "Har hemma" last.
 * - **Tablets and wide windows** (from [PantryTwoColumnWidth]): the places as columns (two, three
 *   from [PantryThreeColumnWidth]); TalkBack still reads them in order.
 * - **States:** loading, a new Tandoor without places ("Lägg till Kyl, Frys och Skafferi"), empty
 *   (how to fill it: scan, receipt, Packa upp, by hand), offline (changes wait, with the count),
 *   guest (#42: no edit controls at all, an explanation), demo (the shell's banner).
 *
 * @param onBack the back arrow (Hemma is opened from Mer, a place from Hemma); null shows none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantryScreen(
    state: PantryUiState,
    onAction: (PantryAction) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    ReportContentReady(ready = !state.loading)
    val place = state.location
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TandemTopAppBar(
                title = place?.name ?: stringResource(R.string.pantry_title),
                onNavigateBack = onBack,
                actions = { TopBarActions(state = state, onAction = onAction) },
            )
        },
        floatingActionButton = {
            if (state.canEdit && !state.loading && !state.noLocations) {
                val label = stringResource(R.string.pantry_add)
                ExtendedFloatingActionButton(
                    text = { Text(label) },
                    icon = { Icon(TandemIcons.Add, contentDescription = null) },
                    onClick = { onAction(PantryAction.AddEntry(place?.id)) },
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
                onRefresh = { onAction(PantryAction.Refresh) },
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                PantryContent(state = state, onAction = onAction)
            }
        }
    }
    PantryDialogs(state = state, onAction = onAction)
}

@Composable
private fun TopBarActions(state: PantryUiState, onAction: (PantryAction) -> Unit) {
    PendingChip(count = state.pendingCount)
    AppChromeStatusChip(Modifier.padding(start = TandemTheme.spacing.xs))
    if (state.loading || state.location != null) return
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { menuOpen = true }) {
        Icon(TandemIcons.MoreVert, contentDescription = stringResource(R.string.pantry_more))
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.pantry_make_now_title)) },
            leadingIcon = { Icon(TandemIcons.Restaurant, contentDescription = null) },
            onClick = {
                menuOpen = false
                onAction(PantryAction.OpenMakeNow)
            },
        )
        if (state.canEdit) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pantry_unpack_title)) },
                leadingIcon = { Icon(PantryIcons.Unpack, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onAction(PantryAction.OpenUnpack)
                },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.pantry_staples_title)) },
            onClick = {
                menuOpen = false
                onAction(PantryAction.OpenStaples)
            },
        )
        if (state.canEdit) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pantry_location_new)) },
                leadingIcon = { Icon(PantryIcons.Kitchen, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onAction(PantryAction.EditLocation(null))
                },
            )
        }
    }
}

@Composable
private fun PantryContent(state: PantryUiState, onAction: (PantryAction) -> Unit) {
    val spacing = TandemTheme.spacing
    val today = state.today ?: return
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = when {
            state.location != null -> 1
            maxWidth >= PantryThreeColumnWidth -> 3
            maxWidth >= PantryTwoColumnWidth -> 2
            else -> 1
        }
        val horizontal = if (columns == 1) spacing.screenHorizontal else spacing.xl
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize().testTag(PANTRY_LIST_TAG),
            // Room for the "+ Lägg till" button below the last row.
            contentPadding = PaddingValues(start = horizontal, end = horizontal, top = spacing.sm, bottom = 96.dp),
            verticalItemSpacing = spacing.lg,
            horizontalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            item(key = "notices", span = StaggeredGridItemSpan.FullLine) {
                PantryNotices(
                    readOnly = state.readOnly,
                    householdMissing = state.householdMissing,
                    syncNotice = state.syncNotice,
                    pendingCount = state.pendingCount,
                    onRetry = { onAction(PantryAction.Refresh) },
                )
            }
            if (state.location == null) {
                item(key = "shortcuts", span = StaggeredGridItemSpan.FullLine) { Shortcuts(state = state, onAction = onAction) }
            }
            when {
                state.noLocations -> item(key = "no-locations", span = StaggeredGridItemSpan.FullLine) {
                    NoLocations(canEdit = state.canEdit, onAction = onAction)
                }

                state.isEmpty && state.location == null -> item(key = "empty", span = StaggeredGridItemSpan.FullLine) {
                    EmptyPantry(state = state, onAction = onAction)
                }

                else -> {
                    state.sections.forEach { section ->
                        item(key = "section-${section.location.id}") {
                            LocationCard(
                                section = section,
                                today = today,
                                canEdit = state.canEdit,
                                onAction = onAction,
                                onOpen = if (state.location == null) {
                                    { onAction(PantryAction.OpenLocation(section.location.id)) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                    if (state.onHand.isNotEmpty()) {
                        item(key = "on-hand", span = StaggeredGridItemSpan.FullLine) {
                            OnHandCard(foods = state.onHand, canEdit = state.canEdit, onAction = onAction)
                        }
                    }
                }
            }
        }
    }
}

/** "Kan laga nu", "Packa upp" after shopping, and the scanners (members only). */
@Composable
private fun Shortcuts(state: PantryUiState, onAction: (PantryAction) -> Unit) {
    val spacing = TandemTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        if (state.unpackCount > 0) {
            CalloutCard(
                title = stringResource(R.string.pantry_unpack_prompt_title),
                body = pluralStringResource(R.plurals.pantry_unpack_prompt_body, state.unpackCount, state.unpackCount),
                icon = PantryIcons.Unpack,
                tone = TandemTone.Accent,
                actions = {
                    TandemTextButton(text = stringResource(R.string.pantry_unpack_title), onClick = { onAction(PantryAction.OpenUnpack) })
                },
            )
        }
        TandemSecondaryButton(
            text = if (state.expiringCount > 0) {
                pluralStringResource(R.plurals.pantry_make_now_button_expiring, state.expiringCount, state.expiringCount)
            } else {
                stringResource(R.string.pantry_make_now_button)
            },
            onClick = { onAction(PantryAction.OpenMakeNow) },
            leadingIcon = TandemIcons.Restaurant,
            modifier = Modifier.fillMaxWidth(),
        )
        // The empty pantry shows the same ways in as big buttons.
        if (state.canEdit && !state.noLocations && !state.isEmpty) ScannerChips(fridgePhoto = state.fridgePhoto, onAction = onAction)
    }
}

/** Ways to fill the pantry without typing: barcode, receipt and (when set up) the experimental fridge photo. */
@Composable
private fun ScannerChips(fridgePhoto: Boolean, onAction: (PantryAction) -> Unit, locationId: Long? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm),
    ) {
        AssistChip(
            onClick = { onAction(PantryAction.OpenScanner(PantryScanner.BARCODE, locationId)) },
            label = { Text(stringResource(R.string.pantry_scan_barcode)) },
            leadingIcon = { Icon(PantryIcons.BarcodeScanner, contentDescription = null) },
        )
        AssistChip(
            onClick = { onAction(PantryAction.OpenScanner(PantryScanner.RECEIPT, locationId)) },
            label = { Text(stringResource(R.string.pantry_scan_receipt)) },
            leadingIcon = { Icon(PantryIcons.Receipt, contentDescription = null) },
        )
        if (fridgePhoto) {
            AssistChip(
                onClick = { onAction(PantryAction.OpenScanner(PantryScanner.FRIDGE, locationId)) },
                label = { Text(stringResource(R.string.pantry_scan_fridge)) },
                leadingIcon = { Icon(PantryIcons.Camera, contentDescription = null) },
            )
        }
    }
}

/** A new Tandoor has no storage places: one tap adds Kyl, Frys and Skafferi (in the app's language). */
@Composable
private fun NoLocations(canEdit: Boolean, onAction: (PantryAction) -> Unit) {
    val fridge = stringResource(R.string.pantry_default_fridge)
    val freezer = stringResource(R.string.pantry_default_freezer)
    val cupboard = stringResource(R.string.pantry_default_pantry)
    EmptyState(
        icon = PantryIcons.Kitchen,
        title = stringResource(R.string.pantry_no_locations_title),
        body = stringResource(if (canEdit) R.string.pantry_no_locations_body else R.string.pantry_no_locations_body_guest),
    ) {
        if (canEdit) {
            TandemPrimaryButton(
                text = stringResource(R.string.pantry_no_locations_create, fridge, freezer, cupboard),
                onClick = { onAction(PantryAction.CreateDefaultLocations(listOf(fridge to false, freezer to true, cupboard to false))) },
                leadingIcon = TandemIcons.Add,
                modifier = Modifier.fillMaxWidth(),
            )
            TandemTextButton(text = stringResource(R.string.pantry_location_new), onClick = { onAction(PantryAction.EditLocation(null)) })
        }
    }
}

/** Nothing in the pantry yet: how to fill it (scan, receipt, Packa upp, by hand). */
@Composable
private fun EmptyPantry(state: PantryUiState, onAction: (PantryAction) -> Unit) {
    EmptyState(
        icon = PantryIcons.Kitchen,
        title = stringResource(R.string.pantry_empty_title),
        body = stringResource(if (state.canEdit) R.string.pantry_empty_body else R.string.pantry_empty_body_guest),
    ) {
        if (state.canEdit) {
            TandemPrimaryButton(
                text = stringResource(R.string.pantry_scan_barcode),
                onClick = { onAction(PantryAction.OpenScanner(PantryScanner.BARCODE, null)) },
                leadingIcon = PantryIcons.BarcodeScanner,
                modifier = Modifier.fillMaxWidth(),
            )
            TandemSecondaryButton(
                text = stringResource(R.string.pantry_scan_receipt),
                onClick = { onAction(PantryAction.OpenScanner(PantryScanner.RECEIPT, null)) },
                leadingIcon = PantryIcons.Receipt,
                modifier = Modifier.fillMaxWidth(),
            )
            TandemSecondaryButton(
                text = stringResource(R.string.pantry_unpack_title),
                onClick = { onAction(PantryAction.OpenUnpack) },
                leadingIcon = PantryIcons.Unpack,
                modifier = Modifier.fillMaxWidth(),
            )
            TandemTextButton(
                text = stringResource(R.string.pantry_add_by_hand),
                onClick = { onAction(PantryAction.AddEntry(null)) },
                leadingIcon = TandemIcons.Add,
            )
        }
    }
}
