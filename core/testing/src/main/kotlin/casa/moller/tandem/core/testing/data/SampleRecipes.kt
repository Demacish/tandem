package casa.moller.tandem.core.testing.data

import casa.moller.tandem.core.model.home.HomeSection
import casa.moller.tandem.core.model.home.HomeSectionType
import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.mealplan.MealType
import casa.moller.tandem.core.model.mealplan.UpcomingMeals
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.recipe.Ingredient
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.model.recipe.MeasurementUnit
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.model.recipe.Step
import casa.moller.tandem.core.model.recipe.StepRecipeRef
import casa.moller.tandem.core.model.recipe.UnitConversion
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Deterministic sample data for unit and screenshot tests (never shipped: core:testing is
 * test-only). Covers what the recipe screens must handle: Swedish names with å/ä/ö, fractional
 * amounts (½, ¼, ⅓, 1½), ingredient headers, "no amount" rows, unit conversions, steps with a
 * step time and with times in the text ("30 minuter", "8–10 minuter"), a step recipe reference,
 * a recipe without steps, private and new recipes, and an English recipe.
 *
 * Images are null on purpose: screenshot tests must not hit the network.
 */
object SampleRecipes {

    val Vegetarian = Keyword(id = 1, name = "Vegetariskt")
    val Weekday = Keyword(id = 2, name = "Vardagsmiddag")
    val Baking = Keyword(id = 3, name = "Bakning")
    val Fika = Keyword(id = 4, name = "Fika", parentId = 3)
    val Soup = Keyword(id = 5, name = "Soppa")

    val keywords: List<Keyword> = listOf(Baking, Fika, Soup, Weekday, Vegetarian)

    private val gram = MeasurementUnit(id = 1, name = "g")
    private val deciliter = MeasurementUnit(id = 2, name = "dl")
    private val tablespoon = MeasurementUnit(id = 3, name = "msk")
    private val teaspoon = MeasurementUnit(id = 4, name = "tsk")
    private val pinch = MeasurementUnit(id = 5, name = "krm")
    private val piece = MeasurementUnit(id = 6, name = "st")

    private fun food(id: Long, name: String, plural: String? = null) = Food(id = id, name = name, pluralName = plural)

    private val cookedAt = Instant.parse("2026-10-01T17:30:00Z")
    private val updatedAt = Instant.parse("2026-09-20T08:00:00Z")

    /** Krämig svamppasta (the Stitch mockup's recipe): 4 servings, conversions, a no-amount row. */
    val MushroomPasta = Recipe(
        id = 101,
        name = "Krämig svamppasta",
        description = "Snabb vardagspasta med champinjoner, vitlök och parmesan.",
        servings = 4,
        servingsText = "portioner",
        workingMinutes = 20,
        waitingMinutes = 5,
        rating = 4.5,
        lastCooked = cookedAt,
        keywords = listOf(Vegetarian, Weekday),
        steps = listOf(
            Step(
                id = 1011,
                name = "Svampen",
                instruction = "Skiva svampen och stek den gyllene i smör, ca 5 minuter.",
                timeMinutes = 5,
                order = 0,
                ingredients = listOf(
                    Ingredient(id = 10111, food = food(1, "champinjon", "champinjoner"), unit = gram, amount = 400.0, order = 0),
                    Ingredient(id = 10112, food = food(2, "smör"), unit = tablespoon, amount = 1.5, order = 1),
                    Ingredient(
                        id = 10113,
                        food = food(3, "vitlöksklyfta", "vitlöksklyftor"),
                        unit = piece,
                        amount = 2.0,
                        note = "pressade",
                        order = 2,
                    ),
                ),
            ),
            Step(
                id = 1012,
                name = "Pastan",
                instruction = "Koka pastan enligt förpackningen, **8–10 minuter**. Spara ½ dl pastavatten.",
                order = 1,
                ingredients = listOf(
                    Ingredient(id = 10121, food = food(4, "pasta"), unit = gram, amount = 400.0, order = 0),
                    Ingredient(id = 10122, food = food(5, "salt"), noAmount = true, order = 1),
                ),
            ),
            Step(
                id = 1013,
                instruction = "Rör ner grädde och parmesan, späd med pastavattnet och vänd ner pastan.",
                order = 2,
                ingredients = listOf(
                    Ingredient(
                        id = 10131,
                        food = food(6, "vispgrädde"),
                        unit = deciliter,
                        amount = 2.5,
                        order = 0,
                        conversions = listOf(
                            UnitConversion(amount = 250.0, unitName = "ml"),
                            UnitConversion(amount = 1.06, unitName = "cup", unitPluralName = "cups"),
                        ),
                    ),
                    Ingredient(id = 10132, food = food(7, "parmesan"), unit = deciliter, amount = 0.75, note = "riven", order = 1),
                ),
            ),
        ),
        updatedAt = updatedAt,
    )

