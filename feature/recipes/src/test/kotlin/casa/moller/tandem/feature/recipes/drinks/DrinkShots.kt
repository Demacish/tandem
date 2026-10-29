package casa.moller.tandem.feature.recipes.drinks

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.model.drink.Drink
import casa.moller.tandem.core.model.drink.Pairing
import casa.moller.tandem.core.testing.data.SamplePantry
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.feature.recipes.DetailWithFakes

/** Sample drinks and pairings of a household (#43, plan §8.8); no images (tests never load any). */
internal object DrinkSamples {
    val rioja = SamplePantry.rioja.copy(note = "Fruktigt och kryddigt. Ca 120 kr.")
    val lager = SamplePantry.lager.copy(note = "Kall och frisk.")
    val juice = Drink(id = 63, name = "Äppelmust", types = listOf("Alkoholfritt"), rating = 5.0, note = "Från gårdsbutiken.")
    val pending = Drink(id = -1, name = "Husets rosé", types = listOf("Vin"), pending = true)
    val all = listOf(lager, rioja, pending, juice)
    val types = listOf("Alkoholfritt", "Cider", "Drink", "Vin", "Öl")
    val bold = Pairing(keywordId = 51, name = "Kraftigt rött", isTypeOnly = true)
    val crisp = Pairing(keywordId = 52, name = "Friskt vitt", isTypeOnly = true)
    val dishes = SampleRecipes.overviews.take(3)

    fun list(readOnly: Boolean = false, selectedType: String? = null, query: String = "") = DrinksUiState(
        loading = false,
        drinks = all.filter { selectedType == null || selectedType in it.types }
            .filter { query.isBlank() || it.name.contains(query, true) },
        totalCount = all.size,
        types = types,
        selectedType = selectedType,
        query = query,
        readOnly = readOnly,
        systembolagetShown = true,
    )

    fun dish(readOnly: Boolean = false) = RecipePairingsUiState(
        loaded = true,
        name = SampleRecipes.MushroomPasta.name,
        drinks = listOf(PairedDrink(rioja, listOf(70)), PairedDrink(pending, listOf(71))),
        kinds = listOf(bold),
        readOnly = readOnly,
    )

    fun drink(readOnly: Boolean = false) = RecipePairingsUiState(
        loaded = true,
        isDrink = true,
        name = rioja.name,
        dishes = dishes,
        readOnly = readOnly,
        systembolagetUrl = systembolagetUrl(rioja.name),
    )

    fun pickerForDish(query: String = "", readOnly: Boolean = false) = PairingPickerUiState(
        loaded = true,
        name = SampleRecipes.MushroomPasta.name,
        query = query,
        drinks = listOf(Choice(lager, false), Choice(rioja, true), Choice(juice, false))
            .filter { query.isBlank() || it.item.name.contains(query, true) },
        kinds = listOf(Choice(crisp, false), Choice(bold, true)).filter { query.isBlank() || it.item.name.contains(query, true) },
        hasDrinks = true,
        readOnly = readOnly,
        canCreateKind = query.isNotBlank() && !readOnly,
    )

    fun pickerForDrink() = PairingPickerUiState(
        loaded = true,
        isDrink = true,
        name = rioja.name,
        dishes = dishes.mapIndexed { index, dish -> Choice(dish, index == 0) },
    )

    fun create(filled: Boolean = false, nameMissing: Boolean = false) = CreateDrinkUiState(
        name = if (filled) "Husets rosé" else "",
        note = if (filled) "Ca 90 kr på vingården" else "",
        types = types,
        selectedTypes = if (filled) setOf("Vin") else emptySet(),
        pairWithName = SampleRecipes.MushroomPasta.name,
        nameMissing = nameMissing,
    )
}

