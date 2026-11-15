package casa.moller.tandem.core.domain.inbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Fas 5 (rss lane): the inbox's excerpt and image from a post's HTML, without rendering it. */
class InboxTextTest {

    @Test
    fun theExcerptIsPlainTextWithEntitiesDecoded() {
        val html = "<p>Tunna pannkakor med <b>blåbär</b> &amp; grädde.</p><p>Gräddas i&nbsp;smör.</p>"
        assertEquals("Tunna pannkakor med blåbär & grädde. Gräddas i smör.", InboxText.excerpt(html, 400))
    }

    @Test
    fun scriptsStylesAndCommentsAreDropped() {
        val html = "<style>p{color:red}</style><script>alert('x')</script><!-- note --><p>Linssoppa</p>"
        assertEquals("Linssoppa", InboxText.excerpt(html, 400))
    }

    @Test
    fun aLongTextIsCutAtAWord() {
        val excerpt = InboxText.excerpt("<p>" + "ord ".repeat(200) + "</p>", 40)
        assertTrue(excerpt.length <= 40, excerpt)
        assertTrue(excerpt.endsWith("ord…"), excerpt)
    }

    @Test
    fun numericEntitiesAndUnknownOnes() {
        assertEquals("Café – 4 st & mer", InboxText.decode("Caf&#233; &#x2013; 4 st &amp; mer"))
        assertEquals("&bogus; &#0;", InboxText.decode("&bogus; &#0;"))
        assertEquals("", InboxText.excerpt("   ", 10))
    }

    @Test
    fun theImageIsAnHttpsEnclosureFirst() {
        val html = """<p><img src="https://example.com/i/soppa.jpg"></p>"""
        assertEquals(
            "https://example.com/i/pannkakor.jpg",
            InboxText.firstImage(html, listOf("https://example.com/a.mp3" to "audio/mpeg", "https://example.com/i/pannkakor.jpg" to "image/jpeg")),
        )
        assertEquals("https://example.com/i/soppa.jpg", InboxText.firstImage(html))
    }

    @Test
    fun onlyHttpsImagesAreTaken() {
        val html = """<img alt='x' src='http://example.com/a.jpg'><img src="data:image/png;base64,AAAA"><IMG SRC="https://example.com/b.jpg?w=1&amp;h=2">"""
        assertEquals("https://example.com/b.jpg?w=1&h=2", InboxText.firstImage(html, listOf("http://example.com/c.jpg" to "image/jpeg")))
        assertNull(InboxText.firstImage("<p>Ingen bild</p>"))
    }
}
