package casa.moller.tandem.core.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.error.InvalidUrlReason
import casa.moller.tandem.core.model.error.NotTandoorReason
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.TlsErrorKind
import casa.moller.tandem.core.model.error.UnauthorizedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class TandemErrorTextTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val errors: List<TandemError> = listOf(
        TandemError.NetworkUnavailable,
        TandemError.UnknownHost,
        TandemError.Timeout,
        TandemError.TlsError(TlsErrorKind.UNTRUSTED_CERTIFICATE),
        TandemError.TlsError(TlsErrorKind.HOSTNAME_MISMATCH),
        TandemError.InvalidUrl(InvalidUrlReason.MALFORMED),
        TandemError.InvalidUrl(InvalidUrlReason.CLEARTEXT_NOT_ALLOWED),
        TandemError.NotTandoor(NotTandoorReason.LOGIN_REDIRECT, httpStatus = 302),
        TandemError.Unauthorized(UnauthorizedSource.TANDOOR),
        TandemError.Blocked,
        TandemError.NotFound,
        TandemError.BadRequest(),
        TandemError.Throttled(retryAfter = null),
        TandemError.ServerError(502),
        TandemError.Decode(),
        TandemError.Unknown(),
    )

    private fun texts(): List<TandemErrorText> {
        var result: List<TandemErrorText> = emptyList()
        composeRule.setContent { result = errors.map { it.toErrorText() } }
        composeRule.waitForIdle()
        return result
    }

    @Test
    fun everyErrorHasItsOwnTitleAndBody() {
        val texts = texts()
        assertEquals(errors.size, texts.size)
        texts.forEach { assertTrue(it.title.isNotBlank() && it.body.isNotBlank()) }
        // Every case in the list has its own explanation (titles may be shared, e.g. both
        // InvalidUrl reasons are "Invalid address").
        assertEquals(texts.size, texts.map { it.body }.toSet().size)
    }

    @Test
    fun detailsNameTheErrorType() {
        val texts = texts()
        assertTrue(texts[7].details.contains("NotTandoor"))
        assertTrue(texts[7].details.contains("302"))
    }

    @Test
    @Config(qualifiers = "sv")
    fun swedishTexts() {
        val texts = texts()
        assertEquals("Certifikatet är inte betrott", texts[3].title)
        assertEquals("Logga in igen", texts[8].title)
    }
}
