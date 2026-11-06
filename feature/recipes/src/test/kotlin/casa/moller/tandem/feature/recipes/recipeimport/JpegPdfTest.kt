package casa.moller.tandem.feature.recipes.recipeimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan §8.4: several pages, one PDF, about the size of the JPEGs themselves. */
class JpegPdfTest {
    /** A JPEG as far as the PDF cares: a start, a comment, a frame header and some data. */
    private fun jpeg(width: Int, height: Int, components: Int = 3, data: Int = 16): ByteArray {
        val bytes = mutableListOf(0xFF, 0xD8, 0xFF, 0xFE, 0x00, 0x04, 0x41, 0x42)
        bytes += listOf(0xFF, 0xC0, 0x00, 0x11, 0x08, height shr 8, height and 0xFF, width shr 8, width and 0xFF, components)
        bytes += List(data) { 0x11 }
        bytes += listOf(0xFF, 0xD9)
        return bytes.map { it.toByte() }.toByteArray()
    }

    private fun text(pdf: ByteArray) = String(pdf, Charsets.ISO_8859_1)

    @Test
    fun theFrameIsFoundAfterOtherSegments() {
        assertEquals(JpegPdf.Frame(1600, 1200, 3), JpegPdf.frameOf(jpeg(1600, 1200)))
        assertEquals(JpegPdf.Frame(10, 20, 1), JpegPdf.frameOf(jpeg(10, 20, components = 1)))
    }

    @Test
    fun whatIsNoJpegIsRefused() {
        assertNull(JpegPdf.frameOf(byteArrayOf(1, 2, 3)))
        assertNull(JpegPdf.frameOf(ByteArray(0)))
        assertNull(JpegPdf.join(listOf(jpeg(10, 10), byteArrayOf(1, 2, 3))))
    }

    @Test
    fun threePagesAreOneDocumentWithEveryPageInIt() {
        val pdf = JpegPdf.join(listOf(jpeg(1600, 1200), jpeg(1200, 1600), jpeg(800, 600)))!!
        val text = text(pdf)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        assertTrue(text.contains("/Count 3"))
        assertEquals(3, "/Subtype /Image".toRegex().findAll(text).count())
        assertTrue(text.contains("/MediaBox [0 0 1200 1600]"))
        assertTrue(text.contains("/Filter /DCTDecode"))
    }

    @Test
    fun theCrossReferenceTablePointsAtTheObjects() {
        val pdf = JpegPdf.join(listOf(jpeg(100, 100), jpeg(200, 200)))!!
        val text = text(pdf)
        val table = text.substring(text.indexOf("xref\n")).lines()
        val count = table[1].split(" ")[1].toInt()
        (1 until count).forEach { number ->
            val offset = table[2 + number].take(10).toInt()
            assertTrue("object $number at $offset", text.startsWith("$number 0 obj", offset))
        }
        val start = text.substringAfter("startxref\n").substringBefore("\n").toInt()
        assertTrue(text.startsWith("xref", start))
    }

    @Test
    fun theFileIsThePagesPlusAboutAKilobyte() {
        val pages = List(3) { jpeg(1600, 1200, data = 400_000) }
        val pdf = JpegPdf.join(pages)!!
        val overhead = pdf.size - pages.sumOf { it.size }
        assertTrue("overhead $overhead", overhead in 1..2_048)
    }
}
