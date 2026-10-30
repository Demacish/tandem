package casa.moller.tandem.feature.pantry

import androidx.compose.runtime.Composable
import casa.moller.tandem.core.model.makenow.MakeNowFilter
import casa.moller.tandem.core.model.makenow.MakeNowGroup
import casa.moller.tandem.core.model.makenow.MakeNowRecipe
import casa.moller.tandem.core.model.mealplan.MealType
import casa.moller.tandem.core.model.pantry.ExpiryChoice
import casa.moller.tandem.core.model.pantry.InventoryEntry
import casa.moller.tandem.core.model.pantry.InventoryLocation
import casa.moller.tandem.core.model.pantry.PantryFood
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.testing.data.SamplePantry
import casa.moller.tandem.core.ui.pantry.ExpiryUrgency
import casa.moller.tandem.feature.pantry.entry.PantryEntryLayout
import casa.moller.tandem.feature.pantry.entry.PantryEntryScreen
import casa.moller.tandem.feature.pantry.entry.PantryEntryUiState
import casa.moller.tandem.feature.pantry.hemma.OnHandFood
import casa.moller.tandem.feature.pantry.hemma.PantryRow
import casa.moller.tandem.feature.pantry.hemma.PantryScreen
import casa.moller.tandem.feature.pantry.hemma.PantrySection
import casa.moller.tandem.feature.pantry.hemma.PantryUiState
import casa.moller.tandem.feature.pantry.makenow.MakeNowRow
import casa.moller.tandem.feature.pantry.makenow.MakeNowScreen
import casa.moller.tandem.feature.pantry.makenow.MakeNowUiState
import casa.moller.tandem.feature.pantry.staples.StapleRow
import casa.moller.tandem.feature.pantry.staples.StaplesLayout
import casa.moller.tandem.feature.pantry.staples.StaplesScreen
import casa.moller.tandem.feature.pantry.staples.StaplesUiState
import casa.moller.tandem.feature.pantry.unpack.UnpackRow
import casa.moller.tandem.feature.pantry.unpack.UnpackScreen
import casa.moller.tandem.feature.pantry.unpack.UnpackUiState
import kotlinx.datetime.LocalDate

/**
 * Every screen and state of "Hemma" with sample data, for the screenshot and accessibility tests
 * (one list, so both cover the same states).
 */
internal enum class PantryShot(val content: @Composable () -> Unit) {
    HEMMA({ HemmaShot(PantryStates.hemma()) }),
    HEMMA_EMPTY({ HemmaShot(PantryStates.hemma(entries = emptyList())) }),
    HEMMA_NO_LOCATIONS({ HemmaShot(PantryStates.hemma(locations = emptyList(), entries = emptyList())) }),
    HEMMA_GUEST({ HemmaShot(PantryStates.hemma(readOnly = true)) }),
    HEMMA_OFFLINE({ HemmaShot(PantryStates.hemma(syncNotice = PantrySyncNotice.OFFLINE)) }),
    HEMMA_FRIDGE_PHOTO({ HemmaShot(PantryStates.hemma(fridgePhoto = true, unpackCount = 0)) }),
    HEMMA_LOCATION({ HemmaShot(PantryStates.location()) }),
    HEMMA_MOVE({ HemmaShot(PantryStates.hemma().let { it.copy(moving = it.sections.first().rows[1]) }) }),
    ENTRY_NEW({ PantryEntryScreen(state = PantryStates.entryNew, onAction = {}) }),
    ENTRY_EDIT({ PantryEntryScreen(state = PantryStates.entryEdit, onAction = {}) }),
    UNPACK({ UnpackScreen(state = PantryStates.unpack, onAction = {}) }),
    UNPACK_EMPTY({ UnpackScreen(state = UnpackUiState(loading = false, today = PantrySamples.today), onAction = {}) }),
    STAPLES({ StaplesScreen(state = PantryStates.staples, onAction = {}) }),
    STAPLES_GUEST({ StaplesScreen(state = PantryStates.staples.copy(readOnly = true), onAction = {}) }),
    MAKE_NOW({ MakeNowShot(PantryStates.makeNow()) }),
    MAKE_NOW_MISSING({ MakeNowShot(PantryStates.makeNow(selected = MakeNowGroup.MISSING_2)) }),
    MAKE_NOW_FIRST_TIME({ MakeNowShot(PantryStates.makeNow(staplesPrompt = true)) }),
    MAKE_NOW_EMPTY_FILTERED({ MakeNowShot(PantryStates.makeNowEmptyFiltered) }),

    // Fas S review (fix-ui): every notice above the columns (offline, first time, Använd upp).
    MAKE_NOW_CROWDED({ MakeNowShot(PantryStates.makeNow(staplesPrompt = true).copy(stale = true)) }),

    // The keyboard is open in a low window (Robolectric has no keyboard, so the layout is told).
    ENTRY_EDIT_KEYBOARD({ PantryEntryLayout(state = PantryStates.entryEdit, onAction = {}, keyboardOpen = true) }),
    STAPLES_KEYBOARD({ StaplesLayout(state = PantryStates.staples, onAction = {}, keyboardOpen = true) }),
}

