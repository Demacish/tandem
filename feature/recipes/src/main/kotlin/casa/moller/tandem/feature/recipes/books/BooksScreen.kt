package casa.moller.tandem.feature.recipes.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.Add
import casa.moller.tandem.core.designsystem.icon.BookmarkFilled
import casa.moller.tandem.core.designsystem.icon.ChevronRight
import casa.moller.tandem.core.designsystem.icon.CollectionsBookmark
import casa.moller.tandem.core.designsystem.icon.FavoriteFilled
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.icon.Tune
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.book.RecipeBook
import casa.moller.tandem.core.model.book.RecipeBookKind
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.navigation.AppChromeDemoBanner
import casa.moller.tandem.core.ui.navigation.AppChromeStatusChip
import casa.moller.tandem.core.ui.readonly.ReadOnlyBanner
import casa.moller.tandem.feature.recipes.R

/** Test tag of the books list. */
const val BOOKS_LIST_TAG: String = "books_list"

/**
 * All books (plan 6.2 "Böcker/Favoriter", Stitch receptbok_master_detail_surfplatta,
 * recept_s_k_filter_samlingar): the two collections ("Favoriter" merged from everyone, "Vill
 * laga"), the user's own books and the books other members shared (read-only, plan 3.7).
 * Stateless: [state] in, [onAction] out. On wide windows the shell shows the opened book beside
 * this list ([selectedBookId] highlighted); the grid takes as many columns as fit.
 */
@Composable
fun BooksScreen(
    state: BooksUiState,
    onAction: (BooksAction) -> Unit,
    onOpenBook: (Long) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenWantToCook: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    selectedBookId: Long? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TandemTopAppBar(
                title = stringResource(R.string.books_title),
                onNavigateBack = onBack,
                actions = { AppChromeStatusChip(Modifier.padding(end = TandemTheme.spacing.xs)) },
            )
        },
        floatingActionButton = {
            if (state.canCreate && !state.loading) {
                ExtendedFloatingActionButton(
                    onClick = { onAction(BooksAction.OpenCreate) },
                    icon = { Icon(TandemIcons.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.books_new)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.loading) {
            LoadingState(modifier = Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        val spacing = TandemTheme.spacing
        LazyVerticalGrid(
            columns = GridCells.Adaptive(BOOK_MIN_CELL),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag(BOOKS_LIST_TAG),
            contentPadding = PaddingValues(start = spacing.lg, end = spacing.lg, top = spacing.sm, bottom = FAB_CLEARANCE),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            full("demo") { AppChromeDemoBanner() }
            if (state.readOnly) full("guest") { ReadOnlyBanner() }
            item(key = "favorites") {
                CollectionCard(
                    icon = TandemIcons.FavoriteFilled,
                    title = stringResource(R.string.books_favorites_title),
                    supporting = stringResource(R.string.books_favorites_supporting),
                    count = state.favoriteCount,
                    onClick = onOpenFavorites,
                    selected = selectedBookId == FAVORITES_SELECTION,
                )
            }
            item(key = "want") {
                CollectionCard(
                    icon = TandemIcons.BookmarkFilled,
                    title = stringResource(R.string.books_want_to_cook_title),
                    supporting = stringResource(R.string.books_want_to_cook_supporting),
                    count = state.wantToCookCount,
                    onClick = onOpenWantToCook,
                    selected = selectedBookId == WANT_TO_COOK_SELECTION,
                )
            }
            full("own-title") { SectionTitle(stringResource(R.string.books_section_own)) }
            if (state.own.isEmpty()) {
                full("own-empty") {
                    Text(
                        text = stringResource(R.string.books_own_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            bookItems(state.own, selectedBookId, onOpenBook)
            if (state.shared.isNotEmpty()) {
                full("shared-title") { SectionTitle(stringResource(R.string.books_section_shared)) }
                bookItems(state.shared, selectedBookId, onOpenBook)
            }
        }
    }
    if (state.creating) {
        NewBookDialog(onCreate = { onAction(BooksAction.Create(it)) }, onDismiss = { onAction(BooksAction.DismissCreate) })
    }
}

/** [BooksScreen]'s `selectedBookId` for the two collections (never a book id: those are server ids or negative). */
const val FAVORITES_SELECTION: Long = Long.MIN_VALUE
const val WANT_TO_COOK_SELECTION: Long = Long.MIN_VALUE + 1

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

private fun LazyGridScope.bookItems(books: List<RecipeBook>, selectedBookId: Long?, onOpenBook: (Long) -> Unit) {
    items(books, key = { "book-${it.id}" }) { book ->
        BookRow(book = book, selected = book.id == selectedBookId, onClick = { onOpenBook(book.id) })
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(top = TandemTheme.spacing.sm)
            .semantics { heading() },
    )
}

/** "Favoriter" / "Vill laga": one TalkBack item ("Favoriter, 24 recept, Allas favoriter i hushållet"). */
@Composable
private fun CollectionCard(icon: ImageVector, title: String, supporting: String, count: Int, onClick: () -> Unit, selected: Boolean) {
    val countText = pluralStringResource(R.plurals.books_recipe_count, count, count)
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(TandemTheme.spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(TandemTheme.spacing.sm)
                        .size(TandemTheme.sizes.icon),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(text = countText, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One book: name, size (or "Smart bok") and, for someone else's book, who shared it (read-only). */
@Composable
private fun BookRow(book: RecipeBook, selected: Boolean, onClick: () -> Unit) {
    val supporting = buildList {
        add(
            if (book.kind == RecipeBookKind.SMART) {
                stringResource(R.string.books_smart)
            } else {
                pluralStringResource(R.plurals.books_recipe_count, book.recipeCount, book.recipeCount)
            },
        )
        if (!book.isOwn) {
            add(book.ownerName?.let { stringResource(R.string.books_shared_from, it) } ?: stringResource(R.string.books_shared_read_only))
        }
    }.joinToString(" · ")
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = TandemTheme.spacing.lg, vertical = TandemTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when {
                    !book.isOwn -> TandemIcons.Lock
                    book.kind == RecipeBookKind.SMART -> TandemIcons.Tune
                    else -> TandemIcons.CollectionsBookmark
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = book.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(text = supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(imageVector = TandemIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Ny receptbok": a name, then [onCreate] (offline too; the book is sent later). */
@Composable
internal fun NewBookDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    val confirmText = stringResource(R.string.books_new_create)
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.books_new_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.books_new_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onCreate(name) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TandemPrimaryButton(text = confirmText, onClick = { onCreate(name) }, enabled = name.isNotBlank()) },
        dismissButton = { TandemTextButton(text = stringResource(R.string.books_cancel), onClick = onDismiss) },
    )
}

private val BOOK_MIN_CELL = 320.dp

/** Room below the list for the "Ny bok" button. */
private val FAB_CLEARANCE = 96.dp
