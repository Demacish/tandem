package casa.moller.tandem.feature.shopping

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.shopping.Aisle
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingFood
import casa.moller.tandem.core.model.shopping.ShoppingHistoryItem
import casa.moller.tandem.core.model.shopping.ShoppingList
import casa.moller.tandem.core.model.shopping.ShoppingRecipeLabel
import casa.moller.tandem.core.model.shopping.ShoppingUnit
import casa.moller.tandem.core.model.shopping.Supermarket
import casa.moller.tandem.core.model.shopping.SupermarketCategory
import casa.moller.tandem.core.testing.data.SampleShopping
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.core.ui.navigation.LocalAppChrome
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** Device configurations of the screenshot and accessibility tests (plan 6.5). */
internal object Devices {
    const val PHONE = "w412dp-h915dp-port-xhdpi"
    const val PHONE_TALL = "w412dp-h1800dp-port-xhdpi"
    const val PHONE_LANDSCAPE = "w915dp-h412dp-land-xhdpi"
    const val TABLET_PORTRAIT = "w800dp-h1280dp-port-mdpi"
    const val TABLET = "w1280dp-h800dp-land-mdpi"
}

/**
 * The Handla tab's test data: a store whose walking order is not alphabetical (Frukt & grönt,
 * Skafferi, Mejeri), entries in every aisle, one without an aisle ("Övrigt"), one from a recipe,
 * one delayed ("köp senare"), one checked and one created offline (pending).
 */
internal object ShoppingSamples {
    val now = SampleShopping.now

    val fruit = SupermarketCategory(1, "Frukt & grönt")
    val dairy = SupermarketCategory(2, "Mejeri")
    val pantry = SupermarketCategory(3, "Skafferi")
    val hygiene = SupermarketCategory(4, "Hygien")
    val categories = listOf(dairy, fruit, hygiene, pantry)

    /** Walking order: fruit, pantry, dairy (hygiene is not in this store). */
    val store = Supermarket(
        id = 1,
        name = "Testbutiken",
        aisles = listOf(Aisle(11, fruit, 0), Aisle(12, pantry, 1), Aisle(13, dairy, 2)),
    )
    val otherStore = Supermarket(id = 2, name = "Hörnbutiken", aisles = listOf(Aisle(21, dairy, 0)))
    val supermarkets = listOf(store, otherStore)
    val lists = listOf(ShoppingList(1, "Veckohandling"), ShoppingList(2, "Apotek"))

    private val gratin = ShoppingRecipeLabel(listRecipeId = 3, recipeId = 14, recipeName = "Potatisgratäng", servings = 4.0)

    val milk = ShoppingEntry(1, ShoppingFood(19, "mjölk", categoryId = 2), ShoppingUnit(4, "l"), 2.0, listIds = setOf(1))
    val cream = ShoppingEntry(2, ShoppingFood(39, "grädde", categoryId = 2), ShoppingUnit(5, "dl"), 3.0, recipe = gratin, ingredientId = 74)
    val potato = ShoppingEntry(3, ShoppingFood(4, "potatis", "potatisar", categoryId = 1), ShoppingUnit(2, "kg"), 1.0, recipe = gratin)
    val toothpaste = ShoppingEntry(4, ShoppingFood(42, "tandkräm"), amount = 1.0, addedBy = "Erik", addedById = 3)
    val eggs = ShoppingEntry(
        5,
        ShoppingFood(18, "ägg", categoryId = 2),
        ShoppingUnit(6, "st"),
        6.0,
        checked = true,
        completedAt = now - 1.hours,
        listIds = setOf(1),
    )
    val onions = ShoppingEntry(6, ShoppingFood(7, "gul lök", "gula lökar", categoryId = 1), amount = 3.0)
    val tomatoes = ShoppingEntry(7, ShoppingFood(8, "krossade tomater", categoryId = 3), ShoppingUnit(9, "burk", "burkar"), 2.0)
    val coffee = ShoppingEntry(8, ShoppingFood(9, "kaffe", categoryId = 3), ShoppingUnit(10, "paket"), 1.0, delayUntil = now + 2.days)
    val butter = ShoppingEntry(-9, ShoppingFood(null, "smör"), ShoppingUnit(null, "g"), 500.0, pending = true)

    val entries = listOf(milk, cream, potato, toothpaste, eggs, onions, tomatoes, coffee, butter)

    val history = listOf(
        ShoppingHistoryItem(101, "havregryn", amount = 1.0, unitName = "paket", completedAt = now - 20.days),
        ShoppingHistoryItem(102, "bananer", amount = 6.0, completedAt = now - 20.days),
        ShoppingHistoryItem(103, "olivolja", completedAt = now - 30.days),
    )

    /** The list screen's state, as [ShoppingViewModel] builds it. */
    fun state(
        entries: List<ShoppingEntry> = this.entries,
        store: Supermarket? = this.store,
        readOnly: Boolean = false,
        pendingCount: Int = 0,
        syncNotice: ShoppingSyncNotice? = null,
        checkedExpanded: Boolean = false,
        selectedListId: Long? = null,
    ): ShoppingUiState = ShoppingUiState(
        loading = false,
        readOnly = readOnly,
        lists = lists,
        selectedListId = selectedListId,
        supermarkets = supermarkets,
        store = store,
        categories = categories,
        grouped = ShoppingGrouping.group(entries, categories, store, now),
        pendingCount = pendingCount,
        syncNotice = syncNotice,
        checkedExpanded = checkedExpanded,
        add = AddItemState(recent = history.map { it.foodName }),
        history = history,
    )

    fun storeModeState(entries: List<ShoppingEntry> = this.entries, readOnly: Boolean = false): StoreModeUiState {
        val grouped = ShoppingGrouping.group(entries, categories, store, now)
        return StoreModeUiState(
            loading = false,
            readOnly = readOnly,
            store = store,
            sections = grouped.sections,
            inCart = grouped.checked.size,
        )
    }
}

/** Theme, app chrome and font scale around a screen under test. */
@Composable
internal fun ShoppingTestTheme(
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
