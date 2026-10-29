package casa.moller.tandem.core.testing.data

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.ai.AiSettingsRepository
import casa.moller.tandem.core.data.ai.FridgeVision
import casa.moller.tandem.core.data.drink.DrinkRepository
import casa.moller.tandem.core.data.pantry.MakeNowRepository
import casa.moller.tandem.core.data.pantry.PantryRepository
import casa.moller.tandem.core.data.pantry.StaplesRepository
import casa.moller.tandem.core.data.scan.BarcodeLookup
import casa.moller.tandem.core.data.scan.FoodAliasRepository
import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.core.model.ai.AiSettings
import casa.moller.tandem.core.model.ai.FridgeVisionError
import casa.moller.tandem.core.model.ai.FridgeVisionResult
import casa.moller.tandem.core.model.ai.RecognizedItem
import casa.moller.tandem.core.model.drink.Drink
import casa.moller.tandem.core.model.drink.DrinkDraft
import casa.moller.tandem.core.model.drink.DrinkKeywords
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
import casa.moller.tandem.core.model.pantry.PantryFood
import casa.moller.tandem.core.model.pantry.PantryGroup
import casa.moller.tandem.core.model.pantry.PantryUnit
import casa.moller.tandem.core.model.pantry.Staple
import casa.moller.tandem.core.model.pantry.UnpackChoice
import casa.moller.tandem.core.model.pantry.UnpackItem
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.model.scan.BarcodeLookupResult
import casa.moller.tandem.core.model.scan.BarcodeProduct
import casa.moller.tandem.core.model.scan.FoodAlias
import casa.moller.tandem.core.model.scan.FoodAliasKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate

// Fas S (grund lane): fakes of the "Skafferi & drycker" contracts in core:data (docs/BYGGE.md
// "Fas S – kontrakt"). In memory, deterministic and synchronous like the Phase 2 fakes: new objects
// get negative ids (-1, -2, ...), `readOnly = true` makes every write fail with
// DataProblem.READ_ONLY, `failure` makes the next writes fail with that error.

private inline fun <T> fasSWrite(readOnly: Boolean, failure: TandemError?, block: () -> T): Outcome<T> = when {
    readOnly -> Outcome.Failure(DataProblem.READ_ONLY.toError())
    failure != null -> Outcome.Failure(failure)
    else -> Outcome.Success(block())
}

/** Deterministic pantry data (Swedish names with å/ä/ö); the run day of the samples is [TODAY]. */
object SamplePantry {
    val TODAY: LocalDate = LocalDate(2026, 10, 29)

    val fridge = InventoryLocation(id = 1, name = "Kyl")
    val freezer = InventoryLocation(id = 2, name = "Frys", isFreezer = true)
    val pantry = InventoryLocation(id = 3, name = "Skafferi")
    val locations: List<InventoryLocation> = listOf(fridge, freezer, pantry)

    val cream = InventoryEntry(
        id = 11,
        locationId = 1,
        food = PantryFood(101, "grädde"),
        unit = PantryUnit(1, "dl"),
        amount = 3.0,
        expires = LocalDate(2026, 10, 30),
    )
    val milk = InventoryEntry(
        id = 12,
        locationId = 1,
        food = PantryFood(102, "mjölk"),
        unit = PantryUnit(2, "l"),
        amount = 1.0,
        expires = LocalDate(2026, 11, 4),
    )
    val blueberries = InventoryEntry(
        id = 13,
        locationId = 2,
        food = PantryFood(103, "blåbär"),
        unit = PantryUnit(3, "påse"),
        amount = 1.0,
        expires = LocalDate(2027, 1, 15),
    )
    val pasta = InventoryEntry(id = 14, locationId = 3, food = PantryFood(104, "pasta"), unit = PantryUnit(4, "paket"), amount = 2.0)
    val entries: List<InventoryEntry> = listOf(cream, milk, blueberries, pasta)

