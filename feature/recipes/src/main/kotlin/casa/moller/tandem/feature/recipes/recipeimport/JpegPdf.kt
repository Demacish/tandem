package casa.moller.tandem.feature.recipes.recipeimport

import java.io.ByteArrayOutputStream

/**
 * Plan §8.4: several photographed pages become one PDF for the AI import. The JPEGs go into the
 * PDF as they are (`/DCTDecode`), so the file is the pages' size plus about a kilobyte; drawing them
 * through `android.graphics.pdf.PdfDocument` would store them deflated, several times larger, and
 * three pages of a cookbook could pass the import's 10 MB limit. Pure, so it is tested on the JVM.
 */
internal object JpegPdf {
    /** One JPEG's size and colours, from its frame header. */
    data class Frame(val width: Int, val height: Int, val components: Int)

    /** The size of [jpeg], or null when it is no baseline or progressive JPEG with a frame header. */
    fun frameOf(jpeg: ByteArray): Frame? {
        var at = 2
        if (jpeg.size < 2 || jpeg.u8(0) != MARKER || jpeg.u8(1) != START_OF_IMAGE) return null
        while (at + MARKER_HEADER <= jpeg.size) {
            if (jpeg.u8(at) != MARKER) return null
            val marker = jpeg.u8(at + 1)
            val length = jpeg.u16(at + 2)
            if (marker in FRAME_MARKERS) {
                if (at + FRAME_COMPONENTS >= jpeg.size) return null
                return Frame(width = jpeg.u16(at + FRAME_WIDTH), height = jpeg.u16(at + FRAME_HEIGHT), components = jpeg.u8(at + FRAME_COMPONENTS))
            }
            at += 2 + length
        }
        return null
    }

    /** [pages] as one PDF with a page each, at the photo's own size; null when one is no JPEG. */
    fun join(pages: List<ByteArray>): ByteArray? {
        val frames = pages.map { frameOf(it) ?: return null }
        val out = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        fun write(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))
        fun startObject() {
            offsets += out.size()
            write("${offsets.size} 0 obj\n")
        }
        write("%PDF-1.4\n")
        val kids = pages.indices.joinToString(" ") { "${PAGE_OBJECTS_START + it * OBJECTS_PER_PAGE} 0 R" }
        startObject()
        write("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        startObject()
        write("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>\nendobj\n")
        pages.forEachIndexed { index, jpeg ->
            val frame = frames[index]
            val page = PAGE_OBJECTS_START + index * OBJECTS_PER_PAGE
            startObject()
            write("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${frame.width} ${frame.height}] ")
            write("/Resources << /XObject << /Page$index ${page + 1} 0 R >> >> /Contents ${page + 2} 0 R >>\nendobj\n")
            startObject()
            val colours = if (frame.components == 1) "/DeviceGray" else "/DeviceRGB"
            write("<< /Type /XObject /Subtype /Image /Width ${frame.width} /Height ${frame.height} /ColorSpace $colours ")
            write("/BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n")
            out.write(jpeg)
            write("\nendstream\nendobj\n")
            startObject()
            val draw = "q ${frame.width} 0 0 ${frame.height} 0 0 cm /Page$index Do Q"
            write("<< /Length ${draw.length} >>\nstream\n$draw\nendstream\nendobj\n")
        }
        val table = out.size()
        write("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { write("%010d 00000 n \n".format(it)) }
        write("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$table\n%%EOF\n")
        return out.toByteArray()
    }

    private fun ByteArray.u8(at: Int): Int = this[at].toInt() and BYTE

    private fun ByteArray.u16(at: Int): Int = (u8(at) shl Byte.SIZE_BITS) or u8(at + 1)

    private const val BYTE = 0xFF
    private const val MARKER = 0xFF
    private const val START_OF_IMAGE = 0xD8

    /** SOF0..SOF2: baseline, extended sequential and progressive. */
    private val FRAME_MARKERS = 0xC0..0xC2
    private const val MARKER_HEADER = 4
    private const val FRAME_HEIGHT = 5
    private const val FRAME_WIDTH = 7
    private const val FRAME_COMPONENTS = 9

    /** Objects 1 and 2 are the catalog and the page tree; each page then has three. */
    private const val PAGE_OBJECTS_START = 3
    private const val OBJECTS_PER_PAGE = 3
}
