package casa.moller.tandem.feature.pantry

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.unit.Density
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.makenow.ExpiringFood
import casa.moller.tandem.core.model.makenow.MakeNowGroup
import casa.moller.tandem.core.model.makenow.MakeNowRecipe
import casa.moller.tandem.core.model.makenow.MissingFood
import casa.moller.tandem.core.model.pantry.InventoryEntry
import casa.moller.tandem.core.model.pantry.InventoryLocation
import casa.moller.tandem.core.model.pantry.PantryFood
import casa.moller.tandem.core.model.pantry.PantryUnit
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.testing.data.SamplePantry
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.core.ui.navigation.LocalAppChrome
import kotlinx.datetime.LocalDate

/** Device configurations of the screenshot and accessibility tests (plan 6.5). */
internal object Devices {
    const val PHONE = "w412dp-h915dp-port-xhdpi"
    const val PHONE_TALL = "w412dp-h1800dp-port-xhdpi"

    /** Tall enough that no row is cut at the bottom at font scale 2 (ATF would measure a sliver). */
    const val PHONE_XTALL = "w412dp-h3600dp-port-xhdpi"
    const val PHONE_LANDSCAPE = "w915dp-h412dp-land-xhdpi"
    const val TABLET_PORTRAIT = "w800dp-h1280dp-port-mdpi"
    const val TABLET = "w1280dp-h800dp-land-mdpi"
}

/** Theme, app chrome and font scale around a screen under test. */
@Composable
internal fun PantryTestTheme(
    darkTheme: Boolean = false,
    fontScale: Float = 1f,
    chrome: AppChrome = AppChrome(),
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale * density.fontScale)) {
        TandemTheme(darkTheme = darkTheme) {
            CompositionLocalProvider(LocalAppChrome provides chrome, content = content)
        }
    }
}

/** Runs the TalkBack action [label] of a node (the pantry rows' "Slut", "En mindre", "Flytta"). */
internal fun SemanticsNodeInteraction.performCustomAction(label: String) {
    val actions = fetchSemanticsNode().config[SemanticsActions.CustomActions]
    val action = actions.firstOrNull { it.label == label } ?: error("no action \"$label\" in ${actions.map { it.label }}")
    action.action()
}

/**
 * The pantry of the tests (on [SamplePantry.TODAY], 29 October 2026): cream expiring tomorrow,
 * yesterday's leftovers, milk next week, blueberries in the freezer, pasta without a date, a
 * pending entry made offline, and an empty place.
 */
internal object PantrySamples {
    val today: LocalDate = SamplePantry.TODAY
    val fridge = SamplePantry.fridge
    val freezer = SamplePantry.freezer
    val cupboard = SamplePantry.pantry
    val cellar = InventoryLocation(id = 4, name = "Jordkällaren")
    val locations = listOf(fridge, freezer, cupboard, cellar)

    val cream = SamplePantry.cream
    val leftovers = InventoryEntry(
        id = 15,
        locationId = 1,
        food = PantryFood(111, "köttfärssås"),
        unit = PantryUnit(6, "låda"),
        amount = 1.0,
        expires = LocalDate(2026, 10, 28),
        note = "Från söndagen",
    )
    val milk = SamplePantry.milk
    val blueberries = SamplePantry.blueberries
    val pasta = SamplePantry.pasta
    val yoghurt = InventoryEntry(
        id = -1,
        locationId = 1,
        food = PantryFood(null, "turkisk yoghurt"),
        unit = PantryUnit(null, "dl"),
        amount = 5.0,
        expires = LocalDate(2026, 10, 31),
        pending = true,
    )
    val entries = listOf(cream, leftovers, milk, blueberries, pasta, yoghurt)

    val drinkKeyword = Keyword(id = 90, name = "Dryck")
    val rioja = RecipeOverview(id = 61, name = "Rioja Crianza", keywords = listOf(drinkKeyword))
    val gratin = SamplePantry.gratin
    val pancakes = SamplePantry.pancakes
    val hummus = SamplePantry.hummus
    val creamSauce = RecipeOverview(id = 4, name = "Krämig kycklinggryta", workingMinutes = 35)
    val omelette = RecipeOverview(id = 5, name = "Omelett", workingMinutes = 10)

    val creamExpiring = ExpiringFood(101, "grädde", LocalDate(2026, 10, 30))

    val makeNow: Map<MakeNowGroup, List<MakeNowRecipe>> = mapOf(
        MakeNowGroup.HAS_ALL to listOf(
            MakeNowRecipe(rioja, MakeNowGroup.HAS_ALL, missingFoods = emptyList()),
            MakeNowRecipe(pancakes, MakeNowGroup.HAS_ALL, missingFoods = emptyList()),
            MakeNowRecipe(creamSauce, MakeNowGroup.HAS_ALL, missingFoods = emptyList(), expiring = listOf(creamExpiring)),
            MakeNowRecipe(omelette, MakeNowGroup.HAS_ALL, missingFoods = emptyList()),
        ),
        MakeNowGroup.MISSING_1 to listOf(
            MakeNowRecipe(
                gratin,
                MakeNowGroup.MISSING_1,
                missingFoods = listOf(MissingFood(107, "potatis")),
                expiring = listOf(creamExpiring),
            ),
        ),
        MakeNowGroup.MISSING_2 to listOf(
            MakeNowRecipe(hummus, MakeNowGroup.MISSING_2, missingFoods = listOf(MissingFood(108, "tahini"), MissingFood(109, "citron"))),
        ),
    )
}
