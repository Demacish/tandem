package casa.moller.tandem.core.domain.recipe

import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.scaling.PluralRule
import casa.moller.tandem.core.domain.scaling.ScaleIngredients
import casa.moller.tandem.core.domain.scaling.WebAmountFormat
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.Ingredient
import kotlin.math.abs

/**
 * A step instruction with its templates rendered.
 *
 * @property text Markdown, ready for the Markdown reader: the instruction with every template
 *   replaced, or the instruction unchanged when [hasBrokenTemplate].
 * @property hasBrokenTemplate the instruction has template syntax Tandoor cannot parse (an
 *   unclosed `{{`, `{{ scale( }}`). Tandoor then shows "Could not parse template code" instead of
 *   the step; the app shows the instruction as written (FACTS.md "Fas 1 d").
 */
data class RenderedInstruction(val text: String, val hasBrokenTemplate: Boolean = false)

/**
 * Renders Tandoor's step templates in the raw instruction (plan 8.1, FACTS.md "Fas 1 d" and
 * "Fas 1 l" 5), with the same numbers and names as Tandoor's web, golden-tested against
 * `scaling-golden.json`:
 *
 * - `{{ scale(100) }}`: the number × factor, formatted like ingredient amounts ("150", "1 ½").
 * - `{{ ingredients[i] }}`: the step's i-th ingredient (in `order`) as `amount unit food`. The
 *   amount is scaled; the unit is fixed text, plural when the recipe's amount is not 1 (the
 *   server writes it before scaling); the food is singular/plural for the scaled amount.
 *   `no_amount` rows have no amount and a singular food.
 * - `{{ ingredients[i].amount }}`, `.unit`, `.food`, `.note`: the parts on their own.
 *
 * The server knows ingredient amounts with full precision but sends them rounded to 4 decimals
 * (`0.6667`); when the row's first `conversions` entry is the row itself it carries the precise
 * value, which templates use (the ingredient table, like the web's, uses the sent amount).
 *
 * Never throws. A template it does not know (`{{ foo }}`, `{{ ingredients[9] }}` on a step with
 * fewer ingredients, filters, `{% … %}` blocks) stays as written, so the author can see it.
 * Inserted texts are Markdown-escaped, so a food called `*special*` stays literal.
 */
object TemplateRenderer {
    private const val OPEN = "{{"
    private const val CLOSE = "}}"

    /** Precision of the amounts in the API's JSON (4 decimals). */
    private const val JSON_ROUNDING = 0.00005

    private val scaleCall = Regex("""scale\(\s*([+-]?[0-9]+(?:\.[0-9]*)?(?:[eE][+-]?[0-9]+)?)\s*\)""")
    private val ingredientRef = Regex("""ingredients\s*\[\s*([0-9]+)\s*]\s*(?:\.\s*(amount|unit|food|note))?""")
    private const val MARKDOWN_SPECIAL = "\\`*_[]<>"

    /**
     * Renders [instruction].
     *
     * @param stepIngredients the step's ingredients; indexed in `order` like the server does.
     * @param factor `ServingsScaling.factor`.
     * @param preferences the user's `use_fractions` and `ingredient_decimals`.
     * @param style how numbers are written ([NumberStyle.Web] for exact web text).
     */
    fun render(
        instruction: String,
        stepIngredients: List<Ingredient>,
        factor: Double,
        preferences: UserPreferences,
        style: NumberStyle,
    ): RenderedInstruction {
        if (!instruction.contains(OPEN)) return RenderedInstruction(instruction)
        val ingredients = stepIngredients.sortedBy { it.order }
        val out = StringBuilder(instruction.length + 32)
        var position = 0
        while (position < instruction.length) {
            val start = instruction.indexOf(OPEN, position)
            if (start < 0) {
                out.append(instruction, position, instruction.length)
                break
            }
            val end = instruction.indexOf(CLOSE, start + OPEN.length)
            if (end < 0) return RenderedInstruction(instruction, hasBrokenTemplate = true)
            out.append(instruction, position, start)
            val code = instruction.substring(start + OPEN.length, end).trim()
            if (isSyntaxError(code)) return RenderedInstruction(instruction, hasBrokenTemplate = true)
            val rendered = expression(code, ingredients, factor, preferences, style)
            out.append(rendered ?: instruction.substring(start, end + CLOSE.length))
            position = end + CLOSE.length
        }
        return RenderedInstruction(out.toString())
    }

