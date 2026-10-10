package casa.moller.tandem.contract

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The throwaway data Phase1ContractTest creates through the API (plan §10 Fas 1: "Skalning ger
 * samma mängder som Tandoors webb för 10 testrecept"). Invented recipes; nothing here is a real
 * household's data. The coverage matters, not the cooking: å/ä/ö and an English recipe,
 * ingredient headers, `no_amount`, fractional amounts (0.25, 0.333, 1.5, 2/3), an amount of 0,
 * plural foods and units, notes, step names/times/`show_as_header`, Jinja templates
 * (`{{ scale(n) }}`, `{{ ingredients[i] }}` and its fields, one broken template), keywords with a
 * child keyword, `servings_text`, working/waiting time, a private recipe and `source_url`.
 */
internal object Fas1Seed {
    /** name, plural (null = none), base_unit (null = none). Plurals are set with a PATCH (FACTS fas1 b). */
    val units: List<SeedUnit> = listOf(
        SeedUnit("g", null, "g"),
        SeedUnit("kg", null, "kg"),
        SeedUnit("ml", null, "ml"),
        SeedUnit("l", null, "l"),
        SeedUnit("dl", null, null),
        SeedUnit("msk", null, "tbsp"),
        SeedUnit("tsk", null, "tsp"),
        SeedUnit("st", null, null),
        SeedUnit("burk", "burkar", null),
        SeedUnit("cup", "cups", "us_cup"),
        SeedUnit("clove", "cloves", null),
        SeedUnit("pinch", "pinches", null),
    )

    /** Foods that need a plural name; every other food is created by name inside the recipes. */
    val foods: List<SeedFood> = listOf(
        SeedFood("champinjon", "champinjoner"),
        SeedFood("gul lök", "gula lökar"),
        SeedFood("vitlöksklyfta", "vitlöksklyftor"),
        SeedFood("potatis", "potatisar"),
        SeedFood("morot", "morötter"),
        SeedFood("äpple", "äpplen"),
        SeedFood("tomat", "tomater"),
        SeedFood("räka", "räkor"),
        SeedFood("gul ärta", "gula ärtor"),
        SeedFood("egg", "eggs"),
        SeedFood("onion", "onions"),
        SeedFood("vetemjöl", null),
    )

    /**
     * Unit conversions: a generic 1 dl = 100 ml and a food-specific 1 dl vetemjöl = 60 g, so
     * `Ingredient.conversions` has custom and base-unit entries (FACTS fas1 e).
     */
    val conversions: List<SeedConversion> = listOf(
        SeedConversion(1.0, "dl", 100.0, "ml", null),
        SeedConversion(1.0, "dl", 60.0, "g", "vetemjöl"),
    )

    /** Keyword tree: [CHILD_KEYWORD] is moved under [PARENT_KEYWORD]. */
    const val PARENT_KEYWORD = "Huvudrätt"
    const val CHILD_KEYWORD = "Pasta"
    val keywords: List<String> = listOf(PARENT_KEYWORD, CHILD_KEYWORD, "Vegetariskt", "Bakning", "Husmanskost", "Soppa", "Breakfast")

    /** Meal types: one with a default time, one without. */
    const val DINNER = "Middag"
    const val DINNER_TIME = "18:00:00"
    const val LUNCH = "Lunch"

    /** The recipe whose Step/Ingredient/Food are edited to see what bumps `Recipe.updated_at`. */
    const val EDITED = "gronsakssoppa"

    /** Created and deleted again (404 and recipe/flat after deletion). */
    val temporary = SeedRecipe(
        slug = "temporary",
        name = "Tillfälligt recept",
        servings = 1,
        steps = listOf(SeedStep(instruction = "Raderas av testet.")),
    )

    val recipes: List<SeedRecipe> = listOf(
        SeedRecipe(
            slug = "svamppasta",
            name = "Krämig svamppasta",
            description = "Snabb vardagspasta med champinjoner och grädde.",
            servings = 4,
            servingsText = "portioner",
            workingTime = 20,
            waitingTime = 10,
            keywords = listOf(CHILD_KEYWORD, "Vegetariskt"),
            sourceUrl = "https://exempel.se/recept/kramig-svamppasta",
            image = true,
            steps = listOf(
                SeedStep(
                    name = "Förbered",
                    time = 10,
                    showAsHeader = true,
                    instruction = "Skiva {{ ingredients[0].food }} och hacka {{ ingredients[1] }}.\n" +
                        "Pressa {{ ingredients[2] }}.",
                    ingredients = listOf(
                        ing(250, "g", "champinjon", note = "gärna blandade svampar"),
                        ing(1, "st", "gul lök"),
                        ing(2, null, "vitlöksklyfta"),
                    ),
                ),
                SeedStep(
                    name = "Koka pastan",
                    time = 12,
                    showAsHeader = true,
                    instruction = "Koka pastan i {{ scale(3) }} liter saltat vatten.",
                    ingredients = listOf(
                        ing(400, "g", "pasta"),
                        ing(0, null, "salt", note = "till pastavattnet", noAmount = true),
                    ),
                ),
                SeedStep(
                    name = "Sås",
                    time = 8,
                    showAsHeader = true,
                    instruction = "Häll i {{ ingredients[0] }} och låt puttra.\n\n" +
                        "Rör ner {{ ingredients[1].amount }} {{ ingredients[1].unit }} {{ ingredients[1].food }} " +
                        "(*{{ ingredients[1].note }}*) och smaka av med {{ ingredients[2].food }}.",
                    ingredients = listOf(
                        ing(2.5, "dl", "vispgrädde"),
                        ing(50, "g", "parmesan", note = "riven"),
                        ing(0, null, "svartpeppar", noAmount = true),
                        ing(0.25, "tsk", "salt"),
                    ),
                ),
            ),
        ),
        SeedRecipe(
            slug = "pannkakor",
            name = "Pannkakor",
            servings = 4,
            servingsText = "portioner",
            workingTime = 30,
            keywords = listOf("Husmanskost"),
            steps = listOf(
                SeedStep(
                    instruction = "Vispa ihop en smet och stek ungefär {{ scale(12) }} tunna pannkakor i {{ ingredients[4].unit }} " +
                        "{{ ingredients[4].food }}.",
                    ingredients = listOf(
                        ing(3, null, "ägg"),
                        ing(6, "dl", "mjölk"),
                        ing(2.5, "dl", "vetemjöl"),
                        ing(0.5, "tsk", "salt"),
                        ing(3, "msk", "smör", note = "till stekning"),
                    ),
                ),
            ),
        ),
        SeedRecipe(
            slug = "kanelbullar",
            name = "Kanelbullar",
            servings = 30,
            servingsText = "bullar",
            workingTime = 60,
            waitingTime = 90,
            keywords = listOf("Bakning"),
            steps = listOf(
                SeedStep(
                    name = "Degen",
                    time = 15,
                    instruction = "Smula {{ ingredients[1] }} i en bunke och värm {{ ingredients[2].food }} och smör till 37 grader.",
                    ingredients = listOf(
                        header("Deg"),
                        ing(50, "g", "jäst"),
                        ing(5, "dl", "mjölk"),
                        ing(150, "g", "smör"),
                        ing(1, "dl", "socker"),
                        ing(0.5, "tsk", "salt"),
                        ing(1, "tsk", "kardemumma", note = "stött"),
                        ing(13, "dl", "vetemjöl"),
                    ),
                ),
                SeedStep(name = "Jäsning", time = 60, instruction = "Låt degen jäsa under bakduk."),
                SeedStep(
                    name = "Fyllning och gräddning",
                    time = 8,
                    instruction = "Bred fyllningen över degen, rulla och skär i {{ scale(30) }} bitar. Grädda i 250 grader.",
                    ingredients = listOf(
                        header("Fyllning"),
                        ing(100, "g", "smör", note = "rumsvarmt"),
                        ing(0.75, "dl", "socker"),
                        ing(1.5, "msk", "kanel"),
                        header("Pensling"),
                        ing(1, null, "ägg"),
                    ),
                ),
            ),
        ),
        SeedRecipe(
            slug = "kottbullar",
            name = "Köttbullar med potatismos",
            servings = 4,
            servingsText = "portioner",
            workingTime = 45,
            keywords = listOf("Husmanskost", PARENT_KEYWORD),
            steps = listOf(
                SeedStep(
                    name = "Köttbullar",
                    time = 25,
                    showAsHeader = true,
                    instruction = "Blanda {{ ingredients[1] }} med {{ ingredients[2] }}. Rulla cirka {{ scale(24) }} köttbullar.",
                    ingredients = listOf(
                        ing(500, "g", "köttfärs"),
                        ing(0.5, "dl", "ströbröd"),
                        ing(1, "dl", "mjölk"),
                        ing(1, null, "gul lök", note = "finriven"),
                        ing(1, null, "ägg"),
                        ing(0, null, "salt", noAmount = true),
                    ),
                ),
                SeedStep(
                    name = "Potatismos",
                    time = 20,
                    showAsHeader = true,
                    instruction = "Koka {{ ingredients[0] }} mjuk och mosa med {{ ingredients[1] }} och smör.",
                    ingredients = listOf(
                        ing(1, "kg", "potatis"),
                        ing(2, "dl", "mjölk"),
                        ing(50, "g", "smör"),
                        ing(1, "tsk", "salt"),
                    ),
                ),
                SeedStep(
                    name = "Servering",
                    instruction = "Servera med {{ ingredients[0].food }}.",
                    ingredients = listOf(ing(0, null, "lingonsylt", note = "till servering", noAmount = true)),
                ),
            ),
        ),
        SeedRecipe(
            slug = "shakshuka",
            name = "Shakshuka",
            description = "Eggs poached in a spiced tomato sauce.",
            servings = 2,
            servingsText = "servings",
            workingTime = 25,
            keywords = listOf("Breakfast", "Vegetariskt"),
            sourceUrl = "https://example.com/recipes/shakshuka",
            steps = listOf(
                SeedStep(
                    name = "Sauce",
                    time = 15,
                    instruction = "Fry {{ ingredients[0] }} and {{ ingredients[1] }}, then add {{ ingredients[2] }} and " +
                        "{{ ingredients[3].amount }} {{ ingredients[3].unit }} {{ ingredients[3].food }}.",
                    ingredients = listOf(
                        ing(1, null, "onion", note = "chopped"),
                        ing(2, "clove", "garlic"),
                        ing(1.5, "cup", "crushed tomatoes"),
                        ing(2.0 / 3.0, "cup", "water"),
                        ing(1, "pinch", "salt"),
                    ),
                ),
                SeedStep(
                    name = "Eggs",
                    time = 8,
                    instruction = "Make {{ scale(4) }} wells and crack {{ ingredients[0] }} into them.",
                    ingredients = listOf(ing(4, null, "egg")),
                ),
            ),
        ),
        SeedRecipe(
            slug = "artsoppa",
            name = "Ärtsoppa",
            description = "Privat recept: bara skaparen ser det.",
            servings = 6,
            servingsText = "portioner",
            workingTime = 120,
            waitingTime = 720,
            keywords = listOf("Husmanskost", "Soppa"),
            isPrivate = true,
            steps = listOf(
                SeedStep(
                    name = "Blötlägg",
                    time = 720,
                    instruction = "Blötlägg {{ ingredients[0] }} över natten.",
                    ingredients = listOf(ing(5, "dl", "gul ärta")),
                ),
                SeedStep(
                    name = "Koka",
                    time = 120,
                    instruction = "Koka ärtorna i {{ ingredients[0] }} med {{ ingredients[1] }}.",
                    ingredients = listOf(
                        ing(1.5, "l", "vatten"),
                        ing(300, "g", "rimmat fläsk"),
                        ing(1, null, "gul lök"),
                        ing(1, "tsk", "timjan"),
                        ing(0.5, "msk", "salt"),
                    ),
                ),
            ),
        ),
        SeedRecipe(
            slug = EDITED,
            name = "Grönsakssoppa",
            servings = 4,
            servingsText = "portioner",
            workingTime = 15,
            waitingTime = 25,
            keywords = listOf("Soppa", "Vegetariskt"),
            steps = listOf(
                SeedStep(
                    name = "Koka",
                    time = 25,
                    instruction = "Koka {{ ingredients[0] }} och {{ ingredients[1] }} i {{ scale(20) }} minuter.",
                    ingredients = listOf(
                        ing(4, null, "morot"),
                        ing(2, null, "potatis"),
                        ing(1, "l", "vatten"),
                        ing(2, "msk", "smör"),
                    ),
                ),
            ),
        ),
        SeedRecipe(
            slug = "smorgastarta",
            name = "Smörgåstårta",
            servings = 12,
            servingsText = "bitar",
            workingTime = 60,
            waitingTime = 240,
            steps = listOf(
                SeedStep(
                    name = "Fyllning",
                    instruction = "Blanda {{ ingredients[0] }} med {{ ingredients[2] }}. Garnera med {{ ingredients[5] }}.",
                    ingredients = listOf(
                        ing(0.25, "l", "majonnäs"),
                        ing(0.333, "kg", "räka", note = "skalade"),
                        ing(2.0 / 3.0, "burk", "stenbitsrom"),
                        ing(400, "g", "crème fraiche"),
                        ing("1,5", "dl", "dill", note = "hackad (mängden skickades som strängen \"1,5\")"),
                        ing(0, "st", "tomat", note = "till garnering (mängd 0, inte no_amount)"),
                    ),
                ),
            ),
        ),
        SeedRecipe(
            slug = "appelkaka",
            name = "Äppelkaka",
            servings = 8,
            servingsText = "bitar",
            workingTime = 20,
            waitingTime = 40,
            keywords = listOf("Bakning"),
            image = true,
            steps = listOf(
                SeedStep(
                    name = "Smeten",
                    time = 10,
                    instruction = "Rör ihop {{ ingredients[1] }}, {{ ingredients[2] }} och {{ ingredients[3] }}.",
                    ingredients = listOf(
                        ing(4, null, "äpple"),
                        ing(150, "g", "smör"),
                        ing(1.5, "dl", "socker"),
                        ing(2, null, "ägg"),
                        ing(2.5, "dl", "vetemjöl"),
                        ing(1, "tsk", "bakpulver"),
                    ),
                ),
                SeedStep(
                    name = "Grädda",
                    time = 40,
                    instruction = "Lägg {{ ingredients[0] }} i klyftor ovanpå och strö över kanel. Grädda i 175 grader.",
                    ingredients = listOf(ing(1, "tsk", "kanel")),
                ),
            ),
        ),
        SeedRecipe(
            slug = "rarakor",
            name = "Rårakor med lingon",
            servings = 4,
            servingsText = "portioner",
            workingTime = 30,
            keywords = listOf("Husmanskost", "Vegetariskt"),
            steps = listOf(
                SeedStep(
                    name = "Riv",
                    // A broken Jinja template: Tandoor returns an error text instead of HTML.
                    instruction = "Riv potatisen grovt. {{ scale( }}",
                    ingredients = listOf(ing(1, "kg", "potatis"), ing(1, "tsk", "salt")),
                ),
                SeedStep(
                    name = "Stek",
                    time = 15,
                    instruction = "Stek tunna rårakor i {{ ingredients[0] }}. Servera med {{ ingredients[1].food }}.",
                    ingredients = listOf(ing(3, "msk", "smör"), ing(0, null, "lingonsylt", noAmount = true)),
                ),
            ),
        ),
    )

    private fun ing(
        amount: Any,
        unit: String?,
        food: String?,
        note: String = "",
        noAmount: Boolean = false,
    ): SeedIngredient = SeedIngredient(amount, unit, food, note, header = false, noAmount = noAmount)

    private fun header(text: String): SeedIngredient = SeedIngredient(0, null, null, text, header = true, noAmount = false)
}

internal data class SeedUnit(val name: String, val plural: String?, val baseUnit: String?)

internal data class SeedFood(val name: String, val plural: String?)

internal data class SeedConversion(
    val baseAmount: Double,
    val baseUnit: String,
    val convertedAmount: Double,
    val convertedUnit: String,
    val food: String?,
)

/** [amount] is a Number, or a String to see how Tandoor parses `"1,5"`. */
internal data class SeedIngredient(
    val amount: Any,
    val unit: String?,
    val food: String?,
    val note: String,
    val header: Boolean,
    val noAmount: Boolean,
)

internal data class SeedStep(
    val name: String = "",
    val instruction: String,
    val time: Int = 0,
    val showAsHeader: Boolean = false,
    val ingredients: List<SeedIngredient> = emptyList(),
)

internal data class SeedRecipe(
    val slug: String,
    val name: String,
    val description: String = "",
    val servings: Int,
    val servingsText: String = "",
    val workingTime: Int = 0,
    val waitingTime: Int = 0,
    val keywords: List<String> = emptyList(),
    val sourceUrl: String? = null,
    val isPrivate: Boolean = false,
    val image: Boolean = false,
    val steps: List<SeedStep>,
) {
    /** The body of `POST /api/recipe/`, shaped like the web editor's (nested objects by name). */
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("servings", servings)
        put("servings_text", servingsText)
        put("working_time", workingTime)
        put("waiting_time", waitingTime)
        put("internal", true)
        put("private", isPrivate)
        put("source_url", sourceUrl?.let { JsonPrimitive(it) } ?: JsonNull)
        put("keywords", JsonArray(keywords.map { buildJsonObject { put("name", it) } }))
        put(
            "steps",
            JsonArray(
                steps.mapIndexed { stepIndex, step ->
                    buildJsonObject {
                        put("name", step.name)
                        put("instruction", step.instruction)
                        put("time", step.time)
                        put("order", stepIndex)
                        put("show_as_header", step.showAsHeader)
                        put("show_ingredients_table", true)
                        put("ingredients", JsonArray(step.ingredients.mapIndexed { index, it -> it.toJson(index) }))
                    }
                },
            ),
        )
    }
}

private fun SeedIngredient.toJson(order: Int): JsonObject = buildJsonObject {
    put("food", food?.let { buildJsonObject { put("name", it) } } ?: JsonNull)
    put("unit", unit?.let { buildJsonObject { put("name", it) } } ?: JsonNull)
    put("amount", amountJson(amount))
    put("note", note)
    put("order", order)
    put("is_header", header)
    put("no_amount", noAmount)
}

private fun amountJson(amount: Any): JsonElement = when (amount) {
    is String -> JsonPrimitive(amount)
    is Number -> JsonPrimitive(amount)
    else -> error("amount must be a number or a string: $amount")
}
