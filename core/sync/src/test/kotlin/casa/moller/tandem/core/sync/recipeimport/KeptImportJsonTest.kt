package casa.moller.tandem.core.sync.recipeimport

import casa.moller.tandem.core.data.recipe.KeptRecipeImport
import casa.moller.tandem.core.model.recipe.DraftIngredient
import casa.moller.tandem.core.model.recipe.DraftKeyword
import casa.moller.tandem.core.model.recipe.DraftStep
import casa.moller.tandem.core.model.recipe.ImportDuplicate
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.model.recipe.RecipeImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Komplettering 3: everything an import can put in a draft comes back from the `sync_state` row. */
class KeptImportJsonTest {
    private val kept = KeptRecipeImport(
        RecipeImportResult(
            draft = RecipeDraft(
                name = "Pannkakor",
                description = "Tunna",
                servings = 4,
                servingsText = "portioner",
                workingMinutes = 25,
                waitingMinutes = 30,
                sourceUrl = "https://example.com/recept",
                keywords = listOf(DraftKeyword(id = 5, name = "Husmanskost"), DraftKeyword(name = "Mellanmål")),
                steps = listOf(
                    DraftStep(
                        name = "Smeten",
                        instruction = "Vispa.",
                        timeMinutes = 5,
                        showAsHeader = true,
                        showIngredientsTable = false,
                        ingredients = listOf(
                            DraftIngredient(amount = 2.5, unit = "dl", food = "vetemjöl", note = "siktat"),
                            DraftIngredient(note = "Till stekning", isHeader = true),
                            DraftIngredient(food = "salt", noAmount = true),
                        ),
                    ),
                ),
                imageUrl = "https://example.com/bild.jpg",
            ),
            imageUrls = listOf("https://example.com/bild.jpg", "https://example.com/bild-2.jpg"),
            duplicates = listOf(ImportDuplicate(3, "Pannkakor")),
        ),
        sourcePath = "/cache/recipe-import/recept.jpg",
    )

    @Test
    fun anAnswerComesBackAsItWas() {
        assertEquals(kept, KeptImportJson.decode(KeptImportJson.encode(kept)))
    }

    @Test
    fun aRowThatCannotBeReadIsNoAnswer() {
        assertNull(KeptImportJson.decode(null))
        assertNull(KeptImportJson.decode("not json"))
        assertNull(KeptImportJson.decode("""{"imageUrls":[]}"""))
    }
}
