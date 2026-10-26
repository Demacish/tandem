package casa.moller.tandem.feature.recipes.books

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.model.book.RecipeBook
import casa.moller.tandem.core.model.book.RecipeBookKind
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeRecipeBookRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.feature.recipes.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The books ViewModels on core:testing's fakes (plan 3.7, #17, #42): own vs shared books, what
 * may be removed where, the detail's buttons, the hearts, and a guest who changes nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BooksViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val pasta = SampleRecipes.MushroomPasta.id
    private val pancakes = SampleRecipes.Pancakes.id
    private val own = RecipeBook(id = 1, name = "Vardag", ownerId = 1, isOwn = true)
    private val theirs = RecipeBook(id = 2, name = "Alex fest", ownerId = 2, ownerName = "Alex")
    private val ownFavorites = RecipeBook(id = 3, name = "Favoriter (Kim)", kind = RecipeBookKind.FAVORITES, ownerId = 1, isOwn = true)
    private val theirFavorites =
        RecipeBook(id = 4, name = "Favoriter (Alex)", kind = RecipeBookKind.FAVORITES, ownerId = 2, ownerName = "Alex")
    private val books = FakeRecipeBookRepository(listOf(own, theirs, ownFavorites, theirFavorites)).apply {
        entries.value = mapOf(1L to listOf(pasta), 2L to listOf(pancakes), 3L to listOf(pasta), 4L to listOf(pancakes))
    }
    private val permissions = FakeSpacePermissionsRepository()

    private fun TestScope.collect(flow: kotlinx.coroutines.flow.Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    @Test
    fun theBooksListSeparatesOwnAndSharedBooksAndLeavesOutTheCollections() = runTest {
        val vm = BooksViewModel(books, permissions, SavedStateHandle())
        collect(vm.uiState)
        val state = vm.uiState.value
        assertEquals(listOf("Vardag"), state.own.map { it.name })
        assertEquals(listOf("Alex fest"), state.shared.map { it.name })
        assertEquals("everyone's favourites, merged", 2, state.favoriteCount)
        assertTrue(state.canCreate)
    }

    @Test
    fun aNewBookIsCreatedAndOpened() = runTest {
        val vm = BooksViewModel(books, permissions, SavedStateHandle())
        collect(vm.uiState)
        val effects = mutableListOf<BooksEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        vm.onAction(BooksAction.OpenCreate)
        assertTrue(vm.uiState.value.creating)
        vm.onAction(BooksAction.Create("  Helgmat "))
        assertFalse(vm.uiState.value.creating)
        assertTrue(books.books.value.any { it.name == "Helgmat" && it.isOwn })
        assertTrue(effects.single() is BooksEffect.Created)
    }

    @Test
    fun aGuestCannotCreateBooks() = runTest {
        permissions.setGuest(true)
        val vm = BooksViewModel(books, permissions, SavedStateHandle())
        collect(vm.uiState)
        assertTrue(vm.uiState.value.readOnly)
        vm.onAction(BooksAction.OpenCreate)
        assertFalse(vm.uiState.value.creating)
        vm.onAction(BooksAction.Create("Mina"))
        assertEquals(4, books.books.value.size)
    }

    @Test
    fun someoneElsesBookIsReadOnly() = runTest {
        val vm = BookRecipesViewModel(BookSource.Book(theirs.id), books, permissions, FakeSyncRepository())
        collect(vm.uiState)
        val state = vm.uiState.value
        assertTrue(state.sharedByOther)
        assertEquals("Alex", state.ownerName)
        assertFalse(state.canRemove)
        vm.onAction(BookRecipesAction.Remove(pancakes))
        assertEquals("nothing is written to a shared book", listOf(pancakes), books.entries.value[theirs.id])
    }

    @Test
    fun aRecipeIsTakenOutOfAnOwnBook() = runTest {
        val vm = BookRecipesViewModel(BookSource.Book(own.id), books, permissions, FakeSyncRepository())
        collect(vm.uiState)
        assertTrue(vm.uiState.value.canRemove)
        assertEquals(listOf(SampleRecipes.MushroomPasta.name), vm.uiState.value.recipes.map { it.name })
        vm.onAction(BookRecipesAction.Remove(pasta))
        assertTrue(books.entries.value[own.id].orEmpty().isEmpty())
        assertTrue(vm.uiState.value.recipes.isEmpty())
    }

    @Test
    fun onlyTheOwnFavouritesCanBeTakenOutOfTheMergedView() = runTest {
        val vm = BookRecipesViewModel(BookSource.Favorites, books, permissions, FakeSyncRepository())
        collect(vm.uiState)
        val state = vm.uiState.value
        val mine = state.recipes.single { it.id == pasta }
        val alexs = state.recipes.single { it.id == pancakes }
        assertTrue(state.canRemove(mine))
        assertFalse(state.canRemove(alexs))
        vm.onAction(BookRecipesAction.Remove(pancakes))
        assertEquals(listOf(pancakes), books.entries.value[theirFavorites.id])
        vm.onAction(BookRecipesAction.Remove(pasta))
        assertTrue(books.entries.value[ownFavorites.id].orEmpty().isEmpty())
    }

    @Test
    fun wantToCookIsTheSharedQueue() = runTest {
        books.wantToCook.value = setOf(pasta)
        val vm = BookRecipesViewModel(BookSource.WantToCook, books, permissions, FakeSyncRepository())
        collect(vm.uiState)
        assertEquals(RecipeBookKind.WANT_TO_COOK, vm.uiState.value.kind)
        assertTrue(vm.uiState.value.canRemove)
        vm.onAction(BookRecipesAction.Remove(pasta))
        assertTrue(books.wantToCook.value.isEmpty())
    }

    /** fix-ui: taking a recipe out of a book, Favoriter or the shared "Vill laga" can be undone. */
    @Test
    fun aRemovedRecipeComesBackWithUndo() = runTest {
        books.wantToCook.value = setOf(pasta)
        for (source in listOf(BookSource.Book(own.id), BookSource.Favorites, BookSource.WantToCook)) {
            val vm = BookRecipesViewModel(source, books, permissions, FakeSyncRepository())
            collect(vm.uiState)
            val effects = mutableListOf<BookRecipesEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
            val kind = vm.uiState.value.kind
            vm.onAction(BookRecipesAction.Remove(pasta))
            assertEquals(listOf<BookRecipesEffect>(BookRecipesEffect.Removed(SampleRecipes.MushroomPasta.name, kind)), effects)
            assertTrue("$source: gone", vm.uiState.value.recipes.none { it.id == pasta })
            vm.onAction(BookRecipesAction.Undo)
            assertTrue("$source: back", vm.uiState.value.recipes.any { it.id == pasta })
        }
        assertEquals(listOf(pasta), books.entries.value[own.id])
        assertEquals(listOf(pasta), books.entries.value[ownFavorites.id])
        assertEquals(setOf(pasta), books.wantToCook.value)
    }

    @Test
    fun theDetailButtonsToggleFavouriteWantToCookAndOwnBooks() = runTest {
        val vm = RecipeBookButtonsViewModel(pancakes, books, permissions, SavedStateHandle())
        collect(vm.uiState)
        val effects = mutableListOf<BookRecipesEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        assertEquals(listOf("Alex"), vm.uiState.value.favoriteOf)
        assertEquals("only own plain books in the sheet", listOf("Vardag"), vm.uiState.value.books.map { it.name })

        vm.onAction(RecipeBookButtonsAction.ToggleFavorite)
        assertTrue(vm.uiState.value.isFavorite)
        vm.onAction(RecipeBookButtonsAction.ToggleWantToCook)
        assertTrue(vm.uiState.value.wantToCook)
        vm.onAction(RecipeBookButtonsAction.ToggleBook(own.id))
        assertTrue(vm.uiState.value.books.single().contains)
        vm.onAction(RecipeBookButtonsAction.CreateBook("Helgmat"))
        assertTrue(vm.uiState.value.books.single { it.name == "Helgmat" }.contains)
        assertEquals(
            listOf(R.string.books_favorite_added, R.string.books_want_to_cook_added, R.string.books_added, R.string.books_added),
            effects.map { (it as BookRecipesEffect.Message).text },
        )
    }

    @Test
    fun aGuestGetsNoButtonsAndChangesNothing() = runTest {
        permissions.setGuest(true)
        val vm = RecipeBookButtonsViewModel(pasta, books, permissions, SavedStateHandle())
        collect(vm.uiState)
        assertTrue(vm.uiState.value.readOnly)
        vm.onAction(RecipeBookButtonsAction.ToggleFavorite)
        vm.onAction(RecipeBookButtonsAction.ToggleWantToCook)
        vm.onAction(RecipeBookButtonsAction.OpenBooks)
        assertFalse(vm.uiState.value.sheetOpen)
        assertTrue(books.wantToCook.value.isEmpty())
        assertEquals(listOf(pasta), books.entries.value[ownFavorites.id])
    }

    @Test
    fun theHeartsAndCollectionsOfTheRecipeList() = runTest {
        val vm = RecipeCollectionsViewModel(books, permissions)
        collect(vm.uiState)
        assertEquals(setOf(pasta), vm.uiState.value.favoriteIds)
        assertEquals(2, vm.uiState.value.favoriteCount)
        assertEquals(2, vm.uiState.value.bookCount)
        vm.toggleFavorite(pancakes)
        assertEquals(setOf(pasta, pancakes), vm.uiState.value.favoriteIds)
        permissions.setGuest(true)
        vm.toggleFavorite(pasta)
        assertEquals("a guest's heart does nothing", setOf(pasta, pancakes), vm.uiState.value.favoriteIds)
    }
}
