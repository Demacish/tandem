package casa.moller.tandem.core.domain.pantry

import java.util.Locale

/**
 * One common staple ("Basvaror", plan §8.8) with the names a Tandoor space may use for it, in
 * Swedish and English, the most common first. Without staples marked "har hemma" almost no recipe
 * "has all".
 *
 * @property key a stable id (English, lower case).
 */
data class StapleSuggestion(val key: String, val swedish: List<String>, val english: List<String>) {
    /** Every name, Swedish first. */
    val names: List<String>
        get() = swedish + english

    /** The name to show and to create a food with: Swedish for a Swedish device, else English. */
    fun displayName(language: String): String = if (language == SWEDISH) swedish.first() else english.first()

    private companion object {
        const val SWEDISH = "sv"
    }
}

/** A food of the server, for matching staples (`food` table). */
data class StapleFood(val id: Long, val name: String, val pluralName: String? = null)

/**
 * The curated list of staples the app offers the first time (plan §8.8: salt, peppar, olja,
 * vatten ...) and how a staple's name finds the server's food: the same name (without case, also
 * the plural), else another name of the same staple ("Pepper" on an English phone finds the
 * Swedish space's "svartpeppar"). Pure; the hemma-data lane's `StaplesRepository` uses it.
 */
object StapleCatalog {
    val suggestions: List<StapleSuggestion> = listOf(
        StapleSuggestion("salt", listOf("salt", "flingsalt"), listOf("salt", "sea salt")),
        StapleSuggestion("black pepper", listOf("svartpeppar", "peppar"), listOf("black pepper", "pepper")),
        StapleSuggestion("white pepper", listOf("vitpeppar"), listOf("white pepper")),
        StapleSuggestion("olive oil", listOf("olivolja"), listOf("olive oil")),
        StapleSuggestion("rapeseed oil", listOf("rapsolja", "matolja", "olja"), listOf("rapeseed oil", "vegetable oil", "oil")),
        StapleSuggestion("water", listOf("vatten"), listOf("water")),
        StapleSuggestion("sugar", listOf("strösocker", "socker"), listOf("caster sugar", "sugar", "granulated sugar")),
        StapleSuggestion("flour", listOf("vetemjöl"), listOf("plain flour", "flour", "all-purpose flour")),
        StapleSuggestion("butter", listOf("smör"), listOf("butter")),
        StapleSuggestion("vinegar", listOf("ättika", "vinäger"), listOf("vinegar")),
        StapleSuggestion("soy sauce", listOf("japansk soja", "soja", "sojasås"), listOf("soy sauce")),
        StapleSuggestion("baking powder", listOf("bakpulver"), listOf("baking powder")),
        StapleSuggestion("bicarbonate", listOf("bikarbonat"), listOf("bicarbonate of soda", "baking soda")),
        StapleSuggestion("vanilla sugar", listOf("vaniljsocker"), listOf("vanilla sugar")),
        StapleSuggestion("cinnamon", listOf("kanel"), listOf("ground cinnamon", "cinnamon")),
        StapleSuggestion("stock cube", listOf("buljongtärning", "grönsaksbuljongtärning"), listOf("stock cube", "vegetable stock cube")),
        StapleSuggestion("honey", listOf("honung", "flytande honung"), listOf("honey", "runny honey")),
    )

    /** The suggestion [name] belongs to (any of its names, without case), null for a name the list does not know. */
    fun suggestionFor(name: String): StapleSuggestion? {
        val key = normalize(name)
        if (key.isEmpty()) return null
        return suggestions.firstOrNull { suggestion -> suggestion.names.any { normalize(it) == key } }
    }

    /**
     * The id of the server food for staple [name]: a food of exactly that name (or plural) first,
     * else a food with another name of the same staple (in the suggestion's order), else null.
     */
    fun matchFood(name: String, foods: List<StapleFood>): Long? {
        val key = normalize(name)
        if (key.isEmpty()) return null
        foods.firstOrNull { normalize(it.name) == key || it.pluralName?.let(::normalize) == key }?.let { return it.id }
        val suggestion = suggestionFor(name) ?: return null
        val byName = foods.groupBy { normalize(it.name) }
        return suggestion.names.firstNotNullOfOrNull { other -> byName[normalize(other)]?.firstOrNull()?.id }
    }

    /** Lower case and trimmed, inner spaces collapsed: the key staples are compared by. */
    fun normalize(name: String): String = name.trim().replace(WHITESPACE, " ").lowercase(Locale.ROOT)

    private val WHITESPACE = Regex("\\s+")
}
