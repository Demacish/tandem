package casa.moller.tandem.core.domain.scan

/**
 * A printed receipt name ("GRÄDDE 36% 5DL", "KYCKL FILÉ 900G", "BANAN EKO 1,23 kg") to words for
 * the food search ("grädde", "kyckling filé", "banan", #44): lower case, numbers, sizes and
 * percentages removed, store abbreviations expanded and words that say nothing about the food
 * dropped (eco labels, store brands, package words). The words live in the data file
 * `receipt-words.txt` next to this class (not in code or UI), so the list can grow without code
 * changes. The learned alias always keeps the printed line ([ReceiptItem.name]); this only helps
 * the first time a line is seen.
 */
class ReceiptNormalizer(private val expansions: Map<String, String>, private val ignored: Set<String>) {
    private val separators = Regex("""[^\p{L}&'-]+""")

    /** The search words of [printed], or "" when nothing is left. */
    fun searchText(printed: String): String = printed
        .lowercase()
        .split(separators)
        .map { it.trim('-', '\'', '&') }
        .filter { it.length >= 2 }
        .mapNotNull { word ->
            val expanded = expansions[word] ?: word
            expanded.takeUnless { it in ignored }
        }
        .distinct()
        .joinToString(" ")

    companion object {
        // Absolute: R8 renames the package of this class in a minified build.
        private const val RESOURCE = "/casa/moller/tandem/core/domain/scan/receipt-words.txt"

        /** The normaliser with the bundled word list (empty lists when the file cannot be read). */
        val bundled: ReceiptNormalizer by lazy { fromText(readResource()) }

        /** Parses the word list format (see the data file's header). */
        fun fromText(text: String): ReceiptNormalizer {
            val expansions = mutableMapOf<String, String>()
            val ignored = mutableSetOf<String>()
            text.lineSequence()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .forEach { entry ->
                    when {
                        entry.startsWith("-") -> ignored += entry.removePrefix("-").trim().lowercase()

                        "=" in entry -> {
                            val (short, long) = entry.split("=", limit = 2).map { it.trim().lowercase() }
                            if (short.isNotEmpty() && long.isNotEmpty()) expansions[short] = long
                        }
                    }
                }
            return ReceiptNormalizer(expansions, ignored)
        }

        private fun readResource(): String = runCatching {
            ReceiptNormalizer::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().decodeToString() }
        }.getOrNull().orEmpty()
    }
}
