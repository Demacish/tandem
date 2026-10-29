package casa.moller.tandem.core.domain.scan

import casa.moller.tandem.core.model.scan.ReceiptLine
import kotlin.math.abs

/**
 * One piece of recognised text with its box on the photo, in pixels (ML Kit `Text.Line` on the
 * device, #44). A receipt's item name and its price are often recognised as separate pieces.
 */
data class TextFragment(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int
        get() = (bottom - top).coerceAtLeast(1)

    val centerY: Float
        get() = (top + bottom) / 2f
}

/**
 * Receipt photos to lines in reading order (#44): the recognised pieces of one photo are grouped
 * into rows by geometry (pieces whose vertical centres are within about half a line height), each
 * row read left to right and joined with two spaces; several photos of one long receipt are joined
 * in order, and the rows a photo repeats from the end of the previous one (the overlap) are dropped.
 * Pure: no Android.
 */
object ReceiptLayout {
    private const val SAME_ROW = 0.6f
    private const val MAX_OVERLAP = 8
    private val spaces = Regex("""\s+""")

    /** The rows of one photo, top to bottom. */
    fun rows(fragments: List<TextFragment>): List<String> {
        val pieces = fragments.filter { it.text.isNotBlank() }
        if (pieces.isEmpty()) return emptyList()
        val lineHeight = pieces.map { it.height }.sorted()[pieces.size / 2].toFloat()
        val rows = mutableListOf<MutableList<TextFragment>>()
        for (piece in pieces.sortedBy { it.centerY }) {
            val row = rows.lastOrNull()
            val rowCenter = row?.let { list -> list.sumOf { it.centerY.toDouble() }.toFloat() / list.size }
            if (row != null && rowCenter != null && abs(piece.centerY - rowCenter) <= lineHeight * SAME_ROW) {
                row += piece
            } else {
                rows += mutableListOf(piece)
            }
        }
        return rows.map { row -> row.sortedBy { it.left }.joinToString("  ") { it.text.trim() } }
    }

    /** The lines of a receipt photographed as [pages] (rows of each photo, in order). */
    fun join(pages: List<List<String>>): List<ReceiptLine> {
        val joined = mutableListOf<String>()
        for (page in pages) {
            val overlap = (minOf(MAX_OVERLAP, joined.size, page.size) downTo 1).firstOrNull { k ->
                joined.takeLast(k).map(::key) == page.take(k).map(::key)
            } ?: 0
            joined += page.drop(overlap)
        }
        return joined.mapIndexed { index, text -> ReceiptLine(index, text) }
    }

    private fun key(text: String): String = text.replace(spaces, " ").trim().lowercase()
}