    /** Kanelbullar: ingredient headers, a long rise in the text, 1/3 and 1/4 amounts. */
    val CinnamonBuns = Recipe(
        id = 102,
        name = "Kanelbullar",
        description = "Klassiska bullar till fikat.",
        servings = 30,
        servingsText = "bullar",
        workingMinutes = 45,
        waitingMinutes = 90,
        rating = 5.0,
        keywords = listOf(Baking, Fika),
        steps = listOf(
            Step(
                id = 1021,
                name = "Deg",
                instruction = "Smält smöret, tillsätt mjölken och värm till 37 °C. Blanda med resten och låt jäsa i 30 minuter.",
                timeMinutes = 30,
                order = 0,
                ingredients = listOf(
                    Ingredient(id = 10210, note = "Deg", isHeader = true, order = 0),
                    Ingredient(id = 10211, food = food(10, "vetemjöl"), unit = deciliter, amount = 8.5, order = 1),
                    Ingredient(id = 10212, food = food(11, "mjölk"), unit = deciliter, amount = 3.0, order = 2),
                    Ingredient(id = 10213, food = food(2, "smör"), unit = gram, amount = 100.0, order = 3),
                    Ingredient(id = 10214, food = food(12, "jäst"), unit = gram, amount = 25.0, order = 4),
                    Ingredient(id = 10215, food = food(13, "kardemumma"), unit = teaspoon, amount = 1.0 / 3.0, note = "stött", order = 5),
                ),
            ),
            Step(
                id = 1022,
                name = "Fyllning",
                instruction = "Kavla ut degen, bred på fyllningen, rulla ihop och skär i bitar. Jäs i 1 timme.",
                order = 1,
                ingredients = listOf(
                    Ingredient(id = 10220, note = "Fyllning", isHeader = true, order = 0),
                    Ingredient(id = 10221, food = food(2, "smör"), unit = gram, amount = 75.0, order = 1),
                    Ingredient(id = 10222, food = food(14, "kanel"), unit = tablespoon, amount = 1.5, order = 2),
                    Ingredient(id = 10223, food = food(15, "salt"), unit = pinch, amount = 0.25, order = 3),
                ),
            ),
            Step(
                id = 1023,
                name = "Grädda",
                instruction = "Pensla med ägg och grädda mitt i ugnen på 250 °C i 5–7 minuter.",
                timeMinutes = 7,
                order = 2,
                showIngredientsTable = false,
            ),
        ),
        updatedAt = updatedAt,
    )

    /** Ärtsoppa med fläsk: a step recipe reference and a whole-number recipe. */
    val PeaSoup = Recipe(
        id = 103,
        name = "Ärtsoppa med fläsk",
        servings = 6,
        servingsText = "portioner",
        workingMinutes = 30,
        waitingMinutes = 120,
        rating = 4.0,
        lastCooked = Instant.parse("2026-03-05T17:00:00Z"),
        keywords = listOf(Soup),
        steps = listOf(
            Step(
                id = 1031,
                instruction = "Låt ärtorna ligga i blöt över natten. Koka sedan upp och låt sjuda i 1 tim och 30 minuter.",
                timeMinutes = 90,
                order = 0,
                ingredients = listOf(
                    Ingredient(id = 10311, food = food(20, "gul ärta", "gula ärtor"), unit = gram, amount = 500.0, order = 0),
                    Ingredient(id = 10312, food = food(21, "rimmat sidfläsk"), unit = gram, amount = 400.0, order = 1),
                ),
            ),
            Step(
                id = 1032,
                instruction = "Servera med senap och pannkakor.",
                order = 1,
                stepRecipe = StepRecipeRef(recipeId = 105, name = "Pannkakor"),
            ),
        ),
        updatedAt = updatedAt,
    )

    /** Äppelpaj: private, never cooked, no rating. */
    val ApplePie = Recipe(
        id = 104,
        name = "Äppelpaj",
        servings = 8,
        workingMinutes = 20,
        waitingMinutes = 40,
        keywords = listOf(Baking),
        isPrivate = true,
        steps = listOf(
            Step(
                id = 1041,
                instruction = "Skiva äpplena, smula ihop smör, mjöl och socker och grädda i 200 °C i 25 minuter.",
                timeMinutes = 25,
                order = 0,
                ingredients = listOf(
                    Ingredient(id = 10411, food = food(30, "äpple", "äpplen"), unit = piece, amount = 4.0, order = 0),
                    Ingredient(id = 10412, food = food(10, "vetemjöl"), unit = deciliter, amount = 1.5, order = 1),
                ),
            ),
        ),
        updatedAt = updatedAt,
    )

