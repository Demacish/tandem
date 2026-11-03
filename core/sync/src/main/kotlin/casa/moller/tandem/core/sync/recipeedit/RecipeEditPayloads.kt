package casa.moller.tandem.core.sync.recipeedit

import casa.moller.tandem.core.data.recipe.RecipeEditProblem
import casa.moller.tandem.core.model.recipe.DraftIngredient
import casa.moller.tandem.core.model.recipe.DraftKeyword
import casa.moller.tandem.core.model.recipe.DraftStep
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.recipe.Ingredient
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.model.recipe.MeasurementUnit
import casa.moller.tandem.core.model.recipe.NutritionPresence
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.model.recipe.Step
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Fas 3 (redigerare lane): the `recipe_edit` rows' payloads (docs/BYGGE.md "Fas 3 – kontrakt",
 * table "Outbox"). The payload of a CREATE or PATCH IS the request body Tandoor's own editor
 * sends (FACTS "Fas 1 a", "Fas 3"): `{name, description, servings, servings_text, working_time,
 * waiting_time, source_url, private, internal: true, keywords: [{id} | {name}], steps: [{id?,
 * name, instruction, time, order, show_as_header, show_ingredients_table, ingredients: [{id?,
 * food: {name} | null, unit: {name} | null, amount, note, order, is_header, no_amount}]}]}`.
 *
 * - A keyword the device knows goes by `{id}` only (FACTS "Fas 2 g": `{id, name}` with another
 *   name renames it space-wide); a new one by `{name}` (get-or-create, case ignored).
 * - Foods and units always go by `{name}` (reused or created by name); a header has neither.
 * - A step or ingredient keeps its `id`, so a PATCH updates it in place and leaves what the app
 *   does not edit (a step's file, a linked sub-recipe) alone; one without `id` is created, and the
 *   server deletes those the PATCH leaves out (drf-writable-nested).
 *
 * `base_snapshot` of a PATCH is `{"updated_at": "<the stored copy's>"}`: the sender reads the
 * recipe fresh and holds the edit back as [CONFLICT] when the server's `updated_at` differs.
 * [decode] reads a payload back into a [RecipeDraft] (the editor reopens a kept edit).
 */
internal object RecipeEditPayloads {
    /** The sender's `last_error` of an edit held back because the recipe changed on the server meanwhile. */
    const val CONFLICT: String = "tandem.recipe.conflict"

    private val json = Json { ignoreUnknownKeys = true }

    init {
        check(CONFLICT == RecipeEditProblem.CONFLICT.detail)
    }

    /** The request body (and payload) of [draft], already normalized. */
    fun encode(draft: RecipeDraft): JsonObject = buildJsonObject {
        put("name", draft.name)
        put("description", draft.description)
        put("servings", draft.servings)
        put("servings_text", draft.servingsText)
        put("working_time", draft.workingMinutes)
        put("waiting_time", draft.waitingMinutes)
        put("source_url", draft.sourceUrl)
        put("private", draft.isPrivate)
        put("internal", true)
        put(
            "keywords",
            buildJsonArray {
                draft.keywords.forEach { keyword ->
                    val id = keyword.id
                    add(buildJsonObject { if (id != null && id > 0) put("id", id) else put("name", keyword.name) })
                }
            },
        )
        put(
            "steps",
            buildJsonArray {
                draft.steps.forEachIndexed { order, step -> add(encodeStep(step, order)) }
            },
        )
    }

    private fun encodeStep(step: DraftStep, order: Int): JsonObject = buildJsonObject {
        step.id?.let { put("id", it) }
        put("name", step.name)
        put("instruction", step.instruction)
        put("time", step.timeMinutes)
        put("order", order)
        put("show_as_header", step.showAsHeader)
        put("show_ingredients_table", step.showIngredientsTable)
        put(
            "ingredients",
            buildJsonArray {
                step.ingredients.forEachIndexed { position, ingredient -> add(encodeIngredient(ingredient, position)) }
            },
        )
    }

    private fun encodeIngredient(ingredient: DraftIngredient, order: Int): JsonObject = buildJsonObject {
        ingredient.id?.let { put("id", it) }
        val food = ingredient.food?.takeUnless { ingredient.isHeader }
        val unit = ingredient.unit?.takeUnless { ingredient.isHeader }
        put("food", if (food == null) JsonNull else buildJsonObject { put("name", food) })
        put("unit", if (unit == null) JsonNull else buildJsonObject { put("name", unit) })
        put("amount", if (ingredient.isHeader || ingredient.noAmount) 0.0 else ingredient.amount)
        put("note", ingredient.note)
        put("order", order)
        put("is_header", ingredient.isHeader)
        put("no_amount", ingredient.noAmount && !ingredient.isHeader)
    }

    /** The draft a CREATE or PATCH payload stands for (keyword names come from [keywordsById]); null for anything else. */
    fun decode(payload: String, keywordsById: Map<Long, String> = emptyMap()): RecipeDraft? {
        val body = parse(payload) ?: return null
        val name = body.string("name") ?: return null
        return RecipeDraft(
            name = name,
            description = body.string("description").orEmpty(),
            servings = body.int("servings") ?: 1,
            servingsText = body.string("servings_text").orEmpty(),
            workingMinutes = body.int("working_time") ?: 0,
            waitingMinutes = body.int("waiting_time") ?: 0,
            sourceUrl = body.string("source_url").orEmpty(),
            isPrivate = body.bool("private") ?: false,
            keywords = body.array("keywords").mapNotNull { element ->
                val keyword = element as? JsonObject ?: return@mapNotNull null
                val id = keyword.long("id")
                val keywordName = keyword.string("name") ?: id?.let { keywordsById[it] } ?: return@mapNotNull null
                DraftKeyword(id = id, name = keywordName)
            },
            steps = body.array("steps").mapNotNull { element -> (element as? JsonObject)?.let(::decodeStep) },
        )
    }

    private fun decodeStep(step: JsonObject): DraftStep = DraftStep(
        id = step.long("id"),
        name = step.string("name").orEmpty(),
        instruction = step.string("instruction").orEmpty(),
        timeMinutes = step.int("time") ?: 0,
        showAsHeader = step.bool("show_as_header") ?: false,
        showIngredientsTable = step.bool("show_ingredients_table") ?: true,
        ingredients = step.array("ingredients").mapNotNull { element ->
            val ingredient = element as? JsonObject ?: return@mapNotNull null
            DraftIngredient(
                id = ingredient.long("id"),
                amount = (ingredient["amount"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                unit = (ingredient["unit"] as? JsonObject)?.string("name"),
                food = (ingredient["food"] as? JsonObject)?.string("name"),
                note = ingredient.string("note").orEmpty(),
                isHeader = ingredient.bool("is_header") ?: false,
                noAmount = ingredient.bool("no_amount") ?: false,
            )
        },
    )

    /** `base_snapshot` of a PATCH: the `updated_at` of the copy the edit started from (null = no check). */
    fun base(updatedAt: Instant?): String = buildJsonObject { updatedAt?.let { put("updated_at", it.toString()) } }.toString()

    /** The `updated_at` a base snapshot holds, null when it has none. */
    fun baseUpdatedAt(base: String?): Instant? =
        base?.let { parse(it) }?.string("updated_at")?.let { runCatching { Instant.parse(it) }.getOrNull() }

    /** A `RECIPE_IMAGE` row: the app-private copy of the photo. */
    fun image(path: String): String = buildJsonObject { put("file", path) }.toString()

    fun imagePathOf(payload: String): String? = parse(payload)?.string("file")

    fun parse(payload: String): JsonObject? = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject

    /**
     * The recipe as this device stores it until the server answers: [draft] under [id] with the
     * ids of its stored steps and ingredients, foods and units resolved by name where the device
     * knows them (so the food filter and the pantry's "Kan laga nu" see them), [stored]'s image,
     * rating and cook statistics kept, and [imageUrl] (a new photo's copy) replacing the image.
     */
    fun toRecipe(
        id: Long,
        draft: RecipeDraft,
        stored: Recipe?,
        keywords: List<Keyword>,
        foodsByName: Map<String, Food>,
        unitsByName: Map<String, MeasurementUnit>,
        imageUrl: String?,
        now: Instant,
    ): Recipe {
        var nextId = -1L
        val steps = draft.steps.mapIndexed { order, step ->
            Step(
                id = step.id ?: nextId--,
                name = step.name,
                instruction = step.instruction,
                timeMinutes = step.timeMinutes,
                order = order,
                showAsHeader = step.showAsHeader,
                showIngredientsTable = step.showIngredientsTable,
                stepRecipe = stored?.steps?.firstOrNull { it.id == step.id }?.stepRecipe,
                imageUrl = stored?.steps?.firstOrNull { it.id == step.id }?.imageUrl,
                ingredients = step.ingredients.mapIndexed { position, ingredient ->
                    Ingredient(
                        id = ingredient.id ?: nextId--,
                        food = ingredient.food?.let { name -> foodsByName[key(name)] ?: Food(id = 0, name = name) },
                        unit = ingredient.unit?.let { name -> unitsByName[key(name)] ?: MeasurementUnit(id = 0, name = name) },
                        amount = ingredient.amount,
                        note = ingredient.note.ifEmpty { null },
                        isHeader = ingredient.isHeader,
                        noAmount = ingredient.noAmount,
                        order = position,
                    )
                },
            )
        }
        return Recipe(
            id = id,
            name = draft.name,
            description = draft.description.ifEmpty { null },
            imageUrl = imageUrl ?: stored?.imageUrl,
            servings = draft.servings,
            servingsText = draft.servingsText,
            workingMinutes = draft.workingMinutes,
            waitingMinutes = draft.waitingMinutes,
            rating = stored?.rating,
            lastCooked = stored?.lastCooked,
            keywords = keywords,
            steps = steps,
            sourceUrl = draft.sourceUrl.ifEmpty { null },
            isPrivate = draft.isPrivate,
            showIngredientOverview = stored?.showIngredientOverview ?: true,
            nutrition = stored?.nutrition ?: NutritionPresence.NONE,
            diameter = stored?.diameter ?: 0,
            diameterText = stored?.diameterText.orEmpty(),
            createdAt = stored?.createdAt ?: now,
            updatedAt = stored?.updatedAt,
        )
    }

    /** Names compare trimmed and without case (Tandoor reuses foods, units and keywords that way). */
    fun key(name: String): String = name.trim().lowercase()

    private fun JsonObject.string(field: String): String? = (this[field] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

    private fun JsonObject.int(field: String): Int? = (this[field] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(field: String): Long? = (this[field] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.bool(field: String): Boolean? = (this[field] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.array(field: String): JsonArray = this[field] as? JsonArray ?: JsonArray(emptyList())
}
