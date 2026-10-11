package casa.moller.tandem.core.domain.markdown

/**
 * A small Markdown reader for Tandoor step instructions (plan 6.2 "Receptdetalj", feature #4).
 *
 * Why our own and not a library: step texts use a tiny subset of Markdown (bold, italic,
 * lists, the odd heading or link). A full CommonMark renderer for Compose would add a parser
 * and a renderer with their own release cadence on top of a pre-release Material 3, for text
 * that is a few lines long. This reader is pure Kotlin (KMP-friendly, decision B2), never
 * throws, and only knows what recipes need:
 *
 * - **Blocks:** paragraphs, ATX headings (`#` to `######`), bullet lists (`-`, `*`, `+`) and
 *   ordered lists (`1.` / `1)`). A list item continues on indented lines. Block quotes (`>`) are
 *   read as paragraphs, thematic breaks (`---`) are dropped, everything else is a paragraph.
 * - **Line breaks:** a single newline inside a paragraph is kept as a [MarkdownInline.LineBreak]
 *   (recipe authors write one instruction per line and expect to see it that way, like
 *   Tandoor's web view), so is `<br>`.
 * - **Inline:** `**strong**` / `__strong__`, `*emphasis*` / `_emphasis_` (underscores only at
 *   word boundaries, so `snake_case` stays text), `` `code` ``, `[text](url)`, `<https://…>`
 *   and bare `https://` URLs, `\` escapes. Images (`![alt](url)`) become their alt text.
 * - **Links:** only `http`, `https` and `mailto` targets become [MarkdownInline.Link]; other
 *   schemes (`javascript:`, `intent:`, `file:`) stay plain text.
 *
 * Tandoor templates (`{{ scale(100) }}`) are not Markdown; render them before parsing.
 */
object Markdown {

    /** Parses [text] into blocks. Blank input gives an empty list. */
    fun parse(text: String): List<MarkdownBlock> = BlockParser(text.replace("\r\n", "\n").replace('\r', '\n')).parse()

    /** Parses one line of inline Markdown (no blocks). */
    fun parseInline(text: String): List<MarkdownInline> = InlineParser(text).parse()

    /** The text of [inlines] without any formatting, e.g. for TalkBack or tests. */
    fun plainText(inlines: List<MarkdownInline>): String = buildString { inlines.forEach { appendPlain(it) } }

    /** The text of [blocks] without formatting; blocks and list items are separated by newlines. */
    fun plainText(blocks: List<MarkdownBlock>): String = blocks.joinToString("\n") { block ->
        when (block) {
            is MarkdownBlock.Paragraph -> plainText(block.inlines)
            is MarkdownBlock.Heading -> plainText(block.inlines)
            is MarkdownBlock.ListBlock -> block.items.joinToString("\n") { plainText(it) }
        }
    }

    private fun StringBuilder.appendPlain(inline: MarkdownInline) {
        when (inline) {
            is MarkdownInline.Text -> append(inline.text)
            is MarkdownInline.Code -> append(inline.text)
            is MarkdownInline.Strong -> inline.children.forEach { appendPlain(it) }
            is MarkdownInline.Emphasis -> inline.children.forEach { appendPlain(it) }
            is MarkdownInline.Link -> inline.children.forEach { appendPlain(it) }
            MarkdownInline.LineBreak -> append('\n')
        }
    }

    internal fun isSafeLink(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("mailto:")
    }
}

/** A block of a Markdown text. */
sealed interface MarkdownBlock {
    /** A paragraph; may contain [MarkdownInline.LineBreak]s. */
    data class Paragraph(val inlines: List<MarkdownInline>) : MarkdownBlock

    /** A heading, [level] 1 (largest) to 6. */
    data class Heading(val level: Int, val inlines: List<MarkdownInline>) : MarkdownBlock

    /** A bullet list ([ordered] false) or a numbered list starting at [start]. */
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<List<MarkdownInline>>) : MarkdownBlock
}

/** Inline content of a block. */
sealed interface MarkdownInline {
    data class Text(val text: String) : MarkdownInline

    data class Strong(val children: List<MarkdownInline>) : MarkdownInline

    data class Emphasis(val children: List<MarkdownInline>) : MarkdownInline

    data class Code(val text: String) : MarkdownInline

    /** A link to [url] (http, https or mailto only). */
    data class Link(val url: String, val children: List<MarkdownInline>) : MarkdownInline

    data object LineBreak : MarkdownInline
}

private val headingRegex = Regex("^ {0,3}(#{1,6})(?:[ \t]+(.*?))?[ \t#]*$")
private val bulletRegex = Regex("^ {0,3}[-*+][ \t]+(.*)$")
private val orderedRegex = Regex("^ {0,3}(\\d{1,9})[.)][ \t]+(.*)$")
private val thematicBreakRegex = Regex("^ {0,3}([-*_])([ \t]*\\1){2,}[ \t]*$")
private val quoteRegex = Regex("^ {0,3}>[ \t]?(.*)$")

