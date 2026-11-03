package casa.moller.tandem.core.sync.recipeimport

import casa.moller.tandem.core.domain.shopping.LocalIngredientParser
import casa.moller.tandem.core.model.recipe.DraftIngredient
import casa.moller.tandem.core.model.recipe.DraftStep
import casa.moller.tandem.core.model.recipe.RecipeDraft

/**
 * Fas 3 (import lane): a recipe pasted or shared as TEXT, read on the device (Tandoor's
 * `recipe-from-source` answers 500 for plain text, FACTS "Fas 3 e"; and a text needs no server,
 * so it works offline and in demo mode). Heuristics, meant to be checked in the editor:
 *
 * - the first line is the name; "4 portioner"/"för 6 personer" anywhere sets the servings;
 * - a short line that core:domain's [LocalIngredientParser] reads with an amount or a unit, or a
 *   short line without sentence punctuation under an "Ingredienser" heading, is an ingredient;
 *   a heading line ("Ingredienser", "Gör så här:", "Tillagning") switches the section and is dropped;
 * - every other paragraph (blank line, or a numbered line) is a step; the ingredients go on the
 *   first step, as Tandoor's own import does.
 */
internal object TextRecipeParser {
    /** The draft [text] stands for, or null when it has no first line to be the name. */
    fun parse(text: String, knownUnits: Collection<String> = emptyList()): RecipeDraft? {
        val lines = text.lines().map { it.trim() }
        val name = lines.firstOrNull { it.isNotEmpty() }?.take(MAX_NAME) ?: return null
        val rest = lines.drop(lines.indexOfFirst { it.isNotEmpty() } + 1)
        val ingredients = mutableListOf<DraftIngredient>()
        val paragraphs = mutableListOf<MutableList<String>>()
        var section = Section.UNKNOWN
        var servings = 0
        for (line in rest) {
            if (line.isEmpty()) {
                if (paragraphs.lastOrNull()?.isNotEmpty() == true) paragraphs += mutableListOf()
                continue
            }
            SERVINGS.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { if (servings == 0) servings = it }
            val heading = headingOf(line)
            if (heading != null) {
                section = heading
                continue
            }
            val numbered = NUMBERED.matchEntire(line)
            val isIngredient = section != Section.STEPS && numbered == null && looksLikeIngredient(line, knownUnits, section)
            if (isIngredient) {
                ingredients += ingredientOf(line, knownUnits)
            } else {
                val content = numbered?.groupValues?.get(1) ?: line
                if (numbered != null || paragraphs.isEmpty()) paragraphs += mutableListOf()
                paragraphs.last() += content
            }
        }
        val steps = paragraphs.filter { it.isNotEmpty() }.map { DraftStep(instruction = it.joinToString(" ")) }
        return RecipeDraft(
            name = name,
            servings = servings.coerceAtLeast(1),
            steps = when {
                steps.isEmpty() -> listOf(DraftStep(ingredients = ingredients))
                else -> listOf(steps.first().copy(ingredients = ingredients)) + steps.drop(1)
            },
        ).normalized()
    }

    private fun looksLikeIngredient(line: String, knownUnits: Collection<String>, section: Section): Boolean {
        if (line.length > MAX_INGREDIENT_LINE) return false
        val parsed = LocalIngredientParser.parse(line, knownUnits)
        if (parsed != null && (parsed.amount > 0 || parsed.unitName != null)) return true
        val shortPlain = !line.endsWith('.') && !line.endsWith('!') && line.count { it == ' ' } < MAX_INGREDIENT_WORDS
        return section == Section.INGREDIENTS && shortPlain
    }

    private fun ingredientOf(line: String, knownUnits: Collection<String>): DraftIngredient {
        val parsed = LocalIngredientParser.parse(line, knownUnits)
        return if (parsed == null || parsed.foodName.isBlank()) {
            DraftIngredient(food = line, noAmount = true)
        } else {
            DraftIngredient(
                amount = parsed.amount,
                unit = parsed.unitName,
                food = parsed.foodName,
                note = parsed.note,
                noAmount = parsed.amount <= 0,
            )
        }
    }

    private fun headingOf(line: String): Section? {
        val bare = line.trimEnd(':').lowercase()
        if (bare.length > MAX_HEADING) return null
        return when {
            INGREDIENT_HEADINGS.any { bare.startsWith(it) } -> Section.INGREDIENTS
            STEP_HEADINGS.any { bare.startsWith(it) } -> Section.STEPS
            else -> null
        }
    }

    private enum class Section { UNKNOWN, INGREDIENTS, STEPS }

    private const val MAX_NAME = 128
    private const val MAX_HEADING = 24
    private const val MAX_INGREDIENT_LINE = 60
    private const val MAX_INGREDIENT_WORDS = 6
    private val SERVINGS = Regex("""(\d{1,2})\s*(?:portion|pers|serving|bitar|st\b)""", RegexOption.IGNORE_CASE)
    private val NUMBERED = Regex("""^\s*(?:\d{1,2}[.)]|steg\s+\d{1,2}[.:]?)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val INGREDIENT_HEADINGS = listOf("ingredienser", "ingredients", "du behöver", "det här behöver du")
    private val STEP_HEADINGS =
        listOf("gör så här", "gör såhär", "tillagning", "instruktioner", "instructions", "method", "directions", "så här gör du")
}
