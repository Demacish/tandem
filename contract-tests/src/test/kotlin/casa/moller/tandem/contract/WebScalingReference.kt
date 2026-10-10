package casa.moller.tandem.contract

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * An independent Kotlin statement of how Tandoor 2.6.13's web frontend scales and displays
 * ingredient amounts (FACTS.md, Fas 1 "Skalning"). Written from the documented behaviour, not
 * copied from Tandoor; [ScalingGoldenTest] checks it against `scaling-golden.json`, which
 * contract.yml produces by executing the frontend's own functions. App lanes implement the same
 * rules in :core:domain and test them against the same golden file.
 */
internal object WebScalingReference {
    private const val MAX_DENOMINATOR = 16.0
    private const val SINGULAR_EPSILON = 0.0001

    /** RecipeView: the factor every amount is multiplied with. */
    fun factor(servings: Double, recipeServings: Int?): Double = servings / (recipeServings?.let { maxOf(it, 1) } ?: 1).toDouble()

    /** `|amount - 1| < 0.0001` selects the singular name. */
    fun isSingular(amount: Double): Boolean = abs(amount - 1) < SINGULAR_EPSILON

    /**
     * The HTML the web inserts for `amount * factor`: with fractions off the number rounded to
     * [decimals] (JavaScript `Number(x.toFixed(decimals))` printed as a JS number); with fractions
     * on a mixed fraction with denominator <= 16 (`2 <sup>1</sup>&frasl;<sub>2</sub>`), or the
     * rounded number when no fraction within 1/16 steps fits.
     */
    fun amountHtml(amount: Double, factor: Double, useFractions: Boolean, decimals: Int): String {
        val x = amount * factor
        if (!useFractions) return roundDecimals(x, decimals)
        val (whole, numerator, denominator) = mixedFraction(x)
        if (whole == 0.0 && numerator == 0.0 && denominator == 1.0) return roundDecimals(x, decimals)
        val out = StringBuilder()
        if (whole > 0) out.append(jsInteger(whole))
        if (numerator > 0) out.append(" <sup>${jsInteger(numerator)}</sup>&frasl;<sub>${jsInteger(denominator)}</sub>")
        return out.toString()
    }

    /**
     * JavaScript `String(Number(x.toFixed(decimals)))`: `toFixed` rounds the exact binary value
     * half up; the result prints without trailing zeros.
     */
    fun roundDecimals(x: Double, decimals: Int): String {
        val rounded = BigDecimal(x).setScale(decimals, RoundingMode.HALF_UP)
        return rounded.stripTrailingZeros().toPlainString()
    }

    /**
     * Best rational approximation with a denominator <= 16 by walking the Stern-Brocot tree
     * between floor(x) and floor(x) + 1 (the algorithm of the Apache-2.0 "frac" package that the
     * web uses), returned as (whole, numerator, denominator). Doubles throughout, like JavaScript.
     */
    fun mixedFraction(x: Double): Triple<Double, Double, Double> {
        var n1 = floor(x)
        var d1 = 1.0
        var n2 = n1 + 1
        var d2 = 1.0
        if (x != n1) {
            while (d1 <= MAX_DENOMINATOR && d2 <= MAX_DENOMINATOR) {
                val m = (n1 + n2) / (d1 + d2)
                if (x == m) {
                    if (d1 + d2 <= MAX_DENOMINATOR) {
                        d1 += d2
                        n1 += n2
                        d2 = MAX_DENOMINATOR + 1
                    } else if (d1 > d2) {
                        d2 = MAX_DENOMINATOR + 1
                    } else {
                        d1 = MAX_DENOMINATOR + 1
                    }
                    break
                } else if (x < m) {
                    n2 += n1
                    d2 += d1
                } else {
                    n1 += n2
                    d1 += d2
                }
            }
        }
        if (d1 > MAX_DENOMINATOR) {
            d1 = d2
            n1 = n2
        }
        val whole = floor(n1 / d1)
        return Triple(whole, n1 - whole * d1, d1)
    }

    /** Singular or plural name: singular for no_amount, a missing plural or a singular amount. */
    fun pluralName(singular: String, plural: String?, amount: Double, noAmount: Boolean): String {
        if (noAmount || plural.isNullOrEmpty() || isSingular(amount)) return singular
        return plural
    }

    /** One row of the web's ingredient table (IngredientsTable.vue). */
    fun ingredientRow(ingredient: JsonObject, factor: Double, useFractions: Boolean, decimals: Int): GoldenRow {
        val note = ingredient.stringAt("note").orEmpty()
        if (ingredient.boolAt("is_header") == true) return GoldenRow(header = note)
        val amount = ingredient.doubleAt("amount") ?: 0.0
        val noAmount = ingredient.boolAt("no_amount") == true
        val shown = !noAmount && amount != 0.0
        val html = if (shown) amountHtml(amount, factor, useFractions, decimals) else ""
        val unit = ingredient.objAt("unit")
        val food = ingredient.objAt("food")
        val unitText = if (unit != null && shown) {
            pluralName(unit.stringAt("name").orEmpty(), unit.stringAt("plural_name"), amount * factor, noAmount)
        } else {
            ""
        }
        val foodText = food?.let { pluralName(it.stringAt("name").orEmpty(), it.stringAt("plural_name"), amount * factor, noAmount) }
        return GoldenRow(
            amount = htmlToText(html),
            amountHtml = if (useFractions) html else null,
            unit = htmlToText(unitText),
            food = htmlToText(foodText.orEmpty()),
            note = note,
        )
    }