private class BlockParser(text: String) {
    private val lines = text.split('\n')
    private val blocks = mutableListOf<MarkdownBlock>()
    private val paragraph = mutableListOf<String>()
    private var listOrdered = false
    private var listStart = 1
    private val listItems = mutableListOf<MutableList<String>>()

    fun parse(): List<MarkdownBlock> {
        for (line in lines) {
            parseLine(line)
        }
        flushParagraph()
        flushList()
        return blocks
    }

    private fun parseLine(raw: String) {
        val line = raw.trimEnd()
        if (line.isBlank()) {
            flushParagraph()
            flushList()
            return
        }
        if (thematicBreakRegex.matches(line) && paragraph.isEmpty()) {
            flushList()
            return
        }
        headingRegex.matchEntire(line)?.let { match ->
            flushParagraph()
            flushList()
            val content = match.groupValues[2].trim()
            if (content.isNotEmpty()) blocks += MarkdownBlock.Heading(match.groupValues[1].length, Markdown.parseInline(content))
            return
        }
        bulletRegex.matchEntire(line)?.let { match ->
            startItem(ordered = false, start = 1, content = match.groupValues[1])
            return
        }
        orderedRegex.matchEntire(line)?.let { match ->
            startItem(ordered = true, start = match.groupValues[1].toIntOrNull() ?: 1, content = match.groupValues[2])
            return
        }
        if (listItems.isNotEmpty() && (raw.startsWith(" ") || raw.startsWith("\t"))) {
            listItems.last() += line.trim()
            return
        }
        flushList()
        val quote = quoteRegex.matchEntire(line)
        paragraph += (quote?.groupValues?.get(1) ?: line).trim()
    }

    private fun startItem(ordered: Boolean, start: Int, content: String) {
        flushParagraph()
        if (listItems.isNotEmpty() && listOrdered != ordered) flushList()
        if (listItems.isEmpty()) {
            listOrdered = ordered
            listStart = start
        }
        listItems += mutableListOf(content.trim())
    }

    private fun flushParagraph() {
        if (paragraph.isEmpty()) return
        val inlines = mutableListOf<MarkdownInline>()
        paragraph.forEachIndexed { index, line ->
            if (index > 0) inlines += MarkdownInline.LineBreak
            inlines += Markdown.parseInline(line)
        }
        blocks += MarkdownBlock.Paragraph(inlines.mergeText())
        paragraph.clear()
    }

    private fun flushList() {
        if (listItems.isEmpty()) return
        blocks += MarkdownBlock.ListBlock(
            ordered = listOrdered,
            start = listStart,
            items = listItems.map { lines -> Markdown.parseInline(lines.joinToString(" ")) },
        )
        listItems.clear()
    }
}

private class InlineParser(private val text: String) {
    private var pos = 0

    fun parse(): List<MarkdownInline> = parseUntil(null).mergeText()