/** Every drink screen and state, for the screenshot and accessibility tests (one list, so both cover the same). */
internal enum class DrinkShot(val content: @Composable () -> Unit) {
    LIST({ DrinkList(DrinkSamples.list()) }),
    LIST_FILTERED({ DrinkList(DrinkSamples.list(selectedType = "Vin")) }),
    LIST_GUEST({ DrinkList(DrinkSamples.list(readOnly = true)) }),
    LIST_EMPTY({ DrinkList(DrinksUiState(loading = false)) }),
    LIST_EMPTY_GUEST({ DrinkList(DrinksUiState(loading = false, readOnly = true)) }),
    LIST_NO_MATCH({ DrinkList(DrinkSamples.list(query = "champagne")) }),
    LIST_DETAIL({ ListDetail() }),
    DISH_PAIRINGS({ Section(DrinkSamples.dish()) }),
    DISH_PAIRINGS_EMPTY({ Section(RecipePairingsUiState(loaded = true, name = "Pannkakor")) }),
    DISH_PAIRINGS_GUEST({ Section(DrinkSamples.dish(readOnly = true)) }),
    DRINK_PAIRINGS({ Section(DrinkSamples.drink(), canAddToList = true) }),
    DETAIL_WITH_PAIRINGS({
        DetailWithFakes(recipeId = SampleRecipes.MushroomPasta.id, pairings = { SectionContent(DrinkSamples.dish()) })
    }),
    PICKER_DISH({ Picker(DrinkSamples.pickerForDish()) }),
    PICKER_NEW_KIND({ Picker(DrinkSamples.pickerForDish(query = "Bubbel")) }),
    PICKER_GUEST({ Picker(DrinkSamples.pickerForDish(readOnly = true)) }),
    PICKER_DRINK({ Picker(DrinkSamples.pickerForDrink()) }),
    CREATE_EMPTY({ Create(DrinkSamples.create()) }),
    CREATE_FILLED({ Create(DrinkSamples.create(filled = true).copy(photoUri = null)) }),
    CREATE_NAME_MISSING({ Create(DrinkSamples.create(nameMissing = true)) }),
    SUGGESTION({
        Surface { DrinkSuggestionRow(DrinkSuggestionState(listOf("Rioja Crianza", "Kraftigt rött"), drinkId = 61), onOpenDrink = {}) }
    }),
}

@Composable
private fun DrinkList(state: DrinksUiState) = DrinksScreen(state = state, onAction = {}, onOpenDrink = {}, onCreateDrink = {}, onBack = {})

@Composable
private fun Section(state: RecipePairingsUiState, canAddToList: Boolean = false) = Surface { SectionContent(state, canAddToList) }

@Composable
private fun SectionContent(state: RecipePairingsUiState, canAddToList: Boolean = false) = RecipePairingsSection(
    state = state,
    onAction = {},
    onOpenPicker = {},
    onOpenRecipe = {},
    onOpenUrl = {},
    canAddToList = canAddToList,
    modifier = Modifier.padding(16.dp),
)

@Composable
private fun Picker(state: PairingPickerUiState) = PairingPickerScreen(state = state, onAction = {}, onBack = {}, onCreateDrink = {})

@Composable
private fun Create(state: CreateDrinkUiState) =
    CreateDrinkScreen(state = state, onAction = {}, onTakePhoto = {}, onPickPhoto = {}, onBack = {})

/** "Drycker" beside an open drink, like the shell's list-detail scene on wide windows. */
@Composable
private fun ListDetail() {
    Row {
        DrinksScreen(
            state = DrinkSamples.list(),
            onAction = {},
            onOpenDrink = {},
            onCreateDrink = {},
            onBack = {},
            selectedDrinkId = DrinkSamples.rioja.id,
            modifier = Modifier.width(LIST_PANE_WIDTH),
        )
        VerticalDivider(Modifier.fillMaxHeight())
        Surface(modifier = Modifier.weight(1f)) { SectionContent(DrinkSamples.drink(), canAddToList = true) }
    }
}

private val LIST_PANE_WIDTH = 360.dp