    /** Pannkakor: new, without steps' times, referenced by [PeaSoup]. */
    val Pancakes = Recipe(
        id = 105,
        name = "Pannkakor",
        servings = 4,
        workingMinutes = 25,
        keywords = listOf(Weekday),
        steps = listOf(
            Step(
                id = 1051,
                instruction = "Vispa ihop mjöl, mjölk och ägg. Stek tunna pannkakor i smör.",
                order = 0,
                ingredients = listOf(
                    Ingredient(id = 10511, food = food(10, "vetemjöl"), unit = deciliter, amount = 2.5, order = 0),
                    Ingredient(id = 10512, food = food(11, "mjölk"), unit = deciliter, amount = 6.0, order = 1),
                    Ingredient(id = 10513, food = food(31, "ägg"), unit = piece, amount = 3.0, order = 2),
                ),
            ),
        ),
        updatedAt = updatedAt,
    )

    /** An English recipe: content is shown in the server's language, whatever the app's. */
    val BananaBread = Recipe(
        id = 106,
        name = "Banana bread",
        servings = 1,
        servingsText = "loaf",
        workingMinutes = 15,
        waitingMinutes = 60,
        rating = 3.5,
        steps = listOf(
            Step(
                id = 1061,
                instruction = "Mash the bananas, mix everything and bake for about 55 minutes.",
                timeMinutes = 55,
                order = 0,
                ingredients = listOf(
                    Ingredient(id = 10611, food = food(40, "banana", "bananas"), unit = piece, amount = 3.0, order = 0),
                    Ingredient(
                        id = 10612,
                        food = food(41, "flour"),
                        unit = MeasurementUnit(id = 7, name = "cup", pluralName = "cups"),
                        amount = 1.5,
                        order = 1,
                    ),
                ),
            ),
        ),
        updatedAt = updatedAt,
    )

    /** A recipe that only exists in the index: no steps, no times. */
    val IndexOnly = Recipe(id = 107, name = "Ölbröd")

    /** Every sample recipe detail, by id order. */
    val details: List<Recipe> = listOf(MushroomPasta, CinnamonBuns, PeaSoup, ApplePie, Pancakes, BananaBread, IndexOnly)

    /** The list entries of [details]. Pasta and buns are available offline (buns pinned), Pancakes are new. */
    val overviews: List<RecipeOverview> = details.map { recipe ->
        recipe.toOverview(
            availableOffline = recipe.id == MushroomPasta.id || recipe.id == CinnamonBuns.id,
            pinned = recipe.id == CinnamonBuns.id,
            isNew = recipe.id == Pancakes.id,
            timesCooked = when (recipe.id) {
                MushroomPasta.id -> 12
                PeaSoup.id -> 3
                else -> null
            },
        )
    }

    /** The overview of the sample recipe [id]. */
    fun overview(id: Long): RecipeOverview = overviews.first { it.id == id }

    val Dinner = MealType(id = 1, name = "Middag", order = 1, time = LocalTime(18, 0), color = "#E8590C")
    val Lunch = MealType(id = 2, name = "Lunch", order = 0, time = LocalTime(12, 0))

    /** The day the sample meal plan is built around (a Tuesday). */
    val Today: LocalDate = LocalDate(2026, 10, 6)

    /** Tonight: the mushroom pasta; tomorrow: a lunch with only a title, and pea soup for dinner. */
    val upcomingMeals: UpcomingMeals = UpcomingMeals(
        today = Today,
        todayMeals = listOf(
            MealPlanEntry(id = 1, recipe = overview(MushroomPasta.id), servings = 4.0, fromDate = Today, mealType = Dinner),
        ),
        tomorrowMeals = listOf(
            MealPlanEntry(id = 2, title = "Rester", fromDate = LocalDate(2026, 10, 7), mealType = Lunch),
            MealPlanEntry(id = 3, recipe = overview(PeaSoup.id), servings = 6.0, fromDate = LocalDate(2026, 10, 7), mealType = Dinner),
        ),
    )

    /** The Today carousels for the sample data. */
    val homeSections: List<HomeSection> = listOf(
        HomeSection(HomeSectionType.PINNED, listOf(overview(CinnamonBuns.id))),
        HomeSection(HomeSectionType.OFTEN_COOKED, listOf(overview(MushroomPasta.id), overview(PeaSoup.id))),
        HomeSection(HomeSectionType.NEW, listOf(overview(Pancakes.id))),
        HomeSection(HomeSectionType.NOT_COOKED_LATELY, listOf(overview(PeaSoup.id))),
    )
}

/** The list entry for this detail (what the data layer stores next to it). */
fun Recipe.toOverview(
    availableOffline: Boolean = true,
    pinned: Boolean = false,
    isNew: Boolean = false,
    timesCooked: Int? = null,
): RecipeOverview = RecipeOverview(
    id = id,
    name = name,
    description = description,
    imageUrl = imageUrl,
    workingMinutes = workingMinutes,
    waitingMinutes = waitingMinutes,
    rating = rating,
    lastCooked = lastCooked,
    timesCooked = timesCooked,
    keywords = keywords,
    isNew = isNew,
    isPrivate = isPrivate,
    updatedAt = updatedAt,
    availableOffline = availableOffline,
    pinned = pinned,
)
