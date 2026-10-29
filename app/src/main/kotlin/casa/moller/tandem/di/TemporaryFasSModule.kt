package casa.moller.tandem.di

import casa.moller.tandem.core.data.ai.AiSettingsRepository
import casa.moller.tandem.core.data.drink.DrinkRepository
import casa.moller.tandem.core.data.pantry.MakeNowRepository
import casa.moller.tandem.core.data.pantry.PantryRepository
import casa.moller.tandem.core.data.pantry.StaplesRepository
import casa.moller.tandem.core.data.scan.BarcodeLookup
import casa.moller.tandem.core.data.scan.FoodAliasRepository
import casa.moller.tandem.core.datastore.AiKeyStore
import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.core.model.ai.AiSettings
import casa.moller.tandem.core.model.drink.Drink
import casa.moller.tandem.core.model.drink.DrinkDraft
import casa.moller.tandem.core.model.drink.Pairing
import casa.moller.tandem.core.model.drink.RecipePairings
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.makenow.MakeNowFilter
import casa.moller.tandem.core.model.makenow.MakeNowGroup
import casa.moller.tandem.core.model.makenow.MakeNowRecipe
import casa.moller.tandem.core.model.makenow.MakeNowState
import casa.moller.tandem.core.model.makenow.MissingFood
import casa.moller.tandem.core.model.pantry.InventoryEntry
import casa.moller.tandem.core.model.pantry.InventoryEntryDraft
import casa.moller.tandem.core.model.pantry.InventoryEntryUpdate
import casa.moller.tandem.core.model.pantry.InventoryLocation
import casa.moller.tandem.core.model.pantry.PantryGroup
import casa.moller.tandem.core.model.pantry.Staple
import casa.moller.tandem.core.model.pantry.UnpackChoice
import casa.moller.tandem.core.model.pantry.UnpackItem
import casa.moller.tandem.core.model.scan.BarcodeLookupResult
import casa.moller.tandem.core.model.scan.FoodAlias
import casa.moller.tandem.core.model.scan.FoodAliasKind
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate

// Fas S (grund lane, docs/BYGGE.md "Fas S – kontrakt"): TEMPORARY stand-ins so the app builds and
// runs before the lanes land. They are empty and every write fails with [NOT_BUILT]; no screen
// reaches them yet (the entry points come with the lanes). Each lane deletes its module here in
// the same commit that binds the real implementation in core:sync / core:datastore; the last one
// deletes the file. (core:testing's fakes cannot be used in a main configuration: checkModuleGraph
// rule 4.) FridgeVision is bound by core:ai itself (AiModule).

/** Hemma lane (hemma-data): pantry, staples, "Kan laga nu". */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class TemporaryFasSPantryModule {
    @Binds
    abstract fun bindPantry(impl: NotBuiltPantryRepository): PantryRepository

    @Binds
    abstract fun bindStaples(impl: NotBuiltStaplesRepository): StaplesRepository

    @Binds
    abstract fun bindMakeNow(impl: NotBuiltMakeNowRepository): MakeNowRepository
}

/** Scanning lane (skanning): learned aliases and Open Food Facts. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class TemporaryFasSScanModule {
    @Binds
    abstract fun bindFoodAliases(impl: NotBuiltFoodAliasRepository): FoodAliasRepository

    @Binds
    abstract fun bindBarcodeLookup(impl: NotBuiltBarcodeLookup): BarcodeLookup
}

/** Drinks lane (drycker). */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class TemporaryFasSDrinkModule {
    @Binds
    abstract fun bindDrinks(impl: NotBuiltDrinkRepository): DrinkRepository
}

