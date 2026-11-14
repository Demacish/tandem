package casa.moller.tandem.core.testing.accessibility

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult

/**
 * Text never breaks inside a word (docs/design/README.md "Textstorlek och radbrytning"; the device
 * test of 2026-11-14 found "Ingredien/ser" one letter per line, "3 recept" squeezed to 16 dp and
 * "Synkar med Tandoor…" broken mid-word at font scale 2.0 on a 320 dp phone, B18 and B22).
 *
 * Fails when a Text showing exactly one of [texts] ends a line inside a word, or is cut with an
 * ellipsis: every line but the last must end at a space or after a hyphen, a slash or a dash. A
 * text with no node (scrolled away, hidden from TalkBack with clearAndSetSemantics) fails too, so
 * a test never passes by looking at nothing.
 */
fun SemanticsNodeInteractionsProvider.assertNoWordBreaks(vararg texts: String) {
    texts.forEach { text ->
        val nodes = onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes()
        check(nodes.isNotEmpty()) { "No text \"$text\" on the screen" }
        nodes.forEach { node ->
            val layout = node.textLayout() ?: error("\"$text\" has no text layout")
            assertWholeWords(text, layout)
        }
    }
}

private fun SemanticsNode.textLayout(): TextLayoutResult? {
    val results = mutableListOf<TextLayoutResult>()
    config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
    return results.firstOrNull()
}

private fun assertWholeWords(text: String, layout: TextLayoutResult) {
    val content = layout.layoutInput.text.text
    for (line in 0 until layout.lineCount) {
        check(!layout.isLineEllipsized(line)) { "\"$text\" is cut short on line ${line + 1}" }
        if (line == layout.lineCount - 1) continue
        val end = layout.getLineEnd(line)
        val before = content.getOrNull(end - 1)
        val after = content.getOrNull(end)
        val atWordEnd = before == null || before.isWhitespace() || before in BREAK_AFTER || after == null || after.isWhitespace()
        check(atWordEnd) { "\"$text\" breaks inside a word: \"${content.substring(0, end)}|${content.substring(end)}\"" }
    }
}

/** Characters a line may end after without breaking a word. */
private val BREAK_AFTER = setOf('-', '/', '–', '—', '·', ',')