    /**
     * The scaled values in a step's `instructions_markdown`: every `<scalable-number>` shows
     * [amountHtml] of its number, every `<plural-name>` the singular or plural for amount * factor.
     */
    fun step(step: JsonObject, factor: Double, useFractions: Boolean, decimals: Int): GoldenStep {
        val tokens = mutableListOf<GoldenToken>()
        val rendered = TAG.replace(step.stringAt("instructions_markdown").orEmpty()) { match ->
            val attributes = ATTRIBUTE.findAll(match.groupValues[2]).associate { attribute ->
                attribute.groupValues[1] to decodeEntities(attribute.groups[2]?.value ?: attribute.groups[3]?.value.orEmpty())
            }
            if (match.groupValues[1] == "scalable-number") {
                val number = attributes.getValue("v-bind:number").toDouble()
                val html = amountHtml(number, factor, useFractions, decimals)
                tokens += GoldenToken(tag = "scalable-number", number = number, html = html, text = htmlToText(html))
                html
            } else {
                val singular = attributes["singular"].orEmpty()
                val plural = attributes["plural"].orEmpty()
                val amount = attributes.getValue("v-bind:amount").toDouble()
                val noAmount = attributes[":no-amount"] == "true"
                val text = pluralName(singular, plural, amount * factor, noAmount)
                tokens += GoldenToken(
                    tag = "plural-name",
                    singular = singular,
                    plural = plural,
                    amount = amount,
                    noAmount = noAmount,
                    text = text,
                )
                text
            }
        }
        val rows = step.arrAt("ingredients").objects().map { ingredientRow(it, factor, useFractions, decimals) }
        return GoldenStep(ingredients = rows, templates = tokens, instructionsHtml = rendered)
    }

    /** Visible text: tags removed, `&frasl;` as U+2044, whitespace collapsed and trimmed. */
    fun htmlToText(html: String): String {
        val withSlash = html.replace("&frasl;", FRACTION_SLASH)
        val withoutTags = withSlash.replace(HTML_TAG, "")
        return withoutTags.replace(WHITESPACE, " ").trim()
    }

    private fun decodeEntities(value: String): String {
        var out = value
        for ((entity, character) in ENTITIES) out = out.replace(entity, character)
        return out
    }

    /** A JavaScript number that holds an integer, printed like JavaScript does. */
    private fun jsInteger(value: Double): String = value.toLong().toString()

    /** U+2044 FRACTION SLASH, what `&frasl;` displays. */
    private const val FRACTION_SLASH = "\u2044"
    private val HTML_TAG = Regex("<[^>]+>")
    private val WHITESPACE = Regex("\\s+")

    /** In this order: `&amp;` last, so `&amp;lt;` stays `&lt;`. */
    private val ENTITIES = listOf("&quot;" to "\"", "&#39;" to "'", "&#x27;" to "'", "&lt;" to "<", "&gt;" to ">", "&amp;" to "&")

    private val TAG = Regex("""<(scalable-number|plural-name)((?:\s+[^\s=>]+=(?:"[^"]*"|'[^']*'))*)\s*>\s*</\1>""")
    private val ATTRIBUTE = Regex("""([^\s=>]+)=(?:"([^"]*)"|'([^']*)')""")
}

// --- scaling-golden.json -------------------------------------------------------------------------

@Serializable
internal data class Golden(val meta: JsonObject, val recipes: List<GoldenRecipe>)

@Serializable
internal data class GoldenRecipe(
    val fixture: String,
    val id: Long,
    val name: String,
    val servings: Int? = null,
    val cases: List<GoldenCase>,
)

@Serializable
internal data class GoldenCase(val servings: Double, val factor: Double, val fractionsOff: GoldenView, val fractionsOn: GoldenView)

@Serializable
internal data class GoldenView(val steps: List<GoldenStep>)

@Serializable
internal data class GoldenStep(val ingredients: List<GoldenRow>, val templates: List<GoldenToken>, val instructionsHtml: String)

@Serializable
internal data class GoldenRow(
    val header: String? = null,
    val amount: String? = null,
    val amountHtml: String? = null,
    val unit: String? = null,
    val food: String? = null,
    val note: String? = null,
)

@Serializable
internal data class GoldenToken(
    val tag: String,
    val number: Double? = null,
    val html: String? = null,
    val singular: String? = null,
    val plural: String? = null,
    val amount: Double? = null,
    val noAmount: Boolean? = null,
    val text: String,
)