/** AI lane (ai-foto): settings and the encrypted key store. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class TemporaryFasSAiModule {
    @Binds
    abstract fun bindAiSettings(impl: NotBuiltAiSettingsRepository): AiSettingsRepository

    @Binds
    abstract fun bindAiKeys(impl: NotBuiltAiKeyStore): AiKeyStore
}

private val NOT_BUILT = TandemError.Unknown("tandem.fas-s.not-built-yet")

private fun <T> notBuilt(): Outcome<T> = Outcome.Failure(NOT_BUILT)

@Singleton
internal class NotBuiltPantryRepository @Inject constructor() : PantryRepository {
    override fun observeLocations(): Flow<List<InventoryLocation>> = flowOf(emptyList())

    override fun observePantry(): Flow<List<PantryGroup>> = flowOf(emptyList())

    override fun observeEntry(id: Long): Flow<InventoryEntry?> = flowOf(null)

    override fun observeExpiring(today: LocalDate, days: Int): Flow<List<InventoryEntry>> = flowOf(emptyList())

    override fun observeAvailableFoodIds(): Flow<Set<Long>> = flowOf(emptySet())

    override suspend fun createLocation(name: String, isFreezer: Boolean): Outcome<InventoryLocation> = notBuilt()

    override suspend fun renameLocation(id: Long, name: String, isFreezer: Boolean): Outcome<Unit> = notBuilt()

    override suspend fun deleteLocation(id: Long): Outcome<Unit> = notBuilt()

    override suspend fun reorderLocations(idsInOrder: List<Long>): Outcome<Unit> = notBuilt()

    override suspend fun addEntry(draft: InventoryEntryDraft): Outcome<InventoryEntry> = notBuilt()

    override suspend fun updateEntry(id: Long, update: InventoryEntryUpdate): Outcome<Unit> = notBuilt()

    override suspend fun markEmpty(id: Long): Outcome<Unit> = notBuilt()

    override suspend fun decrement(id: Long): Outcome<Unit> = notBuilt()

    override suspend fun deleteEntry(id: Long): Outcome<Unit> = notBuilt()

    override fun observeUnpackable(today: LocalDate): Flow<List<UnpackItem>> = flowOf(emptyList())

    override suspend fun unpack(choices: List<UnpackChoice>): Outcome<Int> = notBuilt()

    override suspend fun refresh(): Outcome<Unit> = notBuilt()
}

@Singleton
internal class NotBuiltStaplesRepository @Inject constructor() : StaplesRepository {
    override fun observeStaples(): Flow<List<Staple>> = flowOf(emptyList())

    override fun observeSuggestionsShown(): Flow<Boolean> = flowOf(false)

    override suspend fun markSuggestionsShown(): Outcome<Unit> = notBuilt()

    override suspend fun addStaples(names: List<String>): Outcome<Unit> = notBuilt()

    override suspend fun removeStaple(name: String): Outcome<Unit> = notBuilt()

    override suspend fun setOnHand(names: List<String>, onHand: Boolean): Outcome<Unit> = notBuilt()
}

@Singleton
internal class NotBuiltMakeNowRepository @Inject constructor() : MakeNowRepository {
    override fun observe(filter: MakeNowFilter, today: LocalDate): Flow<MakeNowState> = flowOf(MakeNowState())

    override suspend fun refresh(force: Boolean): Outcome<Unit> = notBuilt()

    override suspend fun missingFoods(recipeId: Long): Outcome<List<MissingFood>> = notBuilt()

    override suspend fun surprise(group: MakeNowGroup, filter: MakeNowFilter): Outcome<MakeNowRecipe?> = notBuilt()
}

@Singleton
internal class NotBuiltFoodAliasRepository @Inject constructor() : FoodAliasRepository {
    override fun observeAliases(): Flow<List<FoodAlias>> = flowOf(emptyList())

    override suspend fun find(text: String): FoodAlias? = null

    override suspend fun learn(text: String, foodName: String, kind: FoodAliasKind, foodId: Long?): Outcome<FoodAlias> = notBuilt()

    override suspend fun forget(id: Long): Outcome<Unit> = notBuilt()

    override suspend fun refresh(): Outcome<Unit> = notBuilt()
}

@Singleton
internal class NotBuiltBarcodeLookup @Inject constructor() : BarcodeLookup {
    override suspend fun lookup(ean: String): Outcome<BarcodeLookupResult> = notBuilt()
}

@Singleton
internal class NotBuiltDrinkRepository @Inject constructor() : DrinkRepository {
    override fun observeDrinks(type: String?): Flow<List<Drink>> = flowOf(emptyList())

    override fun observeDrinkTypes(): Flow<List<String>> = flowOf(emptyList())

    override fun observePairings(): Flow<List<Pairing>> = flowOf(emptyList())

    override fun observeRecipePairings(recipeId: Long): Flow<RecipePairings> = flowOf(RecipePairings(recipeId))

    override fun observeIsDrink(recipeId: Long): Flow<Boolean> = flowOf(false)

    override suspend fun addPairing(recipeId: Long, keywordId: Long): Outcome<Unit> = notBuilt()

    override suspend fun addNewPairing(recipeId: Long, name: String): Outcome<Pairing> = notBuilt()

    override suspend fun removePairing(recipeId: Long, keywordId: Long): Outcome<Unit> = notBuilt()

    override suspend fun createDrink(draft: DrinkDraft): Outcome<Drink> = notBuilt()

    override suspend fun refresh(): Outcome<Unit> = notBuilt()
}

@Singleton
internal class NotBuiltAiSettingsRepository @Inject constructor() : AiSettingsRepository {
    override val settings: Flow<AiSettings> = flowOf(AiSettings())

    override suspend fun setProvider(provider: AiProvider?): Outcome<Unit> = notBuilt()

    override suspend fun setModel(model: String?): Outcome<Unit> = notBuilt()

    override suspend fun setCustomBaseUrl(url: String?): Outcome<Unit> = notBuilt()

    override suspend fun storeKey(provider: AiProvider, key: String): Outcome<Unit> = notBuilt()

    override suspend fun removeKey(provider: AiProvider): Outcome<Unit> = notBuilt()

    override suspend fun acknowledgeWarning(): Outcome<Unit> = notBuilt()
}

/** Stores nothing: until the ai-foto lane's encrypted store exists, no key can be entered at all. */
@Singleton
internal class NotBuiltAiKeyStore @Inject constructor() : AiKeyStore {
    override val providersWithKey: Flow<Set<AiProvider>> = flowOf(emptySet())

    override suspend fun store(provider: AiProvider, key: String) = Unit

    override suspend fun remove(provider: AiProvider) = Unit

    override suspend fun <T> useKey(provider: AiProvider, block: suspend (key: String) -> T): T? = null

    override suspend fun clearAll() = Unit
}
