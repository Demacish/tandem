package casa.moller.tandem.feature.recipes.drinks

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.model.drink.Drink
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeDrinkRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSettingsRepository
import casa.moller.tandem.core.testing.data.FakeShoppingRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.SamplePantry
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.feature.recipes.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The drink ViewModels on core:testing's fakes (#43, plan §8.8): the list's type filter and search,
 * the Systembolaget switch, "Passar med" / "Passar till" from both sides, the picker, "Ny dryck"
 * (paired with the dish it came from) and a guest who changes nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DrinkViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val drinks = FakeDrinkRepository()
    private val recipes = FakeRecipeRepository()
    private val permissions = FakeSpacePermissionsRepository()
    private val settings = FakeSettingsRepository()
    private val shopping = FakeShoppingRepository()
    private val dish = SampleRecipes.MushroomPasta.id
    private val rioja = SamplePantry.rioja

    private fun TestScope.collect(flow: Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    private fun <T> TestScope.effectsOf(flow: Flow<T>): List<T> {
        val effects = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(effects) }
        return effects
    }

    // ------------------------------------------------------------------ "Drycker"

    @Test
    fun theListFiltersByTypeAndSearchesNameAndNote() = runTest {
        drinks.drinks.value = SamplePantry.drinks + Drink(id = 63, name = "Äppelmust", types = listOf("Alkoholfritt"), note = "Från gården")
        val vm = DrinksViewModel(null, drinks, permissions, settings)
        collect(vm.uiState)
        assertEquals(listOf("Ljus lager", "Rioja Crianza", "Äppelmust"), vm.uiState.value.drinks.map { it.name })
        assertTrue("Vin" in vm.uiState.value.types)

        vm.onAction(DrinksAction.SelectType("Vin"))
        assertEquals(listOf("Rioja Crianza"), vm.uiState.value.drinks.map { it.name })
        vm.onAction(DrinksAction.SelectType(null))
        vm.onAction(DrinksAction.Search("gården"))
        assertEquals("the note is searched, without diacritics too", listOf("Äppelmust"), vm.uiState.value.drinks.map { it.name })
        vm.onAction(DrinksAction.Search("champagne"))
        assertTrue(vm.uiState.value.drinks.isEmpty())
        assertEquals(3, vm.uiState.value.totalCount)
    }

    @Test
    fun aTypeFromTheKeyIsPreselectedAndTheSystembolagetSwitchIsStored() = runTest {
        val vm = DrinksViewModel("Öl", drinks, permissions, settings)
        collect(vm.uiState)
        assertEquals(listOf("Ljus lager"), vm.uiState.value.drinks.map { it.name })
        vm.onAction(DrinksAction.SetSystembolaget(false))
        assertEquals(false, settings.state.value.systembolagetLink)
        assertFalse(vm.uiState.value.systembolagetShown)
        vm.onAction(DrinksAction.SetSystembolaget(true))
        assertTrue(vm.uiState.value.systembolagetShown)
    }

    @Test
    fun aPendingDrinkSaysItOpensOnceSent() = runTest {
        val vm = DrinksViewModel(null, drinks, permissions, settings)
        val effects = effectsOf(vm.effects)
        vm.onAction(DrinksAction.OpenPending)
        assertEquals(listOf(DrinksEffect.Message(R.string.drinks_pending_message)), effects)
    }

    // ------------------------------------------------------------------ "Passar med" / "Passar till"

    @Test
    fun aDishShowsItsDrinkAndKindAndRemovesThem() = runTest {
        val links = setOf(SamplePantry.pairing.keywordId, SamplePantry.typeOnly.keywordId)
        drinks.recipePairings.value = drinks.recipePairings.value + (dish to links)
        val vm = RecipePairingsViewModel(dish, drinks, recipes, permissions, settings, shopping)
        collect(vm.uiState)
        val state = vm.uiState.value
        assertFalse(state.isDrink)
        assertEquals(listOf(rioja.id), state.drinks.map { it.drink.id })
        assertEquals(listOf(SamplePantry.pairing.keywordId), state.drinks.single().keywordIds)
        assertEquals(listOf("Friskt vitt"), state.kinds.map { it.name })

        vm.onAction(RecipePairingsAction.RemoveDrink(state.drinks.single()))
        vm.onAction(RecipePairingsAction.RemoveKind(state.kinds.single()))
        assertTrue(vm.uiState.value.isEmpty)
    }

    @Test
    fun aDrinkShowsItsDishesSystembolagetAndGoesOnTheList() = runTest {
        recipes.overviews.value = recipes.overviews.value + SampleRecipes.overviews.first().copy(id = rioja.id, name = rioja.name)
        drinks.recipePairings.value = drinks.recipePairings.value + (dish to setOf(SamplePantry.pairing.keywordId))
        settings.state.value = AppSettings(systembolagetLink = true)
        val vm = RecipePairingsViewModel(rioja.id, drinks, recipes, permissions, settings, shopping)
        collect(vm.uiState)
        val effects = effectsOf(vm.effects)
        val state = vm.uiState.value
        assertTrue(state.isDrink)
        assertEquals(listOf(dish), state.dishes.map { it.id })
        assertEquals("https://www.systembolaget.se/sok/?textQuery=Rioja+Crianza", state.systembolagetUrl)

        vm.onAction(RecipePairingsAction.AddToList)
        assertTrue(shopping.entries.value.any { it.food.name == "Rioja Crianza" })
        assertEquals(RecipePairingsEffect.Message(R.string.drinks_added_to_list, "Rioja Crianza"), effects.single())

        vm.onAction(RecipePairingsAction.RemoveDish(dish))
        assertTrue(vm.uiState.value.dishes.isEmpty())
    }

    @Test
    fun aGuestSeesPairingsButAChangeIsRefused() = runTest {
        drinks.readOnly = true
        permissions.setGuest(true)
        drinks.recipePairings.value = drinks.recipePairings.value + (dish to setOf(SamplePantry.typeOnly.keywordId))
        val vm = RecipePairingsViewModel(dish, drinks, recipes, permissions, settings, shopping)
        collect(vm.uiState)
        val effects = effectsOf(vm.effects)
        assertTrue(vm.uiState.value.readOnly)
        vm.onAction(RecipePairingsAction.RemoveKind(vm.uiState.value.kinds.single()))
        assertEquals(listOf(RecipePairingsEffect.Message(R.string.drinks_read_only)), effects)
        assertEquals(1, vm.uiState.value.kinds.size)
        assertEquals(DataProblem.READ_ONLY.toError(), (drinks.addPairing(dish, 1) as Outcome.Failure).error)
    }

    // ------------------------------------------------------------------ the picker

    @Test
    fun thePickerPairsADrinkAndCreatesAKind() = runTest {
        val vm = PairingPickerViewModel(dish, drinks, recipes, permissions)
        collect(vm.uiState)
        assertFalse(vm.uiState.value.isDrink)
        assertEquals(setOf("Ljus lager", "Rioja Crianza"), vm.uiState.value.drinks.map { it.item.name }.toSet())
        assertTrue(vm.uiState.value.drinks.none { it.selected })

        vm.onAction(PairingPickerAction.ToggleDrink(rioja, selected = true))
        assertTrue(vm.uiState.value.drinks.single { it.item.id == rioja.id }.selected)

        vm.onAction(PairingPickerAction.Search("kraftigt RÖTT"))
        assertFalse("an existing pairing is not created again", vm.uiState.value.canCreateKind)
        vm.onAction(PairingPickerAction.Search("Bubbel"))
        assertTrue(vm.uiState.value.canCreateKind)
        vm.onAction(PairingPickerAction.CreateKind("Bubbel"))
        assertEquals("", vm.uiState.value.query)
        assertTrue(drinks.pairings.value.any { it.name == "Bubbel" })

        vm.onAction(PairingPickerAction.ToggleDrink(rioja, selected = false))
        assertFalse(vm.uiState.value.drinks.single { it.item.id == rioja.id }.selected)
    }

    @Test
    fun forADrinkThePickerListsDishesOnly() = runTest {
        val vm = PairingPickerViewModel(rioja.id, drinks, recipes, permissions)
        collect(vm.uiState)
        val state = vm.uiState.value
        assertTrue(state.isDrink)
        assertTrue(state.dishes.isNotEmpty())
        assertTrue(state.dishes.none { it.item.id == rioja.id })
        vm.onAction(PairingPickerAction.ToggleDish(state.dishes.first { it.item.id == dish }.item, selected = true))
        assertTrue(vm.uiState.value.dishes.first { it.item.id == dish }.selected)
    }

    // ------------------------------------------------------------------ "Ny dryck"

    @Test
    fun aNameIsRequiredAndANewDrinkIsPairedWithItsDish() = runTest {
        val vm = CreateDrinkViewModel(dish, SavedStateHandle(), drinks, recipes, permissions)
        collect(vm.uiState)
        val effects = effectsOf(vm.effects)
        assertEquals(SampleRecipes.MushroomPasta.name, vm.uiState.value.pairWithName)

        vm.onAction(CreateDrinkAction.Save)
        assertTrue(vm.uiState.value.nameMissing)
        assertTrue(drinks.created.isEmpty())

        vm.onAction(CreateDrinkAction.SetName("Husets rosé"))
        vm.onAction(CreateDrinkAction.ToggleType("Vin"))
        vm.onAction(CreateDrinkAction.SetNote("Ca 90 kr"))
        vm.onAction(CreateDrinkAction.SetPhoto("content://media/picker/1"))
        assertFalse(vm.uiState.value.nameMissing)
        vm.onAction(CreateDrinkAction.Save)

        val draft = drinks.created.single()
        assertEquals("Husets rosé", draft.name)
        assertEquals(listOf("Vin"), draft.types)
        assertEquals("Ca 90 kr", draft.description)
        assertEquals("content://media/picker/1", draft.imageUri)
        assertEquals(listOf(CreateDrinkEffect.Saved(R.string.drinks_create_saved_offline, "Husets rosé")), effects)
        val created = drinks.drinks.value.single { it.name == "Husets rosé" }
        assertTrue(created.id in drinks.recipePairings.value.filterValues { it.isNotEmpty() }.keys)
        assertTrue(drinks.recipePairings.value[dish].orEmpty().isNotEmpty())
    }

    @Test
    fun theFormSurvivesProcessDeath() = runTest {
        val handle = SavedStateHandle()
        val first = CreateDrinkViewModel(null, handle, drinks, recipes, permissions)
        first.onAction(CreateDrinkAction.SetName("Cava"))
        first.onAction(CreateDrinkAction.ToggleType("Vin"))
        val second = CreateDrinkViewModel(null, handle, drinks, recipes, permissions)
        collect(second.uiState)
        assertEquals("Cava", second.uiState.value.name)
        assertEquals(setOf("Vin"), second.uiState.value.selectedTypes)
        assertNull(second.uiState.value.pairWithName)
    }

    @Test
    fun systembolagetGetsOnlyTheNameAndTypesShowInTheUiLanguage() {
        val url = systembolagetUrl(" Äppelmust från gården ")
        assertEquals("https://www.systembolaget.se/sok/?textQuery=%C3%84ppelmust+fr%C3%A5n+g%C3%A5rden", url)
        assertEquals(R.string.drinks_type_wine, drinkTypeRes("vin"))
        assertEquals(R.string.drinks_type_cocktail, drinkTypeRes("Drink"))
        assertNull(drinkTypeRes("Kraftigt rött"))
    }
}
