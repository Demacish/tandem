package casa.moller.tandem.core.testing

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.book.RecipeBook
import casa.moller.tandem.core.model.book.RecipeBookKind
import casa.moller.tandem.core.model.error.errorOrNull
import casa.moller.tandem.core.model.error.getOrNull
import casa.moller.tandem.core.model.mealplan.MealPlanDraft
import casa.moller.tandem.core.model.mealplan.MealPlanUpdate
import casa.moller.tandem.core.model.mealplan.MealPlanWeek
import casa.moller.tandem.core.model.shopping.ShoppingEntryDraft
import casa.moller.tandem.core.model.shopping.ShoppingFilter
import casa.moller.tandem.core.model.sync.ForegroundPolling
import casa.moller.tandem.core.model.sync.ForegroundTarget
import casa.moller.tandem.core.testing.data.FakeForegroundSync
import casa.moller.tandem.core.testing.data.FakeIngredientParser
import casa.moller.tandem.core.testing.data.FakeMealPlanRepository
import casa.moller.tandem.core.testing.data.FakeRecipeBookRepository
import casa.moller.tandem.core.testing.data.FakeShoppingRepository
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.testing.data.SampleShopping
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Phase 2 fakes and pure types behave like the contracts in core:data say (docs/BYGGE.md "Fas 2 – kontrakt"). */
class Fas2FakesTest {

    @Test
    fun newEntriesGetNegativeIdsAndChecksAreVisibleAtOnce() = runTest {
        val shopping = FakeShoppingRepository()
        val id = shopping.add(ShoppingEntryDraft("bröd", amount = 1.0)).getOrNull()

        assertEquals(-1L, id)
        shopping.setChecked(listOf(-1L, 1L), checked = true)
        val entries = shopping.observeEntries().first()
        assertTrue(entries.first { it.id == -1L }.checked && entries.first { it.id == -1L }.isLocalOnly)
        assertTrue(entries.first { it.id == 1L }.pending)
        assertEquals(listOf(5L), shopping.observeEntries(ShoppingFilter(listId = 1)).first().map { it.id })
    }

    @Test
    fun aGuestCannotWrite() = runTest {
        val shopping = FakeShoppingRepository(readOnly = true)
        val books = FakeRecipeBookRepository(readOnly = true)
        val plan = FakeMealPlanRepository(readOnly = true)

        assertEquals(DataProblem.READ_ONLY, shopping.add(ShoppingEntryDraft("godis")).errorOrNull()?.dataProblem())
        assertEquals(DataProblem.READ_ONLY, books.setFavorite(101, true).errorOrNull()?.dataProblem())
        assertEquals(DataProblem.READ_ONLY, plan.delete(1).errorOrNull()?.dataProblem())
        assertTrue(SpacePermissions(SpaceRole.GUEST).readOnly)
        assertFalse("demo mode can write", SpacePermissions(SpaceRole.GUEST, isDemo = true).readOnly)
        assertEquals(SpaceRole.ADMIN, SpaceRole.of(listOf("user", "admin")))
    }

    @Test
    fun delayedUntilTheFutureOnly() {
        val entry = SampleShopping.entries.first()
        assertTrue(entry.copy(delayUntil = SampleShopping.now + 4.hours).isDelayed(SampleShopping.now))
        // Tandoor's undo value, 1970-01-01, is in the past.
        assertFalse(entry.copy(delayUntil = kotlin.time.Instant.fromEpochSeconds(0)).isDelayed(SampleShopping.now))
    }

    @Test
    fun onlyOwnBooksCanBeChanged() = runTest {
        val shared = RecipeBook(7, "Annas", kind = RecipeBookKind.BOOK, ownerId = 2, isOwn = false)
        val books = FakeRecipeBookRepository(books = listOf(shared))

        assertEquals(DataProblem.NOT_OWNER, books.addToBook(7, 101).errorOrNull()?.dataProblem())
        books.setFavorite(SampleRecipes.overviews.first().id, favorite = true)
        val status = books.observeStatus(SampleRecipes.overviews.first().id).first()
        assertTrue(status.isFavorite)
        assertEquals(1, books.observeFavorites().first().size)
    }

    @Test
    fun aMoveSetsBothDaysAndTheWeekStartsOnMonday() = runTest {
        val plan = FakeMealPlanRepository()
        val saturday = LocalDate(2000, 1, 15)
        val id = checkNotNull(plan.create(MealPlanDraft(saturday, mealTypeId = 1, title = "Rester")).getOrNull())

        assertEquals(LocalDate(2000, 1, 10), MealPlanWeek.startOf(saturday))
        assertEquals(MealPlanUpdate(LocalDate(2000, 1, 16), LocalDate(2000, 1, 17)), MealPlanUpdate.move(LocalDate(2000, 1, 16), days = 2))
        plan.update(id, MealPlanUpdate.move(LocalDate(2000, 1, 16)))
        val week = plan.observeWeek(LocalDate(2000, 1, 10)).first()
        assertEquals(listOf("Rester"), week.days.last().meals.map { it.displayTitle })
        assertEquals(LocalDate(2000, 1, 16), week.end)
    }

    @Test
    fun theLocalParserSplitsAmountUnitFoodAndNote() = runTest {
        val parser = FakeIngredientParser()
        val parsed = parser.parse(listOf("2 dl grädde", "mjölk", "1,5 kg potatis, fast", " ")).getOrNull().orEmpty()

        assertEquals(listOf(2.0, 0.0, 1.5), parsed.map { it.amount })
        assertEquals(listOf("dl", null, "kg"), parsed.map { it.unitName })
        assertEquals(listOf("grädde", "mjölk", "potatis"), parsed.map { it.foodName })
        assertEquals("fast", parsed.last().note)
    }

    @Test
    fun pollingNeverRunsFasterThanTheFloors() {
        assertEquals(20.seconds, ForegroundPolling.intervalFor(5))
        assertEquals(60.seconds, ForegroundPolling.intervalFor(60))
        assertEquals(20.seconds, ForegroundPolling.intervalFor(null))
        val sync = FakeForegroundSync()
        sync.keepFresh(ForegroundTarget.ShoppingList)
        assertEquals(listOf<ForegroundTarget>(ForegroundTarget.ShoppingList), sync.started)
    }
}