    val unpack: List<UnpackItem> = listOf(
        UnpackItem(shoppingEntryId = 201, food = PantryFood(105, "ägg"), amount = 12.0, unit = PantryUnit(5, "st")),
        UnpackItem(shoppingEntryId = 202, food = PantryFood(106, "smör"), amount = 1.0, unit = PantryUnit(4, "paket")),
    )

    val staples: List<Staple> = listOf(Staple("salt", 201, onHand = true), Staple("svartpeppar", 202, onHand = true), Staple("olja"))

    val gratin = RecipeOverview(id = 1, name = "Potatisgratäng", workingMinutes = 20, waitingMinutes = 60)
    val pancakes = RecipeOverview(id = 2, name = "Pannkakor", workingMinutes = 30)
    val hummus = RecipeOverview(id = 3, name = "Hummus", workingMinutes = 10)

    val makeNow: Map<MakeNowGroup, List<MakeNowRecipe>> = mapOf(
        MakeNowGroup.HAS_ALL to listOf(MakeNowRecipe(pancakes, MakeNowGroup.HAS_ALL, missingFoods = emptyList())),
        MakeNowGroup.MISSING_1 to listOf(MakeNowRecipe(gratin, MakeNowGroup.MISSING_1, missingFoods = listOf(MissingFood(107, "potatis")))),
        MakeNowGroup.MISSING_2 to listOf(
            MakeNowRecipe(hummus, MakeNowGroup.MISSING_2, missingFoods = listOf(MissingFood(108, "tahini"), MissingFood(109, "citron"))),
        ),
    )

    val aliases: List<FoodAlias> = listOf(
        FoodAlias(id = 31, alias = "2000000000015", foodName = "kikärtor", kind = FoodAliasKind.BARCODE, foodId = 110),
        FoodAlias(id = 32, alias = "GRÄDDE 36% 5DL", foodName = "grädde", kind = FoodAliasKind.RECEIPT, foodId = 101),
    )

    val pairing = Pairing(keywordId = 51, name = "Kraftigt rött")
    val typeOnly = Pairing(keywordId = 52, name = "Friskt vitt", isTypeOnly = true)
    val rioja = Drink(id = 61, name = "Rioja Crianza", types = listOf("Vin"), rating = 4.0)
    val lager = Drink(id = 62, name = "Ljus lager", types = listOf("Öl"))
    val drinks: List<Drink> = listOf(lager, rioja)
}

