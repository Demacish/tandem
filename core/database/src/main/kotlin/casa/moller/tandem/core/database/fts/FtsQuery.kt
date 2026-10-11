package casa.moller.tandem.core.database.fts

/**
 * Turns what the user typed into safe FTS4 `MATCH` terms.
 *
 * The text is split into words the same way the `unicode61` tokenizer splits (letters, combining
 * marks and digits form words, everything else separates), and every word becomes a quoted
 * prefix term: `svamp` -> `"svamp*"`. Quoting means FTS operators typed by the user (`OR`,
 * `NEAR`, `-`, `"`, `*`) are plain words, so no input can make the query fail. Case and
 * diacritics are folded by the tokenizer itself (`remove_diacritics=2`): `kram` matches `Krämig`.
 */
object FtsQuery {
    /** At most this many words are used; more only slow the query down. */
    const val MAX_TERMS: Int = 8

    private val separators = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    /** The searchable words of [text] (at most [MAX_TERMS]), in input order. */
    fun terms(text: String): List<String> = text.split(separators).filter { it.isNotEmpty() }.take(MAX_TERMS)

    /** The `MATCH` expression for one word: a quoted prefix term. */
    fun prefixTerm(term: String): String = "\"$term*\""

    /** A `MATCH` expression requiring every word of [text] as a prefix, or null when it has no word. */
    fun build(text: String): String? = terms(text).takeIf { it.isNotEmpty() }?.joinToString(" ") { prefixTerm(it) }
}