    /** Parses until [closing] (not consumed by this function) or the end. */
    private fun parseUntil(closing: String?): List<MarkdownInline> {
        val result = mutableListOf<MarkdownInline>()
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) {
                result += MarkdownInline.Text(buffer.toString())
                buffer.clear()
            }
        }
        while (pos < text.length) {
            if (closing != null && text.startsWith(closing, pos) && closesHere(closing)) return result.also { flush() }
            val c = text[pos]
            val parsed: MarkdownInline? = when {
                c == '\\' && pos + 1 < text.length && text[pos + 1] in ESCAPABLE -> {
                    buffer.append(text[pos + 1])
                    pos += 2
                    continue
                }

                c == '`' -> code()

                (c == '*' || c == '_') && text.startsWith("$c$c", pos) -> delimited("$c$c") { MarkdownInline.Strong(it) }

                c == '*' || c == '_' -> delimited("$c") { MarkdownInline.Emphasis(it) }

                c == '!' && text.startsWith("![", pos) -> image()

                c == '[' -> link()

                c == '<' -> angle()

                (c == 'h' || c == 'H') && atWordStart() -> bareUrl()

                else -> null
            }
            if (parsed != null) {
                flush()
                result += parsed
            } else {
                buffer.append(c)
                pos++
            }
        }
        flush()
        return result
    }

    private fun closesHere(closing: String): Boolean {
        if (closing.first() != '_') return pos > 0 && !text[pos - 1].isWhitespace()
        val after = pos + closing.length
        return pos > 0 && !text[pos - 1].isWhitespace() && (after >= text.length || !text[after].isLetterOrDigit())
    }

    private fun atWordStart(): Boolean = pos == 0 || !text[pos - 1].isLetterOrDigit()

    private fun code(): MarkdownInline? {
        val end = text.indexOf('`', pos + 1)
        if (end < 0) return null
        val content = text.substring(pos + 1, end)
        pos = end + 1
        return MarkdownInline.Code(content)
    }

    private fun delimited(delimiter: String, build: (List<MarkdownInline>) -> MarkdownInline): MarkdownInline? {
        val start = pos
        val after = start + delimiter.length
        // An opener needs text right after it; `_` also needs a word boundary before it.
        if (after >= text.length || text[after].isWhitespace()) return null
        if (delimiter.first() == '_' && start > 0 && text[start - 1].isLetterOrDigit()) return null
        if (!hasCloser(delimiter, after)) return null
        pos = after
        val children = parseUntil(delimiter)
        if (pos >= text.length || !text.startsWith(delimiter, pos)) {
            pos = start
            return null
        }
        pos += delimiter.length
        return build(children.mergeText())
    }

    private fun hasCloser(delimiter: String, from: Int): Boolean {
        var index = text.indexOf(delimiter, from)
        while (index >= 0) {
            if (!text[index - 1].isWhitespace()) return true
            index = text.indexOf(delimiter, index + 1)
        }
        return false
    }

    private fun image(): MarkdownInline? {
        val closeBracket = matchingBracket(pos + 1) ?: return null
        val target = linkTarget(closeBracket + 1) ?: return null
        val alt = text.substring(pos + 2, closeBracket)
        pos = target.second
        return MarkdownInline.Text(alt)
    }

    private fun link(): MarkdownInline? {
        val closeBracket = matchingBracket(pos) ?: return null
        val target = linkTarget(closeBracket + 1) ?: return null
        val label = text.substring(pos + 1, closeBracket)
        val children = InlineParser(label).parse()
        pos = target.second
        return if (Markdown.isSafeLink(target.first)) MarkdownInline.Link(target.first, children) else MarkdownInline.Text(label)
    }

    /** Index of the `]` that closes the `[` at [open], or null. */
    private fun matchingBracket(open: Int): Int? {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i++
                '[' -> depth++
                ']' -> if (--depth == 0) return i
            }
            i++
        }
        return null
    }

    /** `(url "title")` at [start]: the url and the index after `)`. */
    private fun linkTarget(start: Int): Pair<String, Int>? {
        if (start >= text.length || text[start] != '(') return null
        // Balanced parentheses, so `(https://example.com/a_(b))` keeps its last `)`.
        var depth = 0
        var end = -1
        for (i in start until text.length) {
            if (text[i] == '(') depth++
            if (text[i] == ')' && --depth == 0) {
                end = i
                break
            }
        }
        if (end < 0) return null
        val inside = text.substring(start + 1, end).trim()
        val url = inside.substringBefore(' ').removePrefix("<").removeSuffix(">")
        return url to end + 1
    }

    private fun angle(): MarkdownInline? {
        val end = text.indexOf('>', pos + 1)
        if (end < 0) return null
        val inside = text.substring(pos + 1, end)
        val tag = inside.trim().lowercase()
        return when {
            tag == "br" || tag == "br/" || tag == "br /" -> {
                pos = end + 1
                MarkdownInline.LineBreak
            }

            Markdown.isSafeLink(inside) && inside.none { it.isWhitespace() } -> {
                pos = end + 1
                MarkdownInline.Link(inside, listOf(MarkdownInline.Text(inside)))
            }

            else -> null
        }
    }

    private fun bareUrl(): MarkdownInline? {
        val rest = text.substring(pos)
        val lower = rest.lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return null
        var end = rest.indexOfFirst { it.isWhitespace() || it == '<' }
        if (end < 0) end = rest.length
        // Trailing punctuation belongs to the sentence, not the URL.
        while (end > 0 && rest[end - 1] in TRAILING_PUNCTUATION) end--
        val url = rest.substring(0, end)
        if (url.length <= "https://".length) return null
        pos += end
        return MarkdownInline.Link(url, listOf(MarkdownInline.Text(url)))
    }

    private companion object {
        const val ESCAPABLE = "\\`*_{}[]()#+-.!<>|~"
        const val TRAILING_PUNCTUATION = ".,;:!?)'\""
    }
}

/** Joins neighbouring [MarkdownInline.Text] nodes. */
private fun List<MarkdownInline>.mergeText(): List<MarkdownInline> {
    val merged = mutableListOf<MarkdownInline>()
    for (inline in this) {
        val last = merged.lastOrNull()
        if (inline is MarkdownInline.Text && last is MarkdownInline.Text) {
            merged[merged.lastIndex] = MarkdownInline.Text(last.text + inline.text)
        } else {
            merged += inline
        }
    }
    return merged
}