/** In-memory [PantryRepository] over [SamplePantry]. */
class FakePantryRepository(
    locations: List<InventoryLocation> = SamplePantry.locations,
    entries: List<InventoryEntry> = SamplePantry.entries,
    unpackable: List<UnpackItem> = SamplePantry.unpack,
    var readOnly: Boolean = false,
) : PantryRepository {
    val locations = MutableStateFlow(locations)
    val entries = MutableStateFlow(entries)
    val unpackable = MutableStateFlow(unpackable)

    /** Food ids on hand (`food_onhand`) besides the stocked ones. */
    val onHandFoodIds = MutableStateFlow<Set<Long>>(emptySet())
    var failure: TandemError? = null
    var refreshes: Int = 0
    private var nextId = -1L

    override fun observeLocations(): Flow<List<InventoryLocation>> = locations

    override fun observePantry(): Flow<List<PantryGroup>> = combine(locations, entries) { places, all ->
        places.map { place -> PantryGroup(place, sortForPantry(all.filter { it.locationId == place.id && !it.isEmpty })) }
    }

    override fun observeEntry(id: Long): Flow<InventoryEntry?> = entries.map { list -> list.firstOrNull { it.id == id } }

    override fun observeExpiring(today: LocalDate, days: Int): Flow<List<InventoryEntry>> = entries.map { list ->
        sortForPantry(list.filter { !it.isEmpty && it.expiresSoon(today, days) })
    }

    override fun observeAvailableFoodIds(): Flow<Set<Long>> = combine(entries, onHandFoodIds) { list, onHand ->
        list.filter { !it.isEmpty }.mapNotNull { it.food.id }.toSet() + onHand
    }

    override suspend fun createLocation(name: String, isFreezer: Boolean): Outcome<InventoryLocation> = fasSWrite(readOnly, failure) {
        InventoryLocation(id = nextId--, name = name, isFreezer = isFreezer, pending = true).also { created ->
            locations.update { it + created }
        }
    }

    override suspend fun renameLocation(id: Long, name: String, isFreezer: Boolean): Outcome<Unit> = fasSWrite(readOnly, failure) {
        locations.update { list -> list.map { if (it.id == id) it.copy(name = name, isFreezer = isFreezer) else it } }
    }

    override suspend fun deleteLocation(id: Long): Outcome<Unit> = fasSWrite(readOnly, failure) {
        locations.update { list -> list.filterNot { it.id == id } }
        entries.update { list -> list.filterNot { it.locationId == id } }
    }

    override suspend fun reorderLocations(idsInOrder: List<Long>): Outcome<Unit> = fasSWrite(readOnly, failure) {
        locations.update { list -> list.sortedBy { idsInOrder.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE } }
    }

    override suspend fun addEntry(draft: InventoryEntryDraft): Outcome<InventoryEntry> = fasSWrite(readOnly, failure) {
        val entry = InventoryEntry(
            id = nextId--,
            locationId = draft.locationId,
            food = PantryFood(draft.foodId, draft.foodName),
            unit = PantryUnit(null, draft.unitName ?: DEFAULT_UNIT),
            amount = draft.amount,
            expires = draft.expires,
            note = draft.note,
            subLocation = draft.subLocation,
            pending = true,
        )
        entries.update { it + entry }
        entry
    }

    override suspend fun updateEntry(id: Long, update: InventoryEntryUpdate): Outcome<Unit> = fasSWrite(readOnly, failure) {
        entries.update { list ->
            list.map { entry ->
                if (entry.id != id) {
                    entry
                } else {
                    entry.copy(
                        amount = update.amount ?: entry.amount,
                        locationId = update.locationId ?: entry.locationId,
                        expires = if (update.clearExpires) null else update.expires ?: entry.expires,
                        note = update.note ?: entry.note,
                        subLocation = update.subLocation ?: entry.subLocation,
                    )
                }
            }
        }
    }

    override suspend fun markEmpty(id: Long): Outcome<Unit> = updateEntry(id, InventoryEntryUpdate(amount = 0.0))

    override suspend fun decrement(id: Long): Outcome<Unit> {
        val current = entries.value.firstOrNull { it.id == id } ?: return Outcome.Success(Unit)
        return updateEntry(id, InventoryEntryUpdate(amount = (current.amount - 1).coerceAtLeast(0.0)))
    }

    override suspend fun deleteEntry(id: Long): Outcome<Unit> = fasSWrite(readOnly, failure) {
        entries.update { list -> list.filterNot { it.id == id } }
    }

    override fun observeUnpackable(today: LocalDate): Flow<List<UnpackItem>> = unpackable

    override suspend fun unpack(choices: List<UnpackChoice>): Outcome<Int> = fasSWrite(readOnly, failure) {
        val chosen = choices.filter { it.include }
        chosen.forEach { choice ->
            val entry = InventoryEntry(
                id = nextId--,
                locationId = choice.locationId,
                food = choice.item.food,
                unit = choice.item.unit,
                amount = choice.item.amount,
                expires = choice.expires,
                pending = true,
            )
            entries.update { it + entry }
        }
        val done = choices.map { it.item.shoppingEntryId }.toSet()
        unpackable.update { list -> list.filterNot { it.shoppingEntryId in done } }
        chosen.size
    }

    override suspend fun refresh(): Outcome<Unit> {
        refreshes += 1
        return failure?.let { Outcome.Failure(it) } ?: Outcome.Success(Unit)
    }

    private fun sortForPantry(list: List<InventoryEntry>): List<InventoryEntry> =
        list.sortedWith(compareBy<InventoryEntry, LocalDate?>(nullsLast()) { it.expires }.thenBy { it.food.name })

    companion object {
        const val DEFAULT_UNIT: String = "st"
    }
}

