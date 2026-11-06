package casa.moller.tandem.core.domain.scan

import kotlin.math.abs

private val AMOUNT_ONLY = Regex("^[\\d½¼¾⅓⅔⅛.,/–-]+\\s*\\p{L}{0,5}\\.?$")

/**
 * Fas 4 (textigenkänning av recept): the text of a photographed recipe (a cookbook page, a card,
 * a printout) as the on-device text import reads it (`core:sync` `TextRecipeParser`): the
 * recognised lines in reading order, one per line, with a blank line where the page has a gap
 * (a new paragraph: the name, the ingredients, the steps). Pure, so the layout is testable
 * without ML Kit; [TextFragment]s are ML Kit's lines with their boxes.
 *
 * Fas 4 review (fix-data): a cookbook page in two columns (ingredients left, steps right) is read
 * column by column. A column break is an empty vertical band wider than about a line height with
 * at least [MIN_COLUMN_LINES] lines on each side that share rows. Lines that cross the band (the
 * title, a note under both columns) stay where they are as full-width sections. An ingredient
 * table with the amounts in a column of their own ("2,5 dl", "vetemjöl") is no break: its rows
 * belong together. A page without a break reads exactly as before.
 */
object RecipeTextLayout {
    private const val SAME_ROW = 0.6f

    /** A vertical gap between rows larger than this many line heights starts a new paragraph. */
    private const val PARAGRAPH_GAP = 0.9f

    /** The empty band between two columns is at least this many line heights wide. */
    private const val COLUMN_GAP = 0.8f

    /** A column has at least this many lines ... */
    private const val MIN_COLUMN_LINES = 3

    /** ... and at least this many of the left column's lines have a line of the right one beside them. */
    private const val MIN_SHARED_ROWS = 2

    /** The page's text, or an empty string for a photo without text. */
    fun text(fragments: List<TextFragment>): String = paragraphs(fragments).joinToString("\n\n") { it.joinToString("\n") }

    /** The rows grouped into paragraphs, top to bottom; rows read left to right. */
    fun paragraphs(fragments: List<TextFragment>): List<List<String>> {
        val pieces = fragments.filter { it.text.isNotBlank() }
        if (pieces.isEmpty()) return emptyList()
        val lineHeight = pieces.map { it.height }.sorted()[pieces.size / 2].toFloat()
        return sections(pieces, lineHeight).flatMap { paragraphsOf(it, lineHeight) }
    }

    /** [pieces] in reading order: top to bottom, each band between full-width lines column by column. */
    private fun sections(pieces: List<TextFragment>, lineHeight: Float): List<List<TextFragment>> {
        val columnBreak = columnBreak(pieces, lineHeight) ?: return listOf(pieces)
        val sections = mutableListOf<List<TextFragment>>()
        val wide = mutableListOf<TextFragment>()
        val band = mutableListOf<TextFragment>()

        fun flushWide() {
            if (wide.isNotEmpty()) sections += wide.toList()
            wide.clear()
        }

        fun flushBand() {
            val (left, right) = band.partition { it.right <= columnBreak }
            if (left.isNotEmpty()) sections += left
            if (right.isNotEmpty()) sections += right
            band.clear()
        }

        for (piece in pieces.sortedBy { it.centerY }) {
            if (piece.left <= columnBreak && piece.right > columnBreak) {
                flushBand()
                wide += piece
            } else {
                flushWide()
                band += piece
            }
        }
        flushWide()
        flushBand()
        return sections
    }

    /** The right edge of the left column, or null for a page without columns (see the class). */
    private fun columnBreak(pieces: List<TextFragment>, lineHeight: Float): Int? = pieces.map { it.right }.distinct()
        .mapNotNull { edge -> ColumnSplit.at(edge, pieces) }
        .filter { split -> split.gap >= lineHeight * COLUMN_GAP && split.holds(lineHeight) }
        .minWithOrNull(compareBy<ColumnSplit> { it.crossing }.thenByDescending { it.gap })
        ?.edge

    /** The page cut at [edge]: the lines wholly on each side, how many cross it, and the empty [gap] after it. */
    private class ColumnSplit(
        val edge: Int,
        val left: List<TextFragment>,
        val right: List<TextFragment>,
        val crossing: Int,
        val gap: Int,
    ) {
        fun holds(lineHeight: Float): Boolean {
            if (left.size < MIN_COLUMN_LINES || right.size < MIN_COLUMN_LINES) return false
            if (crossing * 2 > left.size + right.size || isAmountColumn(left) || isAmountColumn(right)) return false
            val shared = left.count { line -> right.any { abs(it.centerY - line.centerY) <= lineHeight * SAME_ROW } }
            return shared >= MIN_SHARED_ROWS
        }

        private fun isAmountColumn(lines: List<TextFragment>): Boolean =
            lines.count { AMOUNT_ONLY.matches(it.text.trim()) } * 2 >= lines.size

        companion object {
            fun at(edge: Int, pieces: List<TextFragment>): ColumnSplit? {
                val right = pieces.filter { it.left > edge }
                val start = right.minOfOrNull { it.left } ?: return null
                return ColumnSplit(
                    edge = edge,
                    left = pieces.filter { it.right <= edge },
                    right = right,
                    crossing = pieces.count { it.left <= edge && it.right > edge },
                    gap = start - edge,
                )
            }
        }
    }

    private fun paragraphsOf(pieces: List<TextFragment>, lineHeight: Float): List<List<String>> {
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
            if (paragraphs.isEmpty() || gap > lineHeight * PARAGRAPH_GAP) paragraphs.add(mutableListOf())
            paragraphs.last() += row.sortedBy { it.left }.joinToString(" ") { it.text.trim() }
            previousBottom = row.maxOf { it.bottom }
        }
        return paragraphs
    }

    /** Several photos of one recipe (a long page, a spread), in order, as one text. */
    fun join(pages: List<String>): String = pages.filter { it.isNotBlank() }.joinToString("\n\n")
}
