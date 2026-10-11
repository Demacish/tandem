package casa.moller.tandem.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.dao.RecipeIndexEntry
import casa.moller.tandem.core.database.mapper.toEntity
import casa.moller.tandem.core.model.recipe.RecipeQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Offline search (plan 5.6 "FTS med å/ä/ö"): FTS4 `unicode61 remove_diacritics=2` plus name substrings. */
@RunWith(AndroidJUnit4::class)
class RecipeSearchTest {
    private val database = newDatabase()
    private val dao = database.recipeDao()

    @After
    fun close() {
        database.close()
    }

    private suspend fun seed() {
        TestRecipes.All.forEach { database.store(ACCOUNT_A, it) }
        // An index-only recipe: no detail, only id + name from recipe/flat.
        dao.applyIndex(ACCOUNT_A, TestRecipes.All.map { RecipeIndexEntry(it.id, it.name) } + RecipeIndexEntry(7, "Ölbröd"))
    }

    private suspend fun search(text: String, accountId: String = ACCOUNT_A): List<String> =
        dao.observeRecipeList(accountId, RecipeQuery(text = text), SWEDISH).first().map { it.overview.name }

    @Test
    fun diacriticsAndCaseAreFoldedBothWays() = runTest {
        seed()
        assertEquals(listOf("Krämig svamppasta"), search("kräm"))
        assertEquals(listOf("Krämig svamppasta"), search("kram"))
        assertEquals(listOf("Krämig svamppasta"), search("KRÄM"))
        assertEquals(listOf("Ärtsoppa"), search("ärt"))
        assertEquals(listOf("Ärtsoppa"), search("art"))
        assertEquals(listOf("Köttbullar med potatismos"), search("kottbullar"))
        assertEquals(listOf("Ölbröd"), search("olbrod"))
        assertEquals(listOf("Ölbröd"), search("ÖLBRÖD"))
    }

    @Test
    fun wordPrefixesMatch() = runTest {
        seed()
        assertEquals(listOf("Krämig svamppasta", "Svampsoppa"), search("svamp"))
        assertEquals(listOf("Krämig svamppasta"), search("varda"))
        assertEquals(listOf("Äppelkaka"), search("äppel"))
    }

    @Test
    fun pastaIsFoundAsKeywordIngredientAndInsideACompoundName() = runTest {
        seed()
        dao.applyIndex(ACCOUNT_A, listOf(RecipeIndexEntry(1, "Krämig svamppasta"), RecipeIndexEntry(8, "Tomatpasta")))
        assertEquals(listOf("Krämig svamppasta", "Tomatpasta"), search("pasta"))
    }

    @Test
    fun ingredientsKeywordsAndDescriptionsAreSearched() = runTest {
        seed()
        assertEquals(listOf("Krämig svamppasta"), search("champinjon"))
        assertEquals(listOf("Svampsoppa"), search("kantarell"))
        assertEquals(listOf("Svampsoppa", "Ärtsoppa"), search("soppa"))
        assertEquals(listOf("Äppelkaka"), search("efterrätt"))
        assertEquals(listOf("Äppelkaka"), search("applen"))
    }

    @Test
    fun everyWordMustMatch() = runTest {
        seed()
        assertEquals(listOf("Krämig svamppasta"), search("svamp grädde"))
        assertEquals(listOf("Svampsoppa"), search("svamp kantarell"))
        assertEquals(listOf("Krämig svamppasta"), search("  Krämig,   SVAMP!  "))
        assertEquals(emptyList<String>(), search("svamp äpple"))
    }

    @Test
    fun ftsOperatorsAreTreatedAsPlainText() = runTest {
        seed()
        assertEquals(emptyList<String>(), search("svamp OR äpple"))
        assertEquals(listOf("Krämig svamppasta", "Svampsoppa"), search("\"svamp* -"))
        assertEquals(7, search("* ( ) \" -").size)
        assertEquals(7, search("").size)
    }

    @Test
    fun ftsFollowsRenamesAndDeletions() = runTest {
        seed()
        assertEquals(7L, database.count("SELECT COUNT(*) FROM recipe_fts"))
        val renamed = TestRecipes.All.filter { it != TestRecipes.PeaSoup }.map { recipe ->
            RecipeIndexEntry(recipe.id, if (recipe == TestRecipes.MushroomSoup) "Skogssoppa" else recipe.name)
        }
        dao.applyIndex(ACCOUNT_A, renamed + RecipeIndexEntry(7, "Ölbröd"))
        assertEquals(listOf("Skogssoppa"), search("skog"))
        assertEquals(listOf("Krämig svamppasta"), search("svamp"))
        assertEquals(emptyList<String>(), search("ärt"))
        assertEquals(6L, database.count("SELECT COUNT(*) FROM recipe_fts"))
    }

    @Test
    fun keywordRenameIsSearchableAfterReplaceKeywords() = runTest {
        seed()
        dao.replaceKeywords(ACCOUNT_A, listOf(TestRecipes.Dessert.copy(name = "Kaffebröd")).map { it.toEntity(ACCOUNT_A) })
        assertEquals(listOf("Äppelkaka"), search("kaffe"))
        assertEquals(emptyList<String>(), search("efterrätt"))
    }

    @Test
    fun searchNeverSeesAnotherAccount() = runTest {
        seed()
        database.store(ACCOUNT_B, TestRecipes.recipe(id = 1, name = "Blåbärspaj", foods = listOf("Blåbär")))
        assertEquals(emptyList<String>(), search("blåbär"))
        assertEquals(listOf("Blåbärspaj"), search("blabar", ACCOUNT_B))
        assertEquals(emptyList<String>(), search("svamp", ACCOUNT_B))
    }
}
