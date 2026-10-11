package casa.moller.tandem.core.data.demo

import casa.moller.tandem.core.network.dto.CookLogDto
import casa.moller.tandem.core.network.dto.KeywordDto
import casa.moller.tandem.core.network.dto.MealPlanDto
import casa.moller.tandem.core.network.dto.MealTypeDto
import casa.moller.tandem.core.network.dto.RecipeBookDto
import casa.moller.tandem.core.network.dto.RecipeBookEntryDto
import casa.moller.tandem.core.network.dto.RecipeDto
import casa.moller.tandem.core.network.dto.RecipeFlatDto
import casa.moller.tandem.core.network.dto.RecipeOverviewDto
import casa.moller.tandem.core.network.dto.UserPreferenceDto
import casa.moller.tandem.core.network.dto.ViewLogDto
import casa.moller.tandem.core.network.json.TandoorJson
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The built-in demo content (plan 6.2 "Demoläge", Phase 1: recipes, a meal plan for "Idag",
 * cook-log statistics, two recipe books and user preferences), read from
 * `core/data/src/main/assets/demo/` (see the README there and docs/BYGGE.md "Demoinnehåll").
 *
 * Everything is stored in **Tandoor 2.6.13's own JSON shapes** and returned as core:network's
 * DTOs, so a demo session goes through exactly the same DTO -> model -> entity mappers as a real
 * server. A loader only: the sync lane uses it as the data source of demo sessions.
 *
 * - **Pure and deterministic:** the result depends only on the arguments. The files are written
 *   as if today were [REFERENCE_DATE]; every date (`created_at`, `updated_at`, `from_date`,
 *   `to_date`, `last_cooked`) is shifted by the days between [REFERENCE_DATE] and `today`, so the
 *   meal plan always starts today, three recipes are always "new" and the cook log always ends a
 *   day ago.
 * - **Locale-aware:** Swedish for Swedish locales, English otherwise ([DemoLanguage.of]). Both
 *   languages share every id, amount and time.
 * - **Images** are `file:///android_asset/demo/images/<name>.webp` URIs ([IMAGE_URI_PREFIX]),
 *   which Coil loads from the APK without network. They are NOT Tandoor media URLs: a demo
 *   session must use them as they are and never pass them through `MediaUrls.resolve`.
 *
 * Parsed files are cached; the class is thread-safe. A missing or broken asset is a packaging bug
 * (DemoContentTest checks every file) and throws [IllegalStateException].
 */
class DemoContent(private val assets: DemoAssets) {
    private val cache = ConcurrentHashMap<String, JsonElement>()

    private val index: JsonObject by lazy { load(INDEX_FILE).jsonObject }

    /** Version of the demo content; bump it in the generator when the content changes. */
    val version: Int by lazy { index.getValue("version").jsonPrimitive.int }

    /** Ids of every demo recipe, in the generator's order. */
    val recipeIds: List<Long> by lazy { index.getValue("recipes").jsonArray.map { it.jsonPrimitive.long } }

    /** Recipes the demo user has pinned on "Idag" (pinning is app-local, not a Tandoor field). */
    val pinnedRecipeIds: List<Long> by lazy { index.getValue("pinned_recipes").jsonArray.map { it.jsonPrimitive.long } }

    /** URIs of every demo image (Coil-loadable, see [IMAGE_URI_PREFIX]). */
    val imageUris: List<String> by lazy { index.getValue("images").jsonArray.map { IMAGE_URI_PREFIX + it.jsonPrimitive.content } }

    /** Every recipe as `GET /api/recipe/{id}/` returns it, in [recipeIds] order. */
    fun recipes(locale: Locale, today: LocalDate): List<RecipeDto> = recipeIds.map { id ->
        decode(RecipeDto.serializer(), recipeJson(id, locale, today) ?: error("Demo recipe $id is listed but missing"))
    }

    /** One recipe as `GET /api/recipe/{id}/` returns it, or null for an id that is not a demo recipe. */
    fun recipe(id: Long, locale: Locale, today: LocalDate): RecipeDto? =
        recipeJson(id, locale, today)?.let { decode(RecipeDto.serializer(), it) }

    /**
     * The raw JSON of [recipe] (dates shifted), for the raw-JSON column next to the extracted
     * fields (plan 3.6, 5.7), or null for an id that is not a demo recipe.
     */
    fun recipeJson(id: Long, locale: Locale, today: LocalDate): JsonObject? {
        if (id !in recipeIds) return null
        return shiftDates(load(languageFile(locale, "recipe-$id.json")), daysSinceReference(today)).jsonObject
    }

    /** The recipe list (`GET /api/recipe/` results), sorted by name like Tandoor's default order. */
    fun recipeOverviews(locale: Locale, today: LocalDate): List<RecipeOverviewDto> =
        list(RecipeOverviewDto.serializer(), languageFile(locale, "recipe-overviews.json"), today)

    /** `GET /api/recipe/flat/`: the cheap index of every recipe (the deletion diff of a sync). */
    fun recipeFlat(locale: Locale): List<RecipeFlatDto> = list(RecipeFlatDto.serializer(), languageFile(locale, "recipe-flat.json"))

    /** Every keyword (`GET /api/keyword/`), parents included (`Huvudrätt > Pasta`). */
    fun keywords(locale: Locale, today: LocalDate): List<KeywordDto> =
        list(KeywordDto.serializer(), languageFile(locale, "keywords.json"), today)

