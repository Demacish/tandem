package casa.moller.tandem.di

import casa.moller.tandem.core.data.account.AccountDirectory
import casa.moller.tandem.core.model.account.AccountSummary
import casa.moller.tandem.core.model.account.Household
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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
 * - meal plan lane: TemporaryFas2MealPlanModule (removed: core:sync's MealPlanModule binds it),
 * - books lane: TemporaryFas2BookModule (removed: core:sync BookModule binds RecipeBookRepository and,
 *   with the guest mode #42, SpacePermissionsRepository),
 * - household lane: TemporaryFas2AccountModule (AccountDirectory),
 * - sync lane: TemporaryFas2SyncModule (ForegroundSync).
 * The integration lane deletes this file. Nothing here may ship in 0.3.0.
 */

private fun <T> notYet(): Outcome<T> = Outcome.Failure(TandemError.Unknown("tandem.temporary.fas2"))

@Module
@InstallIn(SingletonComponent::class)
internal interface TemporaryFas2AccountModule {
    @Binds
    fun bindAccountDirectory(impl: TemporaryAccountDirectory): AccountDirectory
}

internal class TemporaryAccountDirectory @Inject constructor() : AccountDirectory {
    override fun observeAccounts(): Flow<List<AccountSummary>> = flowOf(emptyList())

    override suspend fun switchTo(accountId: String): Outcome<Unit> = notYet()

    override fun observeHousehold(): Flow<Household?> = flowOf(null)

    override suspend fun refreshHousehold(): Outcome<Household?> = Outcome.Success(null)
}
