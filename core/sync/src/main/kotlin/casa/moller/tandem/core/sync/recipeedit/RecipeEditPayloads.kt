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
import casa.moller.tandem.core.model.recipe.PropertyType
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.model.recipe.RecipeProperty
import casa.moller.tandem.core.model.recipe.Step
import casa.moller.tandem.core.model.recipe.StepFile
import casa.moller.tandem.core.model.recipe.StepRecipeRef
import casa.moller.tandem.core.network.json.TandoorDates
import java.security.MessageDigest
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
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
 * - A keyword always carries `name` (`{id}` alone is a 400, FACTS "Fas 3 a"): a known one as
 *   `{id, name}` with the name the device has (the sender swaps in the server's current name
 *   before a POST or PATCH, since another name renames the keyword space-wide, FACTS "Fas 2 g"), a
 *   new one as `{name}` (get-or-create, case ignored).
 * - Foods and units always go by `{name}` (reused or created by name); a header has neither.
 * - A step or ingredient keeps its `id`, so a PATCH updates it in place; one without `id` is
 *   created, and the server deletes those the PATCH leaves out (drf-writable-nested).
 * - Fas 4 (#22, FACTS "Fas 4: receptredigeraren"): `step_recipe`, a step's `file` and the recipe's
 *   own `properties` go only when they differ from the server's copy the edit started from: what is
 *   left out stays as it is on the server, so a save from a form that never touched them cannot
 *   change them. On a copy the server has not answered yet there is nothing to compare with, so a
 *   stored step says all of it. A row keeps what only the device needs, and [forSending] takes it
 *   off: a sub-recipe's name (`step_recipe_data`), a property type's `unit` and a chosen file's
 *   `path` (the sender has uploaded it by then).
 *
 * `base_snapshot` of a PATCH is `{"updated_at": "<the stored copy's>"}`: the sender reads the
 * recipe fresh and holds the edit back as [CONFLICT] when the server's `updated_at` differs and
 * the server does not already say what the edit says ([sameContent]). Fas 4 review (fix-data): it
 * comes from the same stored JSON as the fingerprint, never from the overview, which a sync moves
 * while the detail the draft was made from stays old.
 * [decode] reads a payload back into a [RecipeDraft] (the editor reopens a kept edit).
 */
internal object RecipeEditPayloads {
    /** The sender's `last_error` of an edit held back because the recipe changed on the server meanwhile. */
    const val CONFLICT: String = "tandem.recipe.conflict"

    private val json = Json { ignoreUnknownKeys = true }

    /** The recipe's own fields an edit sends; see [sameContent]. */
    private val COMPARED_FIELDS =
        listOf("name", "description", "servings", "servings_text", "working_time", "waiting_time", "source_url", "private")

    /** The fields of the server's copy the app does not edit but shows, kept while an edit is unsent (never in the payload). */
    private val NUTRITION_FIELDS = listOf("properties", "food_properties")

    /** A step's file in a row: the app-private copy of a file chosen and not uploaded yet. */
    private const val PATH = "path"

    init {
        check(CONFLICT == RecipeEditProblem.CONFLICT.detail)
    }

    /**
     * The payload of [draft], already normalized. [server] is the recipe as the server last said it:
     * null for a new recipe and for one with an edit not answered yet.
     */
    fun encode(draft: RecipeDraft, server: Recipe? = null): JsonObject = buildJsonObject {
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
                    add(
                        buildJsonObject {
                            if (id != null && id > 0) put("id", id)
                            put("name", keyword.name)
                        },
                    )
                }
            },
        )
        put(
            "steps",
            buildJsonArray {
                draft.steps.forEachIndexed { order, step -> add(encodeStep(step, order, server)) }
            },
        )
        draft.properties?.takeIf { server == null || !sameProperties(it, server.properties) }?.let { properties ->
            put("properties", JsonArray(properties.map(::encodeProperty)))
        }
    }

    private fun sameProperties(ours: List<RecipeProperty>, theirs: List<RecipeProperty>): Boolean =
        ours.map { it.typeId to it.amount }.toSet() == theirs.map { it.typeId to it.amount }.toSet()

    private fun encodeProperty(property: RecipeProperty): JsonObject = buildJsonObject {
        property.id?.let { put("id", it) }
        put(
            "property_type",
            buildJsonObject {
                put("id", property.typeId)
                put("name", property.typeName)
                put("unit", property.unit)
            },
        )
        put("property_amount", property.amount)
    }

    private fun encodeStep(step: DraftStep, order: Int, server: Recipe?): JsonObject = buildJsonObject {
        step.id?.let { put("id", it) }
        put("name", step.name)
        put("instruction", step.instruction)
        put("time", step.timeMinutes)
        put("order", order)
        put("show_as_header", step.showAsHeader)
        put("show_ingredients_table", step.showIngredientsTable)
        val known = server?.steps?.firstOrNull { it.id == step.id }
        // A step nobody has an answer for says it all; a new one has nothing to take off.
        val saysAll = server == null && step.id != null
        val linkChanged = known?.let { it.stepRecipe?.recipeId != step.stepRecipe?.recipeId } ?: (saysAll || step.stepRecipe != null)
        if (linkChanged) encodeLink(step.stepRecipe)
        val fileChanged = known?.let { it.file?.id != step.file?.id || step.file?.uri != null } ?: (saysAll || step.file != null)
        if (fileChanged) put("file", encodeFile(step.file))
        put(
            "ingredients",
            buildJsonArray {
                step.ingredients.forEachIndexed { position, ingredient -> add(encodeIngredient(ingredient, position)) }
            },
        )
    }

    private fun JsonObjectBuilder.encodeLink(link: StepRecipeRef?) {
        put("step_recipe", link?.recipeId)
        link?.name?.let { name ->
            put(
                "step_recipe_data",
                buildJsonObject {
                    put("id", link.recipeId)
                    put("name", name)
                },
            )
        }
    }

    private fun encodeFile(file: StepFile?): JsonElement = if (file == null) {
        JsonNull
    } else {
        buildJsonObject {
            file.id?.let { put("id", it) }
            put("name", file.name)
            file.uri?.let { put(PATH, it) }
        }
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

    /**
     * The draft a CREATE or PATCH payload stands for (keyword names come from [keywordsById]); null
     * for anything else. What the payload left out is as it is on the server: [stored] says it.
     */
    fun decode(payload: String, keywordsById: Map<Long, String> = emptyMap(), stored: Recipe? = null): RecipeDraft? {
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
            steps = body.array("steps").mapNotNull { element -> (element as? JsonObject)?.let { decodeStep(it, stored) } },
            properties = decodeProperties(body) ?: stored?.properties,
        )
    }

    private fun decodeProperties(body: JsonObject): List<RecipeProperty>? =
        (body["properties"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::decodeProperty) }

    private fun decodeProperty(property: JsonObject): RecipeProperty? {
        val type = property["property_type"] as? JsonObject ?: return null
        return RecipeProperty(
            id = property.long("id"),
            typeId = type.long("id") ?: return null,
            typeName = type.string("name") ?: return null,
            unit = type.string("unit").orEmpty(),
            amount = (property["property_amount"] as? JsonPrimitive)?.doubleOrNull ?: return null,
        )
    }

    private fun decodeLink(step: JsonObject): StepRecipeRef? = step.long("step_recipe")?.let { id ->
        StepRecipeRef(recipeId = id, name = (step["step_recipe_data"] as? JsonObject)?.string("name"))
    }

    private fun decodeFile(step: JsonObject): StepFile? = (step["file"] as? JsonObject)?.let { file ->
        file.string("name")?.let { name -> StepFile(id = file.long("id"), name = name, uri = file.string(PATH)) }
    }

    private fun decodeStep(step: JsonObject, stored: Recipe?): DraftStep = DraftStep(
        stepRecipe = if ("step_recipe" in step) {
            decodeLink(step)
        } else {
            stored?.steps?.firstOrNull { it.id == step.long("id") }?.stepRecipe
        },
        file = if ("file" in step) decodeFile(step) else stored?.steps?.firstOrNull { it.id == step.long("id") }?.file,
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

    /**
     * `base_snapshot` of a PATCH: the `updated_at` of the copy the edit started from (null = no
     * check) and, Fas 3's leftovers, the [stepsFingerprint] of that copy: a change of only a step
     * or an ingredient on the web (`PATCH /api/step/{id}/`, `/api/ingredient/{id}/`) leaves the
     * recipe's `updated_at` as it was (FACTS "Fas 4: Fas 3:s rester" d).
     */
    fun base(updatedAt: Instant?, stepsFingerprint: String? = null): String = buildJsonObject {
        updatedAt?.let { put("updated_at", it.toString()) }
        stepsFingerprint?.let { put("steps", it) }
    }.toString()

    /** The steps fingerprint a base snapshot holds, null when it has none (older rows: only `updated_at` is compared). */
    fun baseStepsFingerprint(base: String?): String? = base?.let { parse(it) }?.string("steps")

    /**
     * What a recipe's steps say, as the server's JSON ([recipe], `GET /api/recipe/{id}/`) holds
     * them: per step (by id) its name, instruction, time and order, per line its id, amount, unit,
     * food, note, header and no-amount flags and order, as a SHA-256 hex string. Nothing else of
     * the step goes in (a food's on-hand flag or shopping state changes without an edit). Null
     * when [recipe] has no steps array. Fas 4 (#22): the step's flags, sub-recipe and file count once
     * they differ from a plain step's, so a recipe without any has the fingerprint it always had: a
     * queued edit made by an older version of the app is compared with what it saw.
     */
    fun stepsFingerprint(recipe: JsonObject?): String? {
        val steps = recipe?.get("steps") as? JsonArray ?: return null
        val text = steps.mapNotNull { it as? JsonObject }.sortedBy { it.long("id") ?: Long.MAX_VALUE }.joinToString("\n") { step ->
            val lines = step.array("ingredients").mapNotNull { it as? JsonObject }.sortedBy { it.long("id") ?: Long.MAX_VALUE }
                .joinToString(";") { line ->
                    listOf(
                        line.long("id"),
                        (line["amount"] as? JsonPrimitive)?.doubleOrNull,
                        (line["unit"] as? JsonObject)?.string("name"),
                        (line["food"] as? JsonObject)?.string("name"),
                        line.string("note").orEmpty(),
                        line.bool("is_header"),
                        line.bool("no_amount"),
                        line.int("order"),
                    ).joinToString("|")
                }
            val own = listOf(step.long("id"), step.string("name").orEmpty(), step.string("instruction").orEmpty(), step.int("time"))
            own.joinToString("|") + "|" + step.int("order") + "|" + lines + stepExtras(step)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun stepExtras(step: JsonObject): String = listOfNotNull(
        "header".takeIf { step.bool("show_as_header") == true },
        "no-table".takeIf { step.bool("show_ingredients_table") == false },
        step.long("step_recipe")?.let { "recipe=$it" },
        (step["file"] as? JsonObject)?.long("id")?.let { "file=$it" },
    ).joinToString("") { "|$it" }

    /** The `updated_at` a base snapshot holds, null when it has none. */
    fun baseUpdatedAt(base: String?): Instant? =
        base?.let { parse(it) }?.string("updated_at")?.let { runCatching { Instant.parse(it) }.getOrNull() }

    /** The `updated_at` of the server's JSON [recipe] in the sender's precision; null for an unsent edit's body, which has none. */
    fun updatedAtOf(recipe: JsonObject?): Instant? =
        TandoorDates.parseInstantOrNull(recipe?.string("updated_at"))?.toEpochMilli()?.let { Instant.fromEpochMilliseconds(it) }

    /**
     * The base snapshot an edit that started from [from] has after this device's own write moved
     * the recipe to [to]; null when [snapshot] did not start from [from] (or has no `updated_at`).
     */
    fun rebased(snapshot: String?, from: JsonObject, to: JsonObject): String? {
        val started = baseUpdatedAt(snapshot) ?: return null
        val steps = baseStepsFingerprint(snapshot)
        if (started != updatedAtOf(from) || (steps != null && steps != stepsFingerprint(from))) return null
        val moved = updatedAtOf(to) ?: return null
        return base(moved, if (steps != null) stepsFingerprint(to) else null)
    }

    /** The whole-recipe [payload] with the keywords [after] has and [before] lacks added, and the reverse removed. */
    fun withKeywordChange(payload: String, before: JsonObject, after: JsonObject): String {
        val body = parse(payload) ?: return payload
        val had = before.keywordsById()
        val has = after.keywordsById()
        val kept = body.array("keywords").filterNot { (it as? JsonObject)?.long("id")?.let { id -> id in had && id !in has } == true }
        val present = kept.mapNotNull { (it as? JsonObject)?.long("id") }.toSet()
        val added = has.filterKeys { it !in had && it !in present }.map { (id, name) ->
            buildJsonObject {
                put("id", id)
                put("name", name)
            }
        }
        return JsonObject(body + ("keywords" to JsonArray(kept + added))).toString()
    }

    private fun JsonObject.keywordsById(): Map<Long, String> = array("keywords").mapNotNull { element ->
        val keyword = element as? JsonObject ?: return@mapNotNull null
        keyword.long("id")?.let { id -> id to keyword.string("name").orEmpty() }
    }.toMap()

    /**
     * Whether the server's [recipe] already says what the whole-recipe [body] says: the edit's fields,
     * keywords and steps by content, not by id (rows the edit added got theirs from the server). True
     * for a PATCH whose answer was lost: it was applied, nobody else changed anything.
     */
    fun sameContent(recipe: JsonObject, body: JsonObject): Boolean =
        COMPARED_FIELDS.all { field -> recipe.text(field) == body.text(field) } &&
            sameKeywords(recipe, body) &&
            stepsContent(recipe) == stepsContent(body) &&
            sameStepExtras(recipe, body) &&
            sameOwnProperties(recipe, body)

    /** Fas 4 (#22): what an edit left out it did not change, so only what it says is compared. */
    private fun sameStepExtras(recipe: JsonObject, body: JsonObject): Boolean {
        val theirs = stepsInOrder(recipe)
        return stepsInOrder(body).withIndex().all { (index, ours) ->
            val their = theirs.getOrNull(index) ?: return@all false
            ours.bool("show_as_header") == their.bool("show_as_header") &&
                ours.bool("show_ingredients_table") == their.bool("show_ingredients_table") &&
                ("step_recipe" !in ours || ours.long("step_recipe") == their.long("step_recipe")) &&
                ("file" !in ours || (ours["file"] as? JsonObject)?.long("id") == (their["file"] as? JsonObject)?.long("id"))
        }
    }

    private fun stepsInOrder(recipe: JsonObject): List<JsonObject> =
        recipe.array("steps").mapNotNull { it as? JsonObject }.sortedBy { it.int("order") ?: 0 }

    private fun sameOwnProperties(recipe: JsonObject, body: JsonObject): Boolean {
        if ("properties" !in body) return true
        fun valuesOf(json: JsonObject) = json.array("properties").mapNotNull { element ->
            val property = element as? JsonObject
            val type = (property?.get("property_type") as? JsonObject)?.long("id")
            type?.let { it to (property["property_amount"] as? JsonPrimitive)?.doubleOrNull }
        }.toSet()
        return valuesOf(recipe) == valuesOf(body)
    }

    private fun sameKeywords(recipe: JsonObject, body: JsonObject): Boolean {
        val theirs = recipe.array("keywords").mapNotNull { it as? JsonObject }
        val ours = body.array("keywords").mapNotNull { it as? JsonObject }
        return theirs.size == ours.size && ours.all { keyword ->
            val id = keyword.long("id")
            theirs.any { other -> if (id != null) other.long("id") == id else key(other.text("name")) == key(keyword.text("name")) }
        }
    }

    private fun stepsContent(recipe: JsonObject): List<List<String>> = recipe.array("steps").mapNotNull { it as? JsonObject }
        .sortedBy { it.int("order") ?: 0 }
        .map { step ->
            val lines = step.array("ingredients").mapNotNull { it as? JsonObject }.sortedBy { it.int("order") ?: 0 }.map { line ->
                val food = (line["food"] as? JsonObject)?.text("name").orEmpty()
                val unit = (line["unit"] as? JsonObject)?.text("name").orEmpty()
                val amount = (line["amount"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                listOf(amount, key(unit), key(food), line.text("note"), line.bool("is_header") == true, line.bool("no_amount") == true)
                    .joinToString("|")
            }
            listOf(step.text("name"), step.text("instruction"), step.text("time")) + lines
        }

    /**
     * [body] as the stored copy of an unsent edit: an edit's body has no sums of the foods, and may
     * leave out `properties` and a step's `file`, while the panel and the editor read the stored
     * JSON. Nothing else of [stored] comes along: `updated_at` is how a server copy is told from an
     * unsent body.
     */
    fun withStoredNutrition(body: JsonObject, stored: JsonObject?): JsonObject {
        val kept = NUTRITION_FIELDS.mapNotNull { field -> stored?.get(field)?.takeIf { field !in body }?.let { field to it } }
        val storedSteps = stored?.array("steps").orEmpty().mapNotNull { it as? JsonObject }.associateBy { it.long("id") }
        val steps = body.array("steps").map { element ->
            val step = element as? JsonObject ?: return@map element
            val file = storedSteps[step.long("id")]?.get("file")?.takeIf { "file" !in step && step.long("id") != null }
            if (file == null) step else JsonObject(step + ("file" to file))
        }
        return JsonObject(body + kept + ("steps" to JsonArray(steps)))
    }

    /** The property types as `sync_state` keeps them. */
    fun encodePropertyTypes(types: List<PropertyType>): String = buildJsonArray {
        types.forEach { type ->
            add(
                buildJsonObject {
                    put("id", type.id)
                    put("name", type.name)
                    put("unit", type.unit)
                    put("order", type.order)
                },
            )
        }
    }.toString()

    fun decodePropertyTypes(text: String?): List<PropertyType> {
        val types = text?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } as? JsonArray ?: return emptyList()
        return types.mapNotNull { element ->
            val type = element as? JsonObject ?: return@mapNotNull null
            PropertyType(
                id = type.long("id") ?: return@mapNotNull null,
                name = type.string("name") ?: return@mapNotNull null,
                unit = type.string("unit").orEmpty(),
                order = type.int("order") ?: 0,
            )
        }
    }

    /** The app-private copies of chosen step files [payload] stands for. */
    fun stepFilePathsOf(payload: String): List<String> = parse(payload)?.array("steps").orEmpty().mapNotNull { step ->
        filePathOf(step)
    }

    /** [body] with its step at [index] pointing at [fileId], the file it asked for now uploaded. */
    fun withUploadedFile(body: JsonObject, index: Int, fileId: Long): JsonObject = withStep(body, index) { step ->
        val file = buildJsonObject {
            put("id", fileId)
            put("name", fileNameOf(step))
        }
        step + ("file" to file)
    }

    /** [body] without the file of its step at [index]: the copy is gone, so the step stays as the server has it. */
    fun withoutFile(body: JsonObject, index: Int): JsonObject = withStep(body, index) { it - "file" }

    private fun withStep(body: JsonObject, index: Int, change: (JsonObject) -> Map<String, JsonElement>): JsonObject {
        val steps = body.array("steps").mapIndexed { position, element ->
            if (position == index && element is JsonObject) JsonObject(change(element)) else element
        }
        return JsonObject(body + ("steps" to JsonArray(steps)))
    }

    /** The name of the file of [step] in a row. */
    fun fileNameOf(step: JsonElement): String = ((step as? JsonObject)?.get("file") as? JsonObject)?.string("name").orEmpty()

    /** The app-private path of the file of [step] in a row, or null. */
    fun filePathOf(step: JsonElement): String? = ((step as? JsonObject)?.get("file") as? JsonObject)?.string(PATH)

    /** What goes to the server of a row whose files are uploaded: only the fields Tandoor reads. */
    fun forSending(body: JsonObject): JsonObject {
        if ("steps" !in body && "properties" !in body) return body
        val steps = body.array("steps").map { step -> if (step is JsonObject) JsonObject(step - "step_recipe_data") else step }
        val sent = JsonObject(body + ("steps" to JsonArray(steps)))
        if ("properties" !in body) return sent
        val properties = body.array("properties").map { element ->
            val property = element as? JsonObject ?: return@map element
            val type = property["property_type"] as? JsonObject ?: return@map element
            JsonObject(property + ("property_type" to JsonObject(type - "unit")))
        }
        return JsonObject(sent + ("properties" to JsonArray(properties)))
    }

    /** [payload] as an export shows it: a step's file by its name, never the app-private path. */
    fun withoutFilePaths(payload: JsonElement): JsonElement {
        val body = payload as? JsonObject ?: return payload
        if ("steps" !in body) return body
        val steps = body.array("steps").map { step ->
            val file = (step as? JsonObject)?.get("file") as? JsonObject
            if (file == null) step else JsonObject(step + ("file" to JsonObject(file - PATH)))
        }
        return JsonObject(body + ("steps" to JsonArray(steps)))
    }

    /** A `RECIPE_IMAGE` row: the app-private copy of the photo. */
    fun image(path: String): String = buildJsonObject { put("file", path) }.toString()

    /** A `RECIPE_IMAGE` row for a picture on the web (an import's `image_url`): the server downloads it. */
    fun imageUrl(url: String): String = buildJsonObject { put("image_url", url) }.toString()

    /** Fas 3's leftovers: a `RECIPE_IMAGE` row that removes the recipe's picture ("Ta bort bilden"). */
    fun imageRemoval(): String = buildJsonObject { put("remove", true) }.toString()

    fun isImageRemoval(payload: String): Boolean = parse(payload)?.bool("remove") == true

    fun imagePathOf(payload: String): String? = parse(payload)?.string("file")

    fun imageUrlOf(payload: String): String? = parse(payload)?.string("image_url")

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
                stepRecipe = step.stepRecipe,
                imageUrl = stored?.steps?.firstOrNull { it.id == step.id }?.takeIf { it.file?.id == step.file?.id }?.imageUrl,
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
            imageUrl = imageUrl ?: stored?.imageUrl?.takeIf { draft.keepImage },
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
            nutritionValues = stored?.nutritionValues.orEmpty(),
            properties = draft.properties ?: stored?.properties.orEmpty(),
            diameter = stored?.diameter ?: 0,
            diameterText = stored?.diameterText.orEmpty(),
            createdAt = stored?.createdAt ?: now,
            updatedAt = stored?.updatedAt,
        )
    }

    /** Names compare trimmed and without case (Tandoor reuses foods, units and keywords that way). */
    fun key(name: String): String = name.trim().lowercase()

    private fun JsonObject.string(field: String): String? = (this[field] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

    /** [field] as text, a missing value and null alike as empty (`description` null and "" say the same). */
    private fun JsonObject.text(field: String): String = string(field).orEmpty()

    private fun JsonObject.int(field: String): Int? = (this[field] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(field: String): Long? = (this[field] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.bool(field: String): Boolean? = (this[field] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.array(field: String): JsonArray = this[field] as? JsonArray ?: JsonArray(emptyList())
}
