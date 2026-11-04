package casa.moller.tandem.core.domain.scan

import kotlin.math.abs

/**
 * Fas 4 (textigenkänning av recept): the text of a photographed recipe (a cookbook page, a card,
 * a printout) as the on-device text import reads it (`core:sync` `TextRecipeParser`): the
 * recognised lines in reading order, one per line, with a blank line where the page has a gap
 * (a new paragraph: the name, the ingredients, the steps). Pure, so the layout is testable
 * without ML Kit; [TextFragment]s are ML Kit's lines with their boxes.
 */
object RecipeTextLayout {
    private const val SAME_ROW = 0.6f

    /** A vertical gap between rows larger than this many line heights starts a new paragraph. */
    private const val PARAGRAPH_GAP = 0.9f

    /** The page's text, or an empty string for a photo without text. */
    fun text(fragments: List<TextFragment>): String = paragraphs(fragments).joinToString("\n\n") { it.joinToString("\n") }

    /** The rows grouped into paragraphs, top to bottom; rows read left to right. */
    fun paragraphs(fragments: List<TextFragment>): List<List<String>> {
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
        val paragraphs = mutableListOf<MutableList<String>>()
        var previousBottom: Int? = null
        for (row in rows) {
            val top = row.minOf { it.top }
            val gap = previousBottom?.let { top - it } ?: 0
            if (paragraphs.isEmpty() || gap > lineHeight * PARAGRAPH_GAP) paragraphs += mutableListOf()
            paragraphs.last() += row.sortedBy { it.left }.joinToString(" ") { it.text.trim() }
            previousBottom = row.maxOf { it.bottom }
        }
        return paragraphs
    }

    /** Several photos of one recipe (a long page, a spread), in order, as one text. */
    fun join(pages: List<String>): String = pages.filter { it.isNotBlank() }.joinToString("\n\n")
}
