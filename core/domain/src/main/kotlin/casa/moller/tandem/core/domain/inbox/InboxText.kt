package casa.moller.tandem.core.domain.inbox

// Fas 5 (rss lane, plan §7 #35): what the recipe inbox takes from a post's HTML. The app never
// renders a feed's HTML (no WebView, no JavaScript): it keeps a short plain-text excerpt and the
// address of one image, both computed here without an HTML library.

/** Plain text and the first image of a feed post's HTML. Pure, so it is unit tested. */
object InboxText {
    /**
     * The start of [html] as plain text: scripts, styles and comments dropped, tags removed, the
     * common entities decoded, white space collapsed, cut at a word before [maxLength] with "…".
     */
    fun excerpt(html: String, maxLength: Int): String {
        if (html.isBlank() || maxLength <= 0) return ""
        val withoutBlocks = BLOCKS.replace(html.take(MAX_INPUT), " ")
        val breaks = BREAKS.replace(withoutBlocks, " ")
        val text = decode(TAG.replace(breaks, "")).replace(SPACE, " ").trim()
        if (text.length <= maxLength) return text
        val cut = text.take(maxLength - 1)
        val lastSpace = cut.lastIndexOf(' ')
        val word = if (lastSpace > maxLength / 2) cut.take(lastSpace) else cut
        return word.trimEnd(',', '.', ';', ':', ' ') + "…"
    }

    /**
     * The first image of a post: an https image enclosure first, else the first `<img src>` in
     * [html] that is an https address. Null when there is none (an `http:` image would be refused
     * by the app's no-cleartext rule anyway, plan §5.5).
     *
     * @param enclosures the post's enclosures as (url, MIME type).
     */
    fun firstImage(html: String, enclosures: List<Pair<String, String>> = emptyList()): String? {
        enclosures.firstOrNull { (url, type) -> type.startsWith("image/") && url.isHttps() }?.let { return it.first }
        return IMG.findAll(html.take(MAX_INPUT))
            .map { decode(it.groupValues[2]).trim() }
            .firstOrNull { it.isHttps() }
    }

    /** Decodes the entities feeds use most (named ones of Latin-1 that matter, and numeric ones). */
    fun decode(text: String): String = ENTITY.replace(text) { match ->
        val name = match.groupValues[1]
        when {
            name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(HEX)?.let(::codePoint)
            name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::codePoint)
            else -> NAMED[name]
        } ?: match.value
    }

    private fun codePoint(value: Int): String? =
        if (value in 1..MAX_CODE_POINT && value !in SURROGATES) String(Character.toChars(value)) else null

    private fun String.isHttps(): Boolean = startsWith("https://", ignoreCase = true) && length > "https://".length

    /** Longer posts are only read this far (the excerpt and the first image are near the start). */
    private const val MAX_INPUT = 200_000
    private const val HEX = 16
    private const val MAX_CODE_POINT = 0x10FFFF
    private val SURROGATES = 0xD800..0xDFFF

    private val BLOCKS = Regex(
        """<(script|style|noscript|template)\b[^>]*>.*?</\1\s*>|<!--.*?-->""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val BREAKS = Regex("""<(br|/p|/div|/li|/h[1-6]|/tr)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]*>""")
    private val SPACE = Regex("""\s+""")
    private val ENTITY = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});""")
    private val IMG = Regex("""<img\b[^>]*?\bsrc\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
    private val NAMED = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
        "ndash" to "–",
        "mdash" to "—",
        "hellip" to "…",
        "rsquo" to "’",
        "lsquo" to "‘",
        "rdquo" to "”",
        "ldquo" to "“",
        "aring" to "å",
        "Aring" to "Å",
        "auml" to "ä",
        "Auml" to "Ä",
        "ouml" to "ö",
        "Ouml" to "Ö",
        "eacute" to "é",
        "uuml" to "ü",
        "deg" to "°",
        "frac12" to "½",
    )
}
