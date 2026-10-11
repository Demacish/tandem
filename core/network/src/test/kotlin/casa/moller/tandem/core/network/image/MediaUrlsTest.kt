package casa.moller.tandem.core.network.image

import casa.moller.tandem.core.network.dto.PageDto
import casa.moller.tandem.core.network.dto.RecipeFlatDto
import casa.moller.tandem.core.network.dto.RecipeOverviewDto
import casa.moller.tandem.core.network.json.TandoorJson
import casa.moller.tandem.core.network.testing.Fixture
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaUrlsTest {
    private val base = "https://tandoor.example.com/"
    private val subPath = "https://example.com/tandoor/"

    @Test
    fun relativePathsFromTheFlatIndex() {
        val flat = TandoorJson.decodeFromString(ListSerializer(RecipeFlatDto.serializer()), Fixture.load("fas1/recipe-flat").bodyText)
        val image = flat.single { it.id == 2L }.image
        assertEquals("https://tandoor.example.com$image", MediaUrls.resolve(base, image))
        assertEquals("https://example.com/tandoor/media/recipes/a_2.png", MediaUrls.resolve(subPath, "/media/recipes/a_2.png"))
        assertEquals("https://example.com/tandoor/media/recipes/a_2.png", MediaUrls.resolve(subPath, "/tandoor/media/recipes/a_2.png"))
        assertEquals("https://example.com/tandoor/media/recipes/a_2.png", MediaUrls.resolve(subPath, "media/recipes/a_2.png"))
    }

    @Test
    fun absoluteHttpsUrlsAreKept() {
        val page = TandoorJson.decodeFromString(
            PageDto.serializer(RecipeOverviewDto.serializer()),
            Fixture.load("fas1/recipe-list-default").bodyText,
        )
        val image = page.results.single { it.id == 2L }.image!!
        assertEquals(image, MediaUrls.resolve(base, image))
        // External storage on another host stays as it is (the token is never sent there).
        assertEquals("https://cdn.example.com/media/x.png", MediaUrls.resolve(base, "https://cdn.example.com/media/x.png"))
    }

    @Test
    fun cleartextUrlsFromAProxyWithoutForwardedProtoMoveOntoTheBase() {
        assertEquals(
            "https://tandoor.example.com/media/recipes/a_2.png",
            MediaUrls.resolve(base, "http://tandoor.example.com/media/recipes/a_2.png"),
        )
        assertEquals(
            "https://example.com/tandoor/media/recipes/a_2.png?v=1",
            MediaUrls.resolve(subPath, "http://127.0.0.1:8080/media/recipes/a_2.png?v=1"),
        )
        assertNull("no media path: cannot be loaded", MediaUrls.resolve(base, "http://tandoor.example.com/other.png"))
    }

    @Test
    fun nothingElse() {
        assertNull(MediaUrls.resolve(base, null))
        assertNull(MediaUrls.resolve(base, "  "))
        assertNull(MediaUrls.resolve(base, "data:image/png;base64,AAAA"))
        assertNull(MediaUrls.resolve(base, "content://media/1"))
        assertNull("protocol-relative", MediaUrls.resolve(base, "//evil.example.com/media/x.png"))
        assertNull("no usable base", MediaUrls.resolve("http://tandoor.example.com/", "/media/x.png"))
    }
}
