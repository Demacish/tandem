package casa.moller.tandem.core.testing

import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.recipe.RecipeSort
import casa.moller.tandem.core.model.sync.SyncPhase
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.testing.fixtures.TandoorFixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTestingTest {

    private val recipes = FakeRecipeRepository()

    @Test
    fun searchIgnoresCaseAndDiacritics() = runTest {
        val found = recipes.observeRecipes(RecipeQuery(text = "artsoppa")).first()
        assertEquals(listOf("Ärtsoppa med fläsk"), found.map { it.name })

        val byFood = recipes.observeRecipes(RecipeQuery(text = "KARDEMUMMA")).first()
        assertEquals(listOf(SampleRecipes.CinnamonBuns.id), byFood.map { it.id })
    }

    @Test
    fun namesSortTheSwedishWay() = runTest {
        val names = recipes.observeRecipes(RecipeQuery(sort = RecipeSort.NAME)).first().map { it.name }
        // å, ä and ö come after z in Swedish.
        assertEquals(listOf("Äppelpaj", "Ärtsoppa med fläsk", "Ölbröd"), names.takeLast(3))
    }

    @Test
    fun servingsChoiceIsRememberedPerRecipe() = runTest {
        recipes.setServingsChoice(SampleRecipes.MushroomPasta.id, 2.0)
        assertEquals(2.0, recipes.observeServingsChoice(SampleRecipes.MushroomPasta.id).first()?.servings)
        recipes.setServingsChoice(SampleRecipes.MushroomPasta.id, null)
        assertEquals(null, recipes.observeServingsChoice(SampleRecipes.MushroomPasta.id).first())
    }

    @Test
    fun firstSyncEndsWithDone() = runTest {
        val progress = FakeSyncRepository().firstSync().toList()
        assertEquals(SyncPhase.DONE, progress.last().phase)
    }

    @Test
    fun fixturesComeFromApiSchema() {
        val user = TandoorFixtures.exchange("11-user-by-id")
        assertEquals("GET", user.method)
        assertEquals(200, user.status)
        assertNotNull(user.body)
        assertTrue(TandoorFixtures.text("FACTS.md").isNotBlank())
    }

    @Test
    fun phase1RecordingsAreInTheFas1Folder() {
        val preferences = TandoorFixtures.exchange("fas1/user-preference")
        assertEquals("/api/user-preference/", preferences.path)
        assertEquals(200, preferences.status)
    }
}