/** In-memory [StaplesRepository]. */
class FakeStaplesRepository(staples: List<Staple> = SamplePantry.staples, var readOnly: Boolean = false) : StaplesRepository {
    val staples = MutableStateFlow(staples)
    val suggestionsShown = MutableStateFlow(false)
    var failure: TandemError? = null

    override fun observeStaples(): Flow<List<Staple>> = staples.map { list -> list.sortedBy { it.name } }

    override fun observeSuggestionsShown(): Flow<Boolean> = suggestionsShown

    override suspend fun markSuggestionsShown(): Outcome<Unit> {
        suggestionsShown.value = true
        return Outcome.Success(Unit)
    }

    override suspend fun addStaples(names: List<String>): Outcome<Unit> = fasSWrite(readOnly, failure) {
        staples.update { list ->
            val known = list.map { it.name.lowercase() }.toSet()
            list + names.filter { it.lowercase() !in known }.map { Staple(it, onHand = true) }
        }
    }

    override suspend fun removeStaple(name: String): Outcome<Unit> = fasSWrite(readOnly, failure) {
        staples.update { list -> list.filterNot { it.name.equals(name, ignoreCase = true) } }
    }

    override suspend fun setOnHand(names: List<String>, onHand: Boolean): Outcome<Unit> = fasSWrite(readOnly, failure) {
        val keys = names.map { it.lowercase() }.toSet()
        staples.update { list -> list.map { if (it.name.lowercase() in keys) it.copy(onHand = onHand) else it } }
    }
}

/** [MakeNowRepository] over fixed groups ([SamplePantry.makeNow]); [state] can be set by a test. */
class FakeMakeNowRepository(groups: Map<MakeNowGroup, List<MakeNowRecipe>> = SamplePantry.makeNow) : MakeNowRepository {
    val state = MutableStateFlow(MakeNowState(groups))
    var failure: TandemError? = null
    var refreshes: Int = 0
    val filters = mutableListOf<MakeNowFilter>()

    override fun observe(filter: MakeNowFilter, today: LocalDate): Flow<MakeNowState> {
        filters += filter
        return state.map { current ->
            val maxMinutes = filter.maxMinutes ?: return@map current
            current.copy(groups = current.groups.mapValues { (_, list) -> list.filter { it.recipe.totalMinutes <= maxMinutes } })
        }
    }

    override suspend fun refresh(force: Boolean): Outcome<Unit> {
        refreshes += 1
        return failure?.let { Outcome.Failure(it) } ?: Outcome.Success(Unit)
    }

    override suspend fun missingFoods(recipeId: Long): Outcome<List<MissingFood>> {
        failure?.let { return Outcome.Failure(it) }
        val recipe = state.value.groups.values.flatten().firstOrNull { it.recipe.id == recipeId }
        return Outcome.Success(recipe?.missingFoods.orEmpty())
    }

    override suspend fun surprise(group: MakeNowGroup, filter: MakeNowFilter): Outcome<MakeNowRecipe?> {
        failure?.let { return Outcome.Failure(it) }
        // Deterministic "random": the first of the group.
        return Outcome.Success(state.value.recipes(group).firstOrNull())
    }
}

/** In-memory [FoodAliasRepository] (matching without case, like Tandoor). */
class FakeFoodAliasRepository(aliases: List<FoodAlias> = SamplePantry.aliases, var readOnly: Boolean = false) : FoodAliasRepository {
    val aliases = MutableStateFlow(aliases)
    var failure: TandemError? = null
    private var nextId = -1L

    override fun observeAliases(): Flow<List<FoodAlias>> = aliases

    override suspend fun find(text: String): FoodAlias? =
        aliases.value.firstOrNull { it.alias.trim().equals(text.trim(), ignoreCase = true) }