    /** The text for [code], or null when it is not a template the app knows. */
    private fun expression(
        code: String,
        ingredients: List<Ingredient>,
        factor: Double,
        preferences: UserPreferences,
        style: NumberStyle,
    ): String? {
        scaleCall.matchEntire(code)?.let { match ->
            val number = match.groupValues[1].toDoubleOrNull() ?: return null
            return amountText(number, factor, preferences, style)
        }
        val ref = ingredientRef.matchEntire(code) ?: return null
        val ingredient = ref.groupValues[1].toIntOrNull()?.let { ingredients.getOrNull(it) } ?: return null
        val part = IngredientPart.of(ref.groupValues[2])
        return ingredientText(ingredient, part, factor, preferences, style)
    }

    private enum class IngredientPart {
        ALL,
        AMOUNT,
        UNIT,
        FOOD,
        NOTE,
        ;

        companion object {
            fun of(name: String): IngredientPart = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: ALL
        }
    }

    private fun ingredientText(
        ingredient: Ingredient,
        part: IngredientPart,
        factor: Double,
        preferences: UserPreferences,
        style: NumberStyle,
    ): String {
        if (ingredient.isHeader) return escape(ingredient.note.orEmpty().trim())
        val amount = preciseAmount(ingredient)
        val amountPart = if (ingredient.noAmount) "" else amountText(amount, factor, preferences, style)
        val unit = ingredient.unit
        val unitText = when {
            unit == null -> ""
            amount != 1.0 && !unit.pluralName.isNullOrBlank() -> unit.pluralName.orEmpty()
            else -> unit.name
        }
        val food = ingredient.food
        val scaled = ScaleIngredients.amount(amount, factor)
        val foodText = if (food == null) "" else PluralRule.name(food.name, food.pluralName, scaled, ingredient.noAmount)
        return when (part) {
            IngredientPart.AMOUNT -> amountPart
            IngredientPart.UNIT -> escape(unitText)
            IngredientPart.FOOD -> escape(foodText)
            IngredientPart.NOTE -> escape(ingredient.note.orEmpty())
            IngredientPart.ALL -> listOf(amountPart, escape(unitText), escape(foodText)).filter { it.isNotEmpty() }.joinToString(" ")
        }
    }

    /** The amount the server renders: the precise value from `conversions` when it is the row itself. */
    private fun preciseAmount(ingredient: Ingredient): Double {
        val own = ingredient.conversions.firstOrNull() ?: return ingredient.amount
        val unitName = ingredient.unit?.name ?: return ingredient.amount
        val close = own.unitName == unitName && own.amount.isFinite() && abs(own.amount - ingredient.amount) <= JSON_ROUNDING
        return if (close) own.amount else ingredient.amount
    }

    private fun amountText(amount: Double, factor: Double, preferences: UserPreferences, style: NumberStyle): String =
        WebAmountFormat.format(amount, factor, preferences.useFractions, preferences.ingredientDecimals).text(style)

    /**
     * Jinja would reject the template (Tandoor then fails the whole step): empty code,
     * unbalanced brackets or quotes, or code ending in an operator.
     */
    private fun isSyntaxError(code: String): Boolean {
        if (code.isEmpty()) return true
        val stack = ArrayDeque<Char>()
        var quote: Char? = null
        for (c in code) {
            if (quote != null) {
                if (c == quote) quote = null
                continue
            }
            when (c) {
                '\'', '"' -> quote = c
                '(', '[', '{' -> stack.addLast(c)
                ')' -> if (stack.removeLastOrNull() != '(') return true
                ']' -> if (stack.removeLastOrNull() != '[') return true
                '}' -> if (stack.removeLastOrNull() != '{') return true
            }
        }
        return quote != null || stack.isNotEmpty() || code.last() in "+-*/%,.|=<>!~("
    }

    private fun escape(text: String): String {
        if (text.none { it in MARKDOWN_SPECIAL }) return text
        return buildString(text.length + 4) {
            for (c in text) {
                if (c in MARKDOWN_SPECIAL) append('\\')
                append(c)
            }
        }
    }
}
