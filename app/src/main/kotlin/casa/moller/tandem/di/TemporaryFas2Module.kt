package casa.moller.tandem.di

import casa.moller.tandem.core.data.account.AccountDirectory
import casa.moller.tandem.core.data.book.RecipeBookRepository
import casa.moller.tandem.core.data.mealplan.MealPlanRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.model.account.AccountSummary
import casa.moller.tandem.core.model.account.Household
import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.book.RecipeBook
import casa.moller.tandem.core.model.book.RecipeBookStatus
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.mealplan.MealPlanConflict
import casa.moller.tandem.core.model.mealplan.MealPlanDay
import casa.moller.tandem.core.model.mealplan.MealPlanDraft
import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.mealplan.MealPlanUpdate
import casa.moller.tandem.core.model.mealplan.MealPlanWeek
import casa.moller.tandem.core.model.mealplan.MealType
import casa.moller.tandem.core.model.mealplan.MealTypeDraft
import casa.moller.tandem.core.model.mealplan.WeekShoppingPreview
import casa.moller.tandem.core.model.mealplan.WeekShoppingSelection
import casa.moller.tandem.core.model.recipe.RecipeOverview
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/*
 * TEMPORARY (Phase 2 grund lane): stand-in bindings for the Phase 2 contracts in core:data, so the
 * app builds and runs while the lanes work in parallel (docs/BYGGE.md "Fas 2 – kontrakt"). They
 * show nothing (empty lists, no household, a member who may write) and refuse every write with
 * TandemError.Unknown("tandem.temporary.fas2"); nothing reaches the network or the database.
 *
 * Each lane that binds the real implementation (@Binds in its own module, e.g. core:sync's
 * SyncModule) DELETES the matching module below in the same change, otherwise Hilt reports a
 * duplicate binding:
 * - shopping lane: TemporaryFas2ShoppingModule (ShoppingRepository, SupermarketRepository, IngredientParser),
 * - meal plan lane: TemporaryFas2MealPlanModule,
 * - books lane: TemporaryFas2BookModule,
 * - household/guest lane: TemporaryFas2AccountModule (AccountDirectory, SpacePermissionsRepository),
 * - sync lane: TemporaryFas2SyncModule (ForegroundSync).
 * The integration lane deletes this file. Nothing here may ship in 0.3.0.
 */

private fun <T> notYet(): Outcome<T> = Outcome.Failure(TandemError.Unknown("tandem.temporary.fas2"))

@Module
@InstallIn(SingletonComponent::class)
internal interface TemporaryFas2MealPlanModule {
    @Binds
    fun bindMealPlanRepository(impl: TemporaryMealPlanRepository): MealPlanRepository
}

@Module
@InstallIn(SingletonComponent::class)
internal interface TemporaryFas2BookModule {
    @Binds
    fun bindRecipeBookRepository(impl: TemporaryRecipeBookRepository): RecipeBookRepository
}

@Module
@InstallIn(SingletonComponent::class)
internal interface TemporaryFas2AccountModule {
    @Binds
    fun bindAccountDirectory(impl: TemporaryAccountDirectory): AccountDirectory

    @Binds
    fun bindSpacePermissionsRepository(impl: TemporarySpacePermissionsRepository): SpacePermissionsRepository
}

internal class TemporaryMealPlanRepository @Inject constructor() : MealPlanRepository {
    override fun observeWeek(start: LocalDate): Flow<MealPlanWeek> =
        flowOf(MealPlanWeek(start, (0 until MealPlanWeek.DAYS).map { MealPlanDay(start.plus(it, DateTimeUnit.DAY)) }))

    override fun observeMeal(id: Long): Flow<MealPlanEntry?> = flowOf(null)

    override fun observeMealTypes(): Flow<List<MealType>> = flowOf(emptyList())

    override suspend fun create(draft: MealPlanDraft): Outcome<Long> = notYet()

    override suspend fun update(id: Long, update: MealPlanUpdate): Outcome<Unit> = notYet()

    override suspend fun delete(id: Long): Outcome<Unit> = notYet()

    override suspend fun createMealType(draft: MealTypeDraft): Outcome<Long> = notYet()

    override fun observeConflicts(): Flow<List<MealPlanConflict>> = flowOf(emptyList())

    override suspend fun resolveConflict(conflict: MealPlanConflict, keepMine: Boolean): Outcome<Unit> = notYet()

    override suspend fun refresh(): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun weekShoppingPreview(start: LocalDate): Outcome<WeekShoppingPreview> =
        Outcome.Success(WeekShoppingPreview(emptyList()))

    override suspend fun addWeekToShopping(selection: WeekShoppingSelection): Outcome<Unit> = notYet()
}

internal class TemporaryRecipeBookRepository @Inject constructor() : RecipeBookRepository {
    override fun observeBooks(): Flow<List<RecipeBook>> = flowOf(emptyList())

    override fun observeBook(id: Long): Flow<RecipeBook?> = flowOf(null)

    override fun observeRecipes(bookId: Long): Flow<List<RecipeOverview>> = flowOf(emptyList())

    override fun observeFavorites(): Flow<List<RecipeOverview>> = flowOf(emptyList())

    override fun observeWantToCook(): Flow<List<RecipeOverview>> = flowOf(emptyList())

    override fun observeStatus(recipeId: Long): Flow<RecipeBookStatus> = flowOf(RecipeBookStatus(recipeId))

    override suspend fun createBook(name: String, description: String): Outcome<Long> = notYet()

    override suspend fun addToBook(bookId: Long, recipeId: Long): Outcome<Unit> = notYet()

    override suspend fun removeFromBook(bookId: Long, recipeId: Long): Outcome<Unit> = notYet()

    override suspend fun setFavorite(recipeId: Long, favorite: Boolean): Outcome<Unit> = notYet()

    override suspend fun setWantToCook(recipeId: Long, wantToCook: Boolean): Outcome<Unit> = notYet()

    override suspend fun refresh(): Outcome<Unit> = Outcome.Success(Unit)
}

internal class TemporaryAccountDirectory @Inject constructor() : AccountDirectory {
    override fun observeAccounts(): Flow<List<AccountSummary>> = flowOf(emptyList())

    override suspend fun switchTo(accountId: String): Outcome<Unit> = notYet()

    override fun observeHousehold(): Flow<Household?> = flowOf(null)

    override suspend fun refreshHousehold(): Outcome<Household?> = Outcome.Success(null)
}

internal class TemporarySpacePermissionsRepository @Inject constructor() : SpacePermissionsRepository {
    override val permissions: Flow<SpacePermissions> = flowOf(SpacePermissions())
}
