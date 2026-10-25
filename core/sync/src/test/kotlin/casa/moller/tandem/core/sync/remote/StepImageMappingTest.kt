package casa.moller.tandem.core.sync.remote

import casa.moller.tandem.core.network.dto.RecipeDto
import casa.moller.tandem.core.network.json.TandoorJson
import casa.moller.tandem.core.testing.fixtures.TandoorFixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A step's photo (`Step.imageUrl`, cook mode) from `StepDto.filePreview` through the same
 * [ImageResolver] as the recipe image (FACTS "Fas 1 e", "Fas 1 m"): the recorded step file of
 * `fas1/recipe-step-file` (absolute https), a PDF (preview ""), no file, and the shapes a proxy
 * or an older server could send (relative, `http`, a sub-path install).
 */
class StepImageMappingTest {
    private val recorded: JsonObject =
        TandoorJson.parseToJsonElement(TandoorFixtures.exchange("fas1/recipe-step-file").body!!).jsonObject

    private fun stepImages(baseUrl: String, recipe: JsonObject = recorded): List<String?> =
        TandoorJson.decodeFromJsonElement(RecipeDto.serializer(), recipe)
            .toRecipe(ImageResolver.forBaseUrl(baseUrl))!!
            .steps.map { it.imageUrl }

    /** [recorded] with the first step's `preview` replaced. */
    private fun withPreview(preview: JsonElement): JsonObject {
        val steps = recorded.getValue("steps").jsonArray
        val first = steps.first().jsonObject
        val file = JsonObject(first.getValue("file").jsonObject + ("preview" to preview))
        return JsonObject(recorded + ("steps" to JsonArray(listOf(JsonObject(first + ("file" to file))) + steps.drop(1))))
    }

    @Test
    fun theRecordedImageIsUsedAsItIsAndOtherFilesGiveNoPhoto() {
        val preview = recorded.getValue("steps").jsonArray.first().jsonObject.getValue("file").jsonObject
            .getValue("preview").let { (it as JsonPrimitive).content }

        assertEquals(listOf(preview, null, null), stepImages("https://tandoor.example.com/"))
    }

    @Test
    fun relativePathsGoOntoTheAccountsBaseUrl() {
        val relative = withPreview(JsonPrimitive("/media/files/0000_stegbild.png"))
        assertEquals("https://recept.exempel.se/media/files/0000_stegbild.png", stepImages("https://recept.exempel.se/", relative).first())
        // A sub-path install: below the base path.
        assertEquals(
            "https://exempel.se/tandoor/media/files/0000_stegbild.png",
            stepImages("https://exempel.se/tandoor/", relative).first(),
        )
    }

    @Test
    fun anHttpPreviewIsMovedOntoTheBaseUrlAndBlankOrOddValuesGiveNoPhoto() {
        // A proxy without X-Forwarded-Proto: Tandoor builds http URLs, which the app never loads.
        val http = withPreview(JsonPrimitive("http://tandoor.example.com/media/files/0000_stegbild.png"))
        assertEquals("https://recept.exempel.se/media/files/0000_stegbild.png", stepImages("https://recept.exempel.se/", http).first())

        assertNull(stepImages("https://recept.exempel.se/", withPreview(JsonPrimitive(""))).first())
        assertNull(stepImages("https://recept.exempel.se/", withPreview(JsonNull)).first())
        assertNull(stepImages("https://recept.exempel.se/", withPreview(JsonPrimitive("data:image/png;base64,AAAA"))).first())
    }
}