@Composable
private fun HemmaShot(state: PantryUiState) = PantryScreen(state = state, onAction = {}, onBack = {})

@Composable
private fun MakeNowShot(state: MakeNowUiState) = MakeNowScreen(state = state, onAction = {}, onBack = {})

/** The screens' states as the ViewModels build them from [PantrySamples]. */
internal object PantryStates {
    private val today = PantrySamples.today
    private val onHand = listOf(OnHandFood("honung", 301, staple = false), OnHandFood("salt", 201, staple = true))

    fun hemma(
        locations: List<InventoryLocation> = PantrySamples.locations,
        entries: List<InventoryEntry> = PantrySamples.entries,
        readOnly: Boolean = false,
        syncNotice: PantrySyncNotice? = null,
        fridgePhoto: Boolean = false,
        unpackCount: Int = 2,
    ): PantryUiState {
        val sections = locations.map { location ->
            PantrySection(
                location = location,
                rows = entries.filter { it.locationId == location.id }
                    .sortedWith(compareBy<InventoryEntry, LocalDate?>(nullsLast()) { it.expires }.thenBy { it.food.name })
                    .map { PantryRow(it, location.name, ExpiryUrgency.of(it.expires, today)) },
            )
        }
        return PantryUiState(
            loading = false,
            readOnly = readOnly,
            today = today,
            locations = locations,
            sections = sections,
            onHand = if (entries.isEmpty()) emptyList() else onHand,
            unpackCount = if (readOnly) 0 else unpackCount,
            pendingCount = entries.count { it.pending },
            syncNotice = syncNotice,
            fridgePhoto = fridgePhoto,
        )
    }

    fun location(): PantryUiState = hemma().let { state ->
        state.copy(location = PantrySamples.fridge, sections = state.sections.take(1), onHand = emptyList())
    }

    val entryNew = PantryEntryUiState(
        loading = false,
        isNew = true,
        today = today,
        food = "gräd",
        suggestions = listOf(Food(101, "grädde"), Food(120, "gräddfil"), Food(121, "matlagningsgrädde")),
        amount = "2",
        unit = "dl",
        units = listOf("dl", "st", "l", "paket"),
        locations = PantrySamples.locations,
        locationId = PantrySamples.fridge.id,
        expires = ExpiryChoice.PLUS_1_WEEK.dateFrom(today),
    )

    val entryEdit = PantryEntryUiState(
        loading = false,
        isNew = false,
        today = today,
        food = "blåbär",
        foodId = 103,
        amount = "1",
        unit = "påse",
        units = listOf("påse", "dl", "st"),
        locations = PantrySamples.locations,
        locationId = PantrySamples.freezer.id,
        expires = LocalDate(2027, 1, 15),
        expiryChoices = ExpiryChoice.forLocation(PantrySamples.freezer),
        note = "Plockade i augusti",
    )

    val unpack = UnpackUiState(
        loading = false,
        today = today,
        rows = listOf(
            UnpackRow(SamplePantry.unpack[0], PantrySamples.fridge.id, ExpiryChoice.PLUS_1_WEEK, include = true),
            UnpackRow(SamplePantry.unpack[1], PantrySamples.fridge.id, ExpiryChoice.PLUS_1_MONTH, include = true),
            UnpackRow(
                SamplePantry.unpack[0].copy(shoppingEntryId = 203, food = PantryFood(112, "tandkräm")),
                PantrySamples.cupboard.id,
                ExpiryChoice.NONE,
                include = false,
            ),
        ),
        locations = PantrySamples.locations,
    )

    val staples = StaplesUiState(
        loading = false,
        rows = listOf(
            StapleRow("salt", known = true, atHome = true),
            StapleRow("olja", known = true, atHome = false),
            StapleRow("svartpeppar", known = false, atHome = true),
            StapleRow("vatten", known = false, atHome = false),
            StapleRow("socker", known = false, atHome = false),
            StapleRow("vetemjöl", known = false, atHome = false),
        ),
    )

    fun makeNow(selected: MakeNowGroup = MakeNowGroup.HAS_ALL, staplesPrompt: Boolean = false): MakeNowUiState = MakeNowUiState(
        loading = false,
        today = today,
        groups = MakeNowGroup.entries.associateWith { group ->
            PantrySamples.makeNow[group].orEmpty()
                .filterNot { recipe -> recipe.recipe.keywords.any { it.name == "Dryck" } }
                .sortedBy { !it.expiring.isNotEmpty() }
                .map { it.toRow() }
        },
        selected = selected,
        mealTypes = listOf(MealType(1, "Middag"), MealType(2, "Lunch")),
        keywords = listOf(Keyword(7, "Vegetariskt"), Keyword(8, "Snabbt")),
        expiring = listOf(PantrySamples.leftovers, PantrySamples.cream),
        staplesPrompt = staplesPrompt,
    )

    val makeNowEmptyFiltered = MakeNowUiState(
        loading = false,
        today = today,
        groups = emptyMap(),
        filter = MakeNowFilter(maxMinutes = 30, mealTypeId = 2),
        mealTypes = listOf(MealType(1, "Middag"), MealType(2, "Lunch")),
    )

    private fun MakeNowRecipe.toRow() = MakeNowRow(recipe, group, missingFoods, expiring)
}