    /** The meal types (Frukost, Lunch, Middag, Fika). */
    fun mealTypes(locale: Locale): List<MealTypeDto> = list(MealTypeDto.serializer(), languageFile(locale, "meal-types.json"))

    /**
     * The meal plan around [today]: yesterday's dinner, today and tomorrow, and the coming week
     * (`GET /api/meal-plan/` results). One entry has no recipe (a title only) and one spans two
     * days.
     */
    fun mealPlan(locale: Locale, today: LocalDate): List<MealPlanDto> =
        list(MealPlanDto.serializer(), languageFile(locale, "meal-plan.json"), today)

    /**
     * The demo user's cook log (`GET /api/cook-log/` results), oldest last: the source of "times
     * cooked", "last cooked" and ratings ("Ofta lagade", "Inte lagat på länge"). Language-neutral.
     */
    fun cookLog(today: LocalDate): List<CookLogDto> = list(CookLogDto.serializer(), COOK_LOG_FILE, today)

    /** Recently viewed recipes ("Nyligen visade"), as view-log entries. Language-neutral. */
    fun viewLog(today: LocalDate): List<ViewLogDto> = list(ViewLogDto.serializer(), VIEW_LOG_FILE, today)

    /** The two recipe books (plan 6.2: "Favoriter" and "Vill laga"). */
    fun recipeBooks(locale: Locale): List<RecipeBookDto> = list(RecipeBookDto.serializer(), languageFile(locale, "recipe-books.json"))

    /** The recipes in [recipeBooks] (`GET /api/recipe-book-entry/` results). */
    fun recipeBookEntries(locale: Locale, today: LocalDate): List<RecipeBookEntryDto> =
        list(RecipeBookEntryDto.serializer(), languageFile(locale, "recipe-book-entries.json"), today)

    /**
     * The demo user's Tandoor preferences: fractions on (`use_fractions`), two decimals, dinner as
     * the default meal type.
     */
    fun userPreferences(locale: Locale): UserPreferenceDto =
        decode(UserPreferenceDto.serializer(), load(languageFile(locale, "user-preference.json")))

    private fun <T> list(serializer: KSerializer<T>, file: String, today: LocalDate? = null): List<T> {
        val json = load(file)
        val shifted = if (today == null) json else shiftDates(json, daysSinceReference(today))
        return decode(ListSerializer(serializer), shifted)
    }

    private fun <T> decode(serializer: KSerializer<T>, json: JsonElement): T = try {
        TandoorJson.decodeFromJsonElement(serializer, json)
    } catch (e: IllegalArgumentException) {
        // SerializationException is an IllegalArgumentException.
        throw IllegalStateException("Broken demo asset for ${serializer.descriptor.serialName}", e)
    }

    private fun load(file: String): JsonElement = cache.getOrPut(file) {
        val text = try {
            assets.read(file)
        } catch (e: IOException) {
            throw IllegalStateException("Demo asset $file is missing", e)
        }
        try {
            TandoorJson.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Demo asset $file is not valid JSON", e)
        }
    }

    companion object {
        /** The folder inside the APK's assets. */
        const val ASSET_ROOT: String = "demo"

        /** Prefix of every demo image URI; Coil loads `file:///android_asset/...` from the APK. */
        const val IMAGE_URI_PREFIX: String = "file:///android_asset/$ASSET_ROOT/images/"

        /** The day the files are written for ("today" in the files; the same as the fixtures'). */
        val REFERENCE_DATE: LocalDate = LocalDate(2000, 1, 15)

        private const val INDEX_FILE = "index.json"
        private const val COOK_LOG_FILE = "cook-log.json"
        private const val VIEW_LOG_FILE = "view-log.json"

        /** The JSON keys whose values are Tandoor dates or date-times (FACTS "Fas 1"). */
        private val DATE_KEYS = setOf("created_at", "updated_at", "from_date", "to_date", "last_cooked")
        private val DATE_PREFIX = Regex("^\\d{4}-\\d{2}-\\d{2}")

        /** The asset path (relative to [ASSET_ROOT]) of a demo image URI, or null for any other URI. */
        fun imageAssetPath(uri: String): String? =
            uri.takeIf { it.startsWith(IMAGE_URI_PREFIX) }?.let { "images/" + it.removePrefix(IMAGE_URI_PREFIX) }

        internal fun languageFile(locale: Locale, file: String): String = "${DemoLanguage.of(locale).tag}/$file"

        internal fun daysSinceReference(today: LocalDate): Int = REFERENCE_DATE.daysUntil(today)

        /**
         * Moves every date in [json] by [days] days. Only the `yyyy-MM-dd` part changes; the time
         * and offset stay, so a meal planned for 18:00 stays at 18:00.
         */
        internal fun shiftDates(json: JsonElement, days: Int): JsonElement = when {
            days == 0 -> json
            json is JsonObject -> JsonObject(
                json.mapValues { (key, value) ->
                    if (key in DATE_KEYS && value is JsonPrimitive && value.isString) shiftDate(value, days) else shiftDates(value, days)
                },
            )
            json is JsonArray -> JsonArray(json.map { shiftDates(it, days) })
            else -> json
        }

        private fun shiftDate(value: JsonPrimitive, days: Int): JsonPrimitive {
            val text = value.content
            val prefix = DATE_PREFIX.find(text) ?: return value
            val shifted = LocalDate.parse(prefix.value).plus(days, DateTimeUnit.DAY)
            return JsonPrimitive(shifted.toString() + text.substring(prefix.range.last + 1))
        }
    }
}
