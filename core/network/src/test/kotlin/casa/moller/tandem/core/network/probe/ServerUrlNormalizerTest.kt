package casa.moller.tandem.core.network.probe

import casa.moller.tandem.core.model.error.InvalidUrlReason
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlNormalizerTest {

    private val valid = listOf(
        // bare host names and schemes
        "recept.example.com" to "https://recept.example.com/",
        "  recept.example.com \n" to "https://recept.example.com/",
        "https://recept.example.com" to "https://recept.example.com/",
        "https://recept.example.com/" to "https://recept.example.com/",
        "https://recept.example.com///" to "https://recept.example.com/",
        "HTTPS://Recept.Example.COM" to "https://recept.example.com/",
        // sub-path installs (path case is kept, host case is not)
        "https://example.com/tandoor" to "https://example.com/tandoor/",
        "https://example.com/tandoor/" to "https://example.com/tandoor/",
        "example.com/apps/Tandoor//" to "https://example.com/apps/Tandoor/",
        "HTTPS://EXAMPLE.com/Tandoor/" to "https://example.com/Tandoor/",
        // IDN
        "räksmörgås.se" to "https://xn--rksmrgs-5wao1o.se/",
        "https://Kök.Example/tandoor" to "https://xn--kk-fka.example/tandoor/",
        // ports
        "recept.example.com:8443" to "https://recept.example.com:8443/",
        "https://recept.example.com:443/" to "https://recept.example.com/",
        "192.168.1.20:8443" to "https://192.168.1.20:8443/",
        "localhost:9000/tandoor" to "https://localhost:9000/tandoor/",
        // pasted deep links
        "https://recept.example.com/recipe/12/" to "https://recept.example.com/",
        "https://example.com/tandoor/recipe/12/" to "https://example.com/tandoor/",
        "https://recept.example.com/accounts/login/?next=/search/" to "https://recept.example.com/",
        "https://example.com/tandoor/accounts/login/?next=/tandoor/" to "https://example.com/tandoor/",
        "https://recept.example.com/api/server-settings/current/" to "https://recept.example.com/",
        "https://example.com/tandoor/api-token-auth/" to "https://example.com/tandoor/",
        "https://example.com/tandoor/view/recipe/5" to "https://example.com/tandoor/",
        "recept.example.com:8443/tandoor/search?query=pasta#top" to "https://recept.example.com:8443/tandoor/",
        "https://recept.example.com/#/recipe/12" to "https://recept.example.com/",
        "https://recept.example.com/mealplan" to "https://recept.example.com/",
        "https://recept.example.com/Recipe/3" to "https://recept.example.com/",
        // "recipes" is no Tandoor route but the sub-path in Tandoor's own SCRIPT_NAME example
        "https://example.com/recipes" to "https://example.com/recipes/",
        "example.com/recipes/" to "https://example.com/recipes/",
        "https://example.com/recipes/recipe/12/" to "https://example.com/recipes/",
        "https://example.com/recipes/accounts/login/?next=/recipes/" to "https://example.com/recipes/",
        // plausible sub-path names that are not cut
        "https://example.com/test/recipe/5" to "https://example.com/test/",
        "https://example.com/system/" to "https://example.com/system/",
        // more 2.6.13 routes
        "https://x.example/tandoor/advanced-search" to "https://x.example/tandoor/",
        "https://x.example/advanced-search?query=pasta" to "https://x.example/",
        "https://x.example/tandoor/pantry" to "https://x.example/tandoor/",
        "https://x.example/tandoor/inventory/" to "https://x.example/tandoor/",
        "https://x.example/household-setup" to "https://x.example/",
        "https://x.example/space-setup/" to "https://x.example/",
        "https://x.example/tandoor/delete/recipe/3" to "https://x.example/tandoor/",
        "https://x.example/no-group/" to "https://x.example/",
        // credentials and query are dropped
        "https://anna:secret@recept.example.com/" to "https://recept.example.com/",
        "https://recept.example.com/?utm_source=x" to "https://recept.example.com/",
    )

    private val invalid = listOf(
        "" to InvalidUrlReason.EMPTY,
        "   " to InvalidUrlReason.EMPTY,
        "http://recept.example.com" to InvalidUrlReason.CLEARTEXT_NOT_ALLOWED,
        "HTTP://recept.example.com/tandoor/" to InvalidUrlReason.CLEARTEXT_NOT_ALLOWED,
        "ftp://recept.example.com" to InvalidUrlReason.UNSUPPORTED_SCHEME,
        "https://" to InvalidUrlReason.MALFORMED,
        "recept example.com" to InvalidUrlReason.MALFORMED,
        "https://exa mple.com/" to InvalidUrlReason.MALFORMED,
        "https://:8443/" to InvalidUrlReason.MALFORMED,
    )

    @Test
    fun normalizesValidInput() {
        assertTrue("at least 15 cases", valid.size >= 15)
        valid.forEach { (input, expected) ->
            assertEquals("input '$input'", Outcome.Success(expected), ServerUrlNormalizer.normalize(input))
        }
    }

    @Test
    fun rejectsInvalidInput() {
        invalid.forEach { (input, reason) ->
            assertEquals("input '$input'", Outcome.Failure(TandemError.InvalidUrl(reason)), ServerUrlNormalizer.normalize(input))
        }
    }

    @Test
    fun normalizingIsIdempotent() {
        valid.forEach { (_, expected) ->
            assertEquals(Outcome.Success(expected), ServerUrlNormalizer.normalize(expected))
        }
    }

    @Test
    fun candidatesAreTheTypedPathThenTheCutBase() {
        val cases = listOf(
            "https://example.com/tandoor" to listOf("https://example.com/tandoor/"),
            "recept.example.com" to listOf("https://recept.example.com/"),
            // A sub-path named like a route: the typed path first.
            "https://example.com/books" to listOf("https://example.com/books/", "https://example.com/"),
            // A pasted deep link: the deep link (fails on Tandoor), then the base.
            "https://example.com/tandoor/recipe/12/?share=x#top" to
                listOf("https://example.com/tandoor/recipe/12/", "https://example.com/tandoor/"),
            "https://anna:pw@example.com/recipes/" to listOf("https://example.com/recipes/"),
        )
        cases.forEach { (input, expected) ->
            assertEquals("input '$input'", Outcome.Success(expected), ServerUrlNormalizer.candidates(input))
        }
        assertEquals(
            Outcome.Failure(TandemError.InvalidUrl(InvalidUrlReason.CLEARTEXT_NOT_ALLOWED)),
            ServerUrlNormalizer.candidates("http://example.com/"),
        )
    }
}