    override suspend fun learn(text: String, foodName: String, kind: FoodAliasKind, foodId: Long?): Outcome<FoodAlias> =
        fasSWrite(readOnly, failure) {
            val alias = FoodAlias(nextId--, text.trim().take(FoodAlias.MAX_LENGTH), foodName, kind, foodId, pending = true)
            aliases.update { list -> list.filterNot { it.alias.equals(alias.alias, ignoreCase = true) } + alias }
            alias
        }

    override suspend fun forget(id: Long): Outcome<Unit> = fasSWrite(readOnly, failure) {
        aliases.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun refresh(): Outcome<Unit> = failure?.let { Outcome.Failure(it) } ?: Outcome.Success(Unit)
}

/** [BarcodeLookup] over a fixed table; every looked-up code is recorded in [lookups]. */
class FakeBarcodeLookup(
    val products: MutableMap<String, BarcodeProduct> = mutableMapOf(
        "2000000000022" to BarcodeProduct("2000000000022", "Vispgrädde 36%", brand = "Exempelmejeriet", quantity = "5 dl"),
    ),
) : BarcodeLookup {
    val lookups = mutableListOf<String>()
    var failure: TandemError? = null

    override suspend fun lookup(ean: String): Outcome<BarcodeLookupResult> {
        lookups += ean
        failure?.let { return Outcome.Failure(it) }
        if (ean.isEmpty() || !ean.all { it.isDigit() }) return Outcome.Failure(TandemError.Unknown("invalid-ean"))
        return Outcome.Success(products[ean]?.let { BarcodeLookupResult.Found(it) } ?: BarcodeLookupResult.NotFound)
    }
}

/** In-memory [DrinkRepository] over [SamplePantry]'s drinks; [recipePairings] maps recipe id -> pairing keyword ids. */
class FakeDrinkRepository(
    drinks: List<Drink> = SamplePantry.drinks,
    pairings: List<Pairing> = listOf(SamplePantry.pairing, SamplePantry.typeOnly),
    var readOnly: Boolean = false,
) : DrinkRepository {
    val drinks = MutableStateFlow(drinks)
    val pairings = MutableStateFlow(pairings)
    val recipePairings = MutableStateFlow<Map<Long, Set<Long>>>(mapOf(SamplePantry.rioja.id to setOf(SamplePantry.pairing.keywordId)))
    val created = mutableListOf<DrinkDraft>()
    var failure: TandemError? = null
    private var nextId = -1L

    override fun observeDrinks(type: String?): Flow<List<Drink>> = this.drinks.map { list ->
        list.filter { type == null || type in it.types }.sortedBy { it.name }
    }

    override fun observeDrinkTypes(): Flow<List<String>> = this.drinks.map { list ->
        (list.flatMap { it.types } + DrinkKeywords.TYPES).distinct().sorted()
    }

    override fun observePairings(): Flow<List<Pairing>> = pairings.map { list -> list.sortedBy { it.name } }

    override fun observeRecipePairings(recipeId: Long): Flow<RecipePairings> =
        combine(recipePairings, pairings, drinks) { links, all, drinkList ->
            val own = links[recipeId].orEmpty()
            val isDrink = drinkList.any { it.id == recipeId }
            val linked = links.filter { (id, keywords) -> id != recipeId && keywords.any { it in own } }.keys
            RecipePairings(
                recipeId = recipeId,
                pairings = all.filter { it.keywordId in own },
                drinks = if (isDrink) emptyList() else drinkList.filter { it.id in linked },
                dishIds = if (isDrink) linked.filter { id -> drinkList.none { it.id == id } }.sorted() else emptyList(),
            )
        }

    override fun observeIsDrink(recipeId: Long): Flow<Boolean> = drinks.map { list -> list.any { it.id == recipeId } }

    override suspend fun addPairing(recipeId: Long, keywordId: Long): Outcome<Unit> = fasSWrite(readOnly, failure) {
        recipePairings.update { links -> links + (recipeId to (links[recipeId].orEmpty() + keywordId)) }
    }

    override suspend fun addNewPairing(recipeId: Long, name: String): Outcome<Pairing> = fasSWrite(readOnly, failure) {
        val pairing = pairings.value.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Pairing(nextId--, name).also { created ->
            pairings.update { it + created }
        }
        recipePairings.update { links -> links + (recipeId to (links[recipeId].orEmpty() + pairing.keywordId)) }
        pairing
    }

    override suspend fun removePairing(recipeId: Long, keywordId: Long): Outcome<Unit> = fasSWrite(readOnly, failure) {
        recipePairings.update { links -> links + (recipeId to (links[recipeId].orEmpty() - keywordId)) }
    }

    override suspend fun createDrink(draft: DrinkDraft): Outcome<Drink> = fasSWrite(readOnly, failure) {
        created += draft
        val drink = Drink(id = nextId--, name = draft.name, types = draft.types, pending = true)
        drinks.update { it + drink }
        recipePairings.update { links -> links + (drink.id to draft.pairingKeywordIds.toSet()) }
        drink
    }

    override suspend fun refresh(): Outcome<Unit> = failure?.let { Outcome.Failure(it) } ?: Outcome.Success(Unit)
}

/**
 * In-memory [AiSettingsRepository]. The stored keys are kept in [keys] for assertions only (a test
 * double; the real store encrypts them and never returns them).
 */
class FakeAiSettingsRepository(initial: AiSettings = AiSettings()) : AiSettingsRepository {
    private val state = MutableStateFlow(initial)
    val keys = mutableMapOf<AiProvider, String>()
    var failure: TandemError? = null

