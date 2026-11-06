package casa.moller.tandem.feature.widgets

import casa.moller.tandem.core.model.recipe.RecipeOverview
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Plan 8.7: "Ny vara", "Veckan", "Importera länk", senast lagade, within the launcher's limit. */
class AppShortcutsTest {
    private val pasta = RecentRecipe(6, "Krämig svamppasta")
    private val soup = RecentRecipe(7, "Tomatsoppa")

    private fun ids(entries: List<AppShortcuts.Entry>): List<String> = entries.map { entry ->
        when (entry) {
            is AppShortcuts.Entry.Fixed -> entry.shortcut.id
            is AppShortcuts.Entry.Cooked -> "recipe-${entry.recipe.id}"
        }
    }

    @Test
    fun theLatestCookedComeAfterTheThreeTheLauncherShowsFirst() {
        assertEquals(
            listOf("add-item", "week", "import", "recipe-6", "recipe-7", "shopping", "scan"),
            ids(AppShortcuts.plan(listOf(pasta, soup), limit = 15)),
        )
    }

    @Test
    fun theLimitTakesTheLastFixedOnesFirst() {
        assertEquals(listOf("add-item", "week", "import", "recipe-6"), ids(AppShortcuts.plan(listOf(pasta, soup), limit = 4)))
        assertEquals(emptyList<String>(), ids(AppShortcuts.plan(emptyList(), limit = 0)))
    }

    @Test
    fun onlyRecipesThatWereCookedCountAndAtMostTwo() {
        val now = Instant.parse("2026-11-06T12:00:00Z")
        val cooked: List<RecipeOverview> = (1L..3L).map { RecipeOverview(id = it, name = "Recept $it", lastCooked = now - it.days) }
        val never = RecipeOverview(id = 9, name = "Aldrig lagat")
        assertEquals(listOf(1L, 2L), RecentRecipe.of(cooked + never).map { it.id })
        assertEquals(emptyList<RecentRecipe>(), RecentRecipe.of(listOf(never)))
    }
}
