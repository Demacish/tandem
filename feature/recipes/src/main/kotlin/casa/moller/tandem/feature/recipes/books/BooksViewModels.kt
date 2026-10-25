package casa.moller.tandem.feature.recipes.books

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.book.RecipeBookRepository
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.sync.SyncRepository
import casa.moller.tandem.core.model.book.RecipeBookKind
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.feature.recipes.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Phase 2 (books lane): the ViewModels of the books screens (plan 3.7, 6.2, #17, #42). They talk
// to core:data only (RecipeBookRepository, SpacePermissionsRepository, SyncRepository).

private const val STOP_TIMEOUT_MILLIS = 5_000L

/** The message for a refused or failed book write. */
internal fun bookWriteFailed(error: TandemError): Int = when (error.dataProblem()) {
    DataProblem.READ_ONLY -> R.string.books_error_read_only
    DataProblem.NOT_OWNER -> R.string.books_error_not_owner
    else -> R.string.books_error_failed
}

/** All books: the two collections, own books and books shared with the user. */
@HiltViewModel
class BooksViewModel @Inject constructor(
    private val books: RecipeBookRepository,
    permissions: SpacePermissionsRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val effectChannel = Channel<BooksEffect>(Channel.BUFFERED)
    val effects: Flow<BooksEffect> = effectChannel.receiveAsFlow()

    val uiState: StateFlow<BooksUiState> = combine(
        books.observeBooks(),
        books.observeFavorites().map { it.size },
        books.observeWantToCook().map { it.size },
        permissions.permissions.map { it.readOnly }.distinctUntilChanged(),
        savedStateHandle.getStateFlow(KEY_CREATING, false),
    ) { all, favorites, wantToCook, readOnly, creating ->
        val listed = all.filter { it.kind == RecipeBookKind.BOOK || it.kind == RecipeBookKind.SMART }
        BooksUiState(
            loading = false,
            favoriteCount = favorites,
            wantToCookCount = wantToCook,
            own = listed.filter { it.isOwn },
            shared = listed.filterNot { it.isOwn },
            readOnly = readOnly,
            creating = creating && !readOnly,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BooksUiState())

    init {
        // A quiet re-read when the books are opened (small tables, plan 3.5); offline it does nothing.
        viewModelScope.launch { books.refresh() }
    }

    fun onAction(action: BooksAction) {
        when (action) {
            BooksAction.Refresh -> viewModelScope.launch { books.refresh() }
            BooksAction.OpenCreate -> if (uiState.value.canCreate) savedStateHandle[KEY_CREATING] = true
            BooksAction.DismissCreate -> savedStateHandle[KEY_CREATING] = false
            is BooksAction.Create -> create(action.name)
        }
    }

    private fun create(name: String) {
        if (name.isBlank() || !uiState.value.canCreate) return
        savedStateHandle[KEY_CREATING] = false
        viewModelScope.launch {
            when (val created = books.createBook(name.trim())) {
                is Outcome.Success -> effectChannel.trySend(BooksEffect.Created(created.value))
                is Outcome.Failure -> effectChannel.trySend(BooksEffect.Message(bookWriteFailed(created.error)))
            }
        }
    }

    private companion object {
        const val KEY_CREATING = "books_creating"
    }
}

/** One book's recipes, everyone's favourites or "Vill laga" ([BookSource]). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = BookRecipesViewModel.Factory::class)
class BookRecipesViewModel @AssistedInject constructor(
    @Assisted private val source: BookSource,
    private val books: RecipeBookRepository,
    permissions: SpacePermissionsRepository,
    sync: SyncRepository,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(source: BookSource): BookRecipesViewModel
    }

    private val effectChannel = Channel<BookRecipesEffect>(Channel.BUFFERED)
    val effects: Flow<BookRecipesEffect> = effectChannel.receiveAsFlow()

    private val readOnly = permissions.permissions.map { it.readOnly }.distinctUntilChanged()
    private val offline = sync.status.map { it.pause == SyncPause.OFFLINE }.distinctUntilChanged()

    val uiState: StateFlow<BookRecipesUiState> = when (source) {
        is BookSource.Book -> combine(
            books.observeBook(source.id),
            books.observeRecipes(source.id),
            readOnly,
            offline,
        ) { book, recipes, ro, off ->
            if (book == null) {
                BookRecipesUiState(loading = false, missing = true, readOnly = ro, offline = off)
            } else {
                BookRecipesUiState(
                    loading = false,
                    title = book.name,
                    description = book.description,
                    kind = book.kind,
                    isOwn = book.isOwn,
                    ownerName = book.ownerName,
                    recipes = recipes,
                    readOnly = ro,
                    canRemove = book.canEdit && !ro,
                    offline = off,
                )
            }
        }

        BookSource.Favorites -> combine(books.observeFavorites(), books.observeFavoriteIds(), readOnly, offline) { recipes, own, ro, off ->
            BookRecipesUiState(
                loading = false,
                kind = RecipeBookKind.FAVORITES,
                isOwn = false,
                recipes = recipes,
                ownFavoriteIds = own,
                readOnly = ro,
                canRemove = !ro,
                offline = off,
            )
        }

        BookSource.WantToCook -> combine(books.observeWantToCook(), readOnly, offline, flowOf(Unit)) { recipes, ro, off, _ ->
            BookRecipesUiState(
                loading = false,
                kind = RecipeBookKind.WANT_TO_COOK,
                isOwn = true,
                recipes = recipes,
                readOnly = ro,
                canRemove = !ro,
                offline = off,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BookRecipesUiState())

    fun onAction(action: BookRecipesAction) {
        when (action) {
            BookRecipesAction.Refresh -> viewModelScope.launch { books.refresh() }
            is BookRecipesAction.Remove -> remove(action.recipeId)
        }
    }

    private fun remove(recipeId: Long) {
        val state = uiState.value
        val recipe = state.recipes.firstOrNull { it.id == recipeId } ?: return
        if (!state.canRemove(recipe)) return
        viewModelScope.launch {
            val result = when (source) {
                is BookSource.Book -> books.removeFromBook(source.id, recipeId)
                BookSource.Favorites -> books.setFavorite(recipeId, false)
                BookSource.WantToCook -> books.setWantToCook(recipeId, false)
            }
            val message = when (result) {
                is Outcome.Success -> R.string.books_removed
                is Outcome.Failure -> bookWriteFailed(result.error)
            }
            effectChannel.trySend(BookRecipesEffect.Message(message))
        }
    }
}

/** The favourite, "Vill laga" and "Lägg i bok" buttons of one recipe's detail. */
@HiltViewModel(assistedFactory = RecipeBookButtonsViewModel.Factory::class)
class RecipeBookButtonsViewModel @AssistedInject constructor(
    @Assisted private val recipeId: Long,
    private val books: RecipeBookRepository,
    permissions: SpacePermissionsRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(recipeId: Long): RecipeBookButtonsViewModel
    }

    private val effectChannel = Channel<BookRecipesEffect>(Channel.BUFFERED)
    val effects: Flow<BookRecipesEffect> = effectChannel.receiveAsFlow()

    val uiState: StateFlow<RecipeBookButtonsState> = combine(
        books.observeStatus(recipeId),
        books.observeBooks(),
        permissions.permissions.map { it.readOnly }.distinctUntilChanged(),
        savedStateHandle.getStateFlow(KEY_SHEET, false),
    ) { status, all, readOnly, sheet ->
        RecipeBookButtonsState(
            isFavorite = status.isFavorite,
            wantToCook = status.wantToCook,
            favoriteOf = status.favoriteOf,
            books = all.filter { it.canEdit && it.kind == RecipeBookKind.BOOK }
                .map { BookChoice(it.id, it.name, contains = it.id in status.bookIds) },
            readOnly = readOnly,
            sheetOpen = sheet && !readOnly,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RecipeBookButtonsState())

    fun onAction(action: RecipeBookButtonsAction) {
        val state = uiState.value
        if (state.readOnly || !state.loaded) return
        when (action) {
            RecipeBookButtonsAction.ToggleFavorite -> write(
                if (state.isFavorite) R.string.books_favorite_removed else R.string.books_favorite_added,
            ) { books.setFavorite(recipeId, !state.isFavorite) }

            RecipeBookButtonsAction.ToggleWantToCook -> write(
                if (state.wantToCook) R.string.books_want_to_cook_removed else R.string.books_want_to_cook_added,
            ) { books.setWantToCook(recipeId, !state.wantToCook) }

            RecipeBookButtonsAction.OpenBooks -> savedStateHandle[KEY_SHEET] = true

            RecipeBookButtonsAction.DismissBooks -> savedStateHandle[KEY_SHEET] = false

            is RecipeBookButtonsAction.ToggleBook -> {
                val book = state.books.firstOrNull { it.id == action.bookId } ?: return
                write(if (book.contains) R.string.books_removed else R.string.books_added) {
                    if (book.contains) books.removeFromBook(book.id, recipeId) else books.addToBook(book.id, recipeId)
                }
            }

            is RecipeBookButtonsAction.CreateBook -> {
                val name = action.name.trim()
                if (name.isEmpty()) return
                write(R.string.books_added) {
                    when (val created = books.createBook(name)) {
                        is Outcome.Failure -> created
                        is Outcome.Success -> books.addToBook(created.value, recipeId)
                    }
                }
            }
        }
    }

    private fun write(success: Int, block: suspend () -> Outcome<*>) {
        viewModelScope.launch {
            val message = when (val result = block()) {
                is Outcome.Success -> success
                is Outcome.Failure -> bookWriteFailed(result.error)
            }
            effectChannel.trySend(BookRecipesEffect.Message(message))
        }
    }

    private companion object {
        const val KEY_SHEET = "books_sheet"
    }
}

/** The collections row and the hearts of the recipe list. */
@HiltViewModel
class RecipeCollectionsViewModel @Inject constructor(private val books: RecipeBookRepository, permissions: SpacePermissionsRepository) :
    ViewModel() {
    private val effectChannel = Channel<BookRecipesEffect>(Channel.BUFFERED)
    val effects: Flow<BookRecipesEffect> = effectChannel.receiveAsFlow()

    val uiState: StateFlow<RecipeCollectionsState> = combine(
        books.observeFavorites().map { it.size },
        books.observeWantToCook().map { it.size },
        books.observeBooks().map { list -> list.count { it.kind == RecipeBookKind.BOOK || it.kind == RecipeBookKind.SMART } },
        books.observeFavoriteIds(),
        permissions.permissions.map { it.readOnly }.distinctUntilChanged(),
    ) { favorites, wantToCook, bookCount, favoriteIds, readOnly ->
        RecipeCollectionsState(favorites, wantToCook, bookCount, favoriteIds, readOnly)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RecipeCollectionsState())

    /** The heart on a recipe card. */
    fun toggleFavorite(recipeId: Long) {
        val state = uiState.value
        if (state.readOnly) return
        val favorite = recipeId !in state.favoriteIds
        viewModelScope.launch {
            val message = when (val result = books.setFavorite(recipeId, favorite)) {
                is Outcome.Success -> if (favorite) R.string.books_favorite_added else R.string.books_favorite_removed
                is Outcome.Failure -> bookWriteFailed(result.error)
            }
            effectChannel.trySend(BookRecipesEffect.Message(message))
        }
    }
}