    override val settings: Flow<AiSettings> = state

    override suspend fun setProvider(provider: AiProvider?): Outcome<Unit> = change {
        it.copy(provider = provider, model = null, hasKey = provider != null && provider in keys)
    }

    override suspend fun setModel(model: String?): Outcome<Unit> = change { it.copy(model = model?.takeIf { value -> value.isNotBlank() }) }

    override suspend fun setCustomBaseUrl(url: String?): Outcome<Unit> {
        if (url != null && !url.startsWith("https://")) return Outcome.Failure(TandemError.Unknown("ai-base-url-not-https"))
        return change { it.copy(customBaseUrl = url) }
    }

    override suspend fun storeKey(provider: AiProvider, key: String): Outcome<Unit> {
        if (key.isBlank()) return Outcome.Failure(TandemError.Unknown("ai-key-empty"))
        keys[provider] = key.trim()
        return change { it.copy(hasKey = it.provider == provider || it.hasKey) }
    }

    override suspend fun removeKey(provider: AiProvider): Outcome<Unit> {
        keys.remove(provider)
        return change { it.copy(hasKey = it.provider != null && it.provider in keys) }
    }

    override suspend fun acknowledgeWarning(): Outcome<Unit> = change { it.copy(warningAcknowledged = true) }

    private inline fun change(block: (AiSettings) -> AiSettings): Outcome<Unit> {
        failure?.let { return Outcome.Failure(it) }
        state.update(block)
        return Outcome.Success(Unit)
    }
}

/** [FridgeVision] with a fixed answer; the photo sizes it was called with are in [calls]. */
class FakeFridgeVision(
    var result: FridgeVisionResult = FridgeVisionResult.Items(
        listOf(
            RecognizedItem("mjölk", amount = 1.0, unit = "l", confidence = 0.9),
            RecognizedItem("ägg", amount = 6.0, unit = "st", confidence = 0.8),
            RecognizedItem("gurka", confidence = 0.4, note = "delvis skymd"),
        ),
    ),
) : FridgeVision {
    val calls = mutableListOf<Int>()

    override suspend fun recognize(jpeg: ByteArray, languageTag: String): FridgeVisionResult {
        calls += jpeg.size
        if (jpeg.isEmpty()) return FridgeVisionResult.Failed(FridgeVisionError.Unparseable("empty photo"))
        return result
    }
}
