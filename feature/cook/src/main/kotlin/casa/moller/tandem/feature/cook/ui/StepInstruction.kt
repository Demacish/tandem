package casa.moller.tandem.feature.cook.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.domain.format.needsSpokenNumbers
import casa.moller.tandem.core.domain.format.spokenNumbers
import casa.moller.tandem.core.model.timer.TimerSuggestion
import casa.moller.tandem.core.ui.markdown.Markdown
import casa.moller.tandem.core.ui.markdown.MarkdownBlock
import casa.moller.tandem.core.ui.markdown.MarkdownInline
import casa.moller.tandem.core.ui.recipe.toAnnotatedString

/**
 * A step's instruction in cook mode: the same Markdown as the detail screen (core:ui `Markdown`),
 * in the big cook-mode style, with every duration the timer detector found underlined; tapping
 * it starts that timer (plan 6.2 "Timerchips … regex på texten", wireframe 6.3).
 *
 * The tappable words are a convenience for touch: they are not separate TalkBack items, the
 * timer buttons below the text start the same timers with 64 dp targets and a full label.
 *
 * @param suggestions the step's timer suggestions; only TEXT ones have a range in [markdown].
 * @param onTimerTap the index in [suggestions] of the tapped duration.
 */
@Composable
internal fun StepInstruction(
    markdown: String,
    suggestions: List<TimerSuggestion>,
    style: TextStyle,
    onTimerTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val timerColor = MaterialTheme.colorScheme.tertiary
    val pieces = remember(markdown, linkColor, codeBackground) { renderPieces(Markdown.parse(markdown), linkColor, codeBackground) }
    val spans = remember(pieces, suggestions, markdown) {
        val matches = suggestions.mapIndexedNotNull { index, suggestion ->
            suggestion.textRange?.let { range -> TimerMatch(index, markdown.substring(range.first, range.last + 1)) }
        }
        timerSpans(pieces.map { it.text.text }, matches)
    }
    val spacing = TandemTheme.spacing
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        pieces.forEachIndexed { index, piece ->
            val pieceSpans = spans[index].orEmpty()
            val text = remember(piece, pieceSpans, timerColor) { piece.text.withTimerStyle(pieceSpans, timerColor) }
            val pieceStyle = if (piece.kind == PieceKind.HEADING) style.copy(fontWeight = FontWeight.Bold) else style
            val body: @Composable (Modifier) -> Unit = { textModifier ->
                TappableText(text = text, spans = pieceSpans, style = pieceStyle, onTimerTap = onTimerTap, modifier = textModifier)
            }
            when (piece.kind) {
                PieceKind.PARAGRAPH -> body(Modifier)
                PieceKind.HEADING -> body(Modifier.semantics { heading() })
                PieceKind.LIST_ITEM -> Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text(
                        text = piece.bullet.orEmpty(),
                        style = style,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.widthIn(min = 24.dp),
                    )
                    body(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TappableText(text: AnnotatedString, spans: List<TimerSpan>, style: TextStyle, onTimerTap: (Int) -> Unit, modifier: Modifier) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val plain = text.text
    // Typographic fractions of scaled amounts ("³⁄₁₆ tsk") are read as "3/16 tsk" (plan 6.6).
    val spoken = if (needsSpokenNumbers(plain)) spokenNumbers(plain) else null
    Text(
        text = text,
        style = style,
        onTextLayout = { layout = it },
        modifier = modifier
            .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier)
            .then(
                if (spans.isEmpty()) {
                    Modifier
                } else {
                    Modifier.pointerInput(spans) {
                        detectTapGestures { position ->
                            val offset = layout?.getOffsetForPosition(position) ?: return@detectTapGestures
                            spans.firstOrNull { offset >= it.start && offset < it.end }?.let { onTimerTap(it.suggestionIndex) }
                        }
                    }
                },
            ),
    )
}

/** Underlines the durations a timer can start from. */
private fun AnnotatedString.withTimerStyle(spans: List<TimerSpan>, color: androidx.compose.ui.graphics.Color): AnnotatedString {
    if (spans.isEmpty()) return this
    val base = this
    return buildAnnotatedString {
        append(base)
        spans.forEach { span ->
            addStyle(TimerTextStyle.copy(color = color), span.start, span.end)
        }
    }
}

private val TimerTextStyle = SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)

internal enum class PieceKind { PARAGRAPH, HEADING, LIST_ITEM }

/** One line of text the step shows: a paragraph, a heading or one list item (with its [bullet]). */
internal data class Piece(val kind: PieceKind, val text: AnnotatedString, val bullet: String? = null)

private fun renderPieces(
    blocks: List<MarkdownBlock>,
    linkColor: androidx.compose.ui.graphics.Color,
    codeBackground: androidx.compose.ui.graphics.Color,
): List<Piece> = blocks.flatMap { block ->
    when (block) {
        is MarkdownBlock.Paragraph -> listOf(Piece(PieceKind.PARAGRAPH, block.inlines.toAnnotatedString(linkColor, codeBackground)))

        is MarkdownBlock.Heading -> listOf(Piece(PieceKind.HEADING, block.inlines.toAnnotatedString(linkColor, codeBackground)))

        is MarkdownBlock.ListBlock -> block.items.mapIndexed { index, item: List<MarkdownInline> ->
            Piece(
                kind = PieceKind.LIST_ITEM,
                text = item.toAnnotatedString(linkColor, codeBackground),
                bullet = if (block.ordered) "${block.start + index}." else "•",
            )
        }
    }
}

/** A detected duration, as written in the step's Markdown source. */
internal data class TimerMatch(val suggestionIndex: Int, val text: String)

/** Where [TimerMatch] [suggestionIndex] is in the visible text of one piece: characters [start] until [end]. */
internal data class TimerSpan(val suggestionIndex: Int, val start: Int, val end: Int)

/**
 * Finds the detected durations in the visible text (Markdown markers removed), in order: each
 * match is looked for after the previous one, in the same piece or a later one. The detector
 * reads the Markdown source, so a duration that crosses a marker ("**8**–10 min") is not found
 * and simply not tappable (its timer button still exists).
 *
 * @return the spans by piece index.
 */
internal fun timerSpans(pieceTexts: List<String>, matches: List<TimerMatch>): Map<Int, List<TimerSpan>> {
    val result = mutableMapOf<Int, MutableList<TimerSpan>>()
    var piece = 0
    var offset = 0
    matches.forEach { match ->
        if (match.text.isBlank()) return@forEach
        var searchPiece = piece
        var searchOffset = offset
        while (searchPiece < pieceTexts.size) {
            val found = pieceTexts[searchPiece].indexOf(match.text, searchOffset)
            if (found >= 0) {
                result.getOrPut(searchPiece) { mutableListOf() } += TimerSpan(match.suggestionIndex, found, found + match.text.length)
                piece = searchPiece
                offset = found + match.text.length
                return@forEach
            }
            searchPiece++
            searchOffset = 0
        }
    }
    return result
}
