package casa.moller.tandem.contract

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Fas 4, komplettering 2 (#22, decision B9): what the recipe editor's new controls send, as the
 * app's outbox sends it: a whole-recipe PATCH with step ids. The flags `show_as_header`,
 * `show_ingredients_table` and `private`; a linked sub-recipe (`step_recipe`, also taken off, also
 * a recipe linked to itself or in a circle); a step file (`POST /api/user-file/`, then `file`
 * `{id, name}` in the step, also left out and null); the recipe's own nutrition (`properties` with
 * `property_type` `{id, name}`, also changed, left out and emptied) and the property types the
 * editor offers.
 *
 * Recordings go to `build/contract-recordings/<tag>/fas4/editor/` and are committed to
 * `api-schema/fixtures/<version>/fas4/editor/`. Soft checks as in the other phases: every fact is
 * recorded and the last step compares [EXPECTED].
 *
 * Skipped unless TANDOOR_FAS1_TOKEN is set. Never run against a production server.
 */
@EnabledIfEnvironmentVariable(named = "TANDOOR_FAS1_TOKEN", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Phase4EditorContractTest {
    private val env = ContractEnv
    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")
    private val today: LocalDate = LocalDate.now(zone)
    private val sanitizer = Sanitizer(listOf(env.password, env.noSpacePassword), runToday = today)
    private val proxyHeaders = mapOf("Host" to ContractEnv.PUBLIC_HOST, "X-Forwarded-Proto" to "https")
    private val recordDir = File(env.recordDir, "fas4/editor")
    private val http = TandoorHttp(env.baseUrl, sanitizer, recordDir, numbered = false, defaultHeaders = proxyHeaders)
    private val facts = Facts()
    private val mismatches = mutableListOf<String>()

    private val recipes = mutableListOf<Long>()
    private val types = mutableListOf<Long>()

    @BeforeAll
    fun setUp() {
        assertTrue(env.fas1Token.isNotBlank(), "TANDOOR_FAS1_TOKEN must be set")
        sanitizer.fakeTokenFor(env.fas1Token)
        recordDir.deleteRecursively()
        recordDir.mkdirs()
        facts["tandoorTag"] = env.tag
        facts["runDay"] = today.toString()
    }

    @AfterAll
    fun cleanUp() {
        recipes.forEach { api("cleanup-recipe", "DELETE", "/api/recipe/$it/", record = false) }
        types.forEach { api("cleanup-type", "DELETE", "/api/property-type/$it/", record = false) }
        facts["mismatches"] = mismatches
        facts.write(File(recordDir, "observed-facts.json"), sanitizer)
    }

    @Test
    @Order(10)
    fun flags() {
        val id = recipe("flaggor", "Tandem flaggor", listOf(step("Blanda.", order = 0))) ?: return
        val stepId = stepIdOf(id, "flaggor-read") ?: return
        val patched = patch(
            "flaggor-patch",
            id,
            "private" to true,
            "steps" to JsonArray(
                listOf(step("Blanda.", order = 0, id = stepId, extra = arrayOf("show_as_header" to true, "show_ingredients_table" to false))),
            ),
        )
        observe("flags.patch.status", patched.status)
        val read = api("flaggor-after", "GET", "/api/recipe/$id/").json as? JsonObject
        observe("flags.private", read?.boolAt("private"))
        val step = read?.arrAt("steps").objects().firstOrNull()
        observe("flags.stepKeptItsId", step?.longAt("id") == stepId)
        observe("flags.showAsHeader", step?.boolAt("show_as_header"))
        observe("flags.showIngredientsTable", step?.boolAt("show_ingredients_table"))
    }

    @Test
    @Order(20)
    fun subRecipes() {
        val parts = recipe("del-a", "Tandem del A", listOf(step("Del A.", order = 0))) ?: return
        val other = recipe("del-b", "Tandem del B", listOf(step("Del B.", order = 0))) ?: return
        val whole = recipe(
            "del-hel",
            "Tandem hel",
            listOf(step("Med del.", order = 0, extra = arrayOf("step_recipe" to parts))),
        ) ?: return
        var read = api("del-created", "GET", "/api/recipe/$whole/").json as? JsonObject
        var step = read?.arrAt("steps").objects().firstOrNull()
        val stepId = step?.longAt("id") ?: return
        observe("sub.create.stepRecipe", step.longAt("step_recipe") == parts)
        observe("sub.create.dataName", step.objAt("step_recipe_data")?.stringAt("name"))

        // What the editor sends: the step by id with the new link, a link taken off with null.
        fun link(name: String, to: Long?) = patch(
            name,
            whole,
            "steps" to JsonArray(listOf(step("Med del.", order = 0, id = stepId, extra = arrayOf("step_recipe" to to)))),
        )
        observe("sub.change.status", link("del-andra", other).status)
        read = api("del-andrad", "GET", "/api/recipe/$whole/").json as? JsonObject
        step = read?.arrAt("steps").objects().firstOrNull()
        observe("sub.change.stepRecipe", step?.longAt("step_recipe") == other)
        observe("sub.change.dataName", step?.objAt("step_recipe_data")?.stringAt("name"))

        // Left out: stays (a kept edit written by an older version of the app has no such field).
        val without = patch(
            "del-utelamnad",
            whole,
            "steps" to JsonArray(listOf(step("Med del, ändrad.", order = 0, id = stepId))),
        )
        observe("sub.leftOut.status", without.status)
        read = api("del-kvar", "GET", "/api/recipe/$whole/").json as? JsonObject
        observe("sub.leftOut.stepRecipe", read?.arrAt("steps").objects().firstOrNull()?.longAt("step_recipe") == other)

        observe("sub.remove.status", link("del-bort", null).status)
        read = api("del-borttagen", "GET", "/api/recipe/$whole/").json as? JsonObject
        step = read?.arrAt("steps").objects().firstOrNull()
        observe("sub.remove.stepRecipe", step?.get("step_recipe"))
        observe("sub.remove.data", step?.get("step_recipe_data"))

        // Itself and a circle: what the server says, and whether the recipe can still be read.
        observe("sub.self.status", link("del-sig-sjalv", whole).status)
        observe("sub.self.readStatus", api("del-sig-sjalv-las", "GET", "/api/recipe/$whole/").status)
        link("del-cirkel-1", other)
        val otherStep = stepIdOf(other, "del-b-las") ?: return
        val circle = patch(
            "del-cirkel-2",
            other,
            "steps" to JsonArray(listOf(step("Del B.", order = 0, id = otherStep, extra = arrayOf("step_recipe" to whole)))),
        )
        observe("sub.circle.status", circle.status)
        observe("sub.circle.readStatus", api("del-cirkel-las", "GET", "/api/recipe/$whole/").status)
        observe("sub.unknown.status", link("del-okand", UNKNOWN_ID).status)
        // Undone, so cleaning up is not held back.
        link("del-cirkel-bort-1", null)
        patch("del-cirkel-bort-2", other, "steps" to JsonArray(listOf(step("Del B.", order = 0, id = otherStep))))
    }

    @Test
    @Order(30)
    fun stepFiles() {
        val id = recipe("fil", "Tandem stegfil", listOf(step("Med fil.", order = 0))) ?: return
        val stepId = stepIdOf(id, "fil-las") ?: return
        val upload = upload("fil-upp", "Receptblad.pdf", "tandem-receptblad.pdf", TINY_PDF, PDF)
        observe("file.upload.status", upload.status)
        val file = upload.json as? JsonObject ?: return
        val fileId = file.longAt("id") ?: return
        observe("file.upload.name", file.stringAt("name"))
        observe("file.upload.preview", file.stringAt("preview"))

        fun steps(vararg extra: Pair<String, Any?>) =
            "steps" to JsonArray(listOf(step("Med fil.", order = 0, id = stepId, extra = arrayOf(*extra))))
        observe("file.attach.status", patch("fil-pa", id, steps("file" to obj("id" to fileId, "name" to "Receptblad.pdf"))).status)
        var step = (api("fil-pasatt", "GET", "/api/recipe/$id/").json as? JsonObject)?.arrAt("steps").objects().firstOrNull()
        observe("file.attach.id", step?.objAt("file")?.longAt("id") == fileId)
        observe("file.attach.name", step?.objAt("file")?.stringAt("name"))
        observe("file.attach.fieldNames", step?.objAt("file")?.keys?.sorted())

        // What a stored step's file looks like when the app sends it back: as read, with the extra fields.
        val asRead = step?.objAt("file")
        observe("file.asRead.status", patch("fil-som-last", id, steps("file" to asRead)).status)

        observe("file.leftOut.status", patch("fil-utelamnad", id, steps()).status)
        step = (api("fil-kvar", "GET", "/api/recipe/$id/").json as? JsonObject)?.arrAt("steps").objects().firstOrNull()
        observe("file.leftOut.id", step?.objAt("file")?.longAt("id") == fileId)

        observe("file.remove.status", patch("fil-bort", id, steps("file" to null)).status)
        step = (api("fil-borttagen", "GET", "/api/recipe/$id/").json as? JsonObject)?.arrAt("steps").objects().firstOrNull()
        observe("file.remove.file", step?.get("file"))
        // Off the step, the file itself can go.
        observe("file.deleteUnused.status", api("fil-radera", "DELETE", "/api/user-file/$fileId/", record = false).status)

        val listed = api("fil-lista", "GET", "/api/user-file/?page_size=5")
        observe("file.list.status", listed.status)
        observe("file.list.shape", (listed.json as? JsonObject)?.keys?.sorted())
        val tooLong = upload("fil-langt-namn", "x".repeat(LONG_NAME), "tandem-langt.pdf", TINY_PDF, PDF)
        observe("file.longName.status", tooLong.status)
        (tooLong.json as? JsonObject)?.longAt("id")?.let { api("fil-radera-lang", "DELETE", "/api/user-file/$it/", record = false) }
    }

    @Test
    @Order(40)
    fun ownNutrition() {
        val energy = type("typ-energi", "Tandem editor energi", "kcal") ?: return
        val protein = type("typ-protein", "Tandem editor protein", "g") ?: return
        val typeList = api("typ-lista", "GET", "/api/property-type/?page_size=100")
        observe("properties.types.status", typeList.status)
        val typeBody = typeList.json as? JsonObject
        observe("properties.types.shape", typeBody?.keys?.sorted())
        observe("properties.types.fieldNames", typeBody?.arrAt("results").objects().firstOrNull()?.keys?.sorted())

        val id = recipe("naring", "Tandem näring", listOf(step("Blanda.", order = 0))) ?: return
        fun property(type: JsonObject, amount: Double?, id: Long? = null): JsonObject {
            val own = obj(
                "property_type" to obj("id" to type.longAt("id"), "name" to type.stringAt("name")),
                "property_amount" to amount,
            )
            return if (id == null) own else obj("id" to id).let { JsonObject(it + own) }
        }
        fun read(name: String): List<JsonObject> = (api(name, "GET", "/api/recipe/$id/").json as? JsonObject)?.arrAt("properties").objects()

        observe("properties.add.status", patch("naring-ny", id, "properties" to JsonArray(listOf(property(energy, 350.0)))).status)
        var stored = read("naring-ny-las")
        observe("properties.add.values", stored.map { it.doubleAt("property_amount") })
        observe("properties.add.fieldNames", stored.firstOrNull()?.keys?.sorted())
        observe("properties.add.typeFieldNames", stored.firstOrNull()?.objAt("property_type")?.keys?.sorted())
        val propertyId = stored.firstOrNull()?.longAt("id") ?: return

        val changed = patch(
            "naring-andra",
            id,
            "properties" to JsonArray(listOf(property(energy, 400.0, propertyId), property(protein, 12.5))),
        )
        observe("properties.change.status", changed.status)
        stored = read("naring-andrad-las")
        observe("properties.change.values", stored.mapNotNull { it.doubleAt("property_amount") }.sorted())
        observe("properties.change.keptItsId", stored.any { it.longAt("id") == propertyId })

        observe("properties.leftOut.status", patch("naring-utelamnad", id, "description" to "Utan properties.").status)
        observe("properties.leftOut.count", read("naring-utelamnad-las").size)

        val withoutId = patch("naring-utan-id", id, "properties" to JsonArray(listOf(property(energy, 410.0))))
        observe("properties.withoutId.status", withoutId.status)
        observe("properties.withoutId.values", read("naring-utan-id-las").mapNotNull { it.doubleAt("property_amount") })

        observe("properties.empty.status", patch("naring-tom", id, "properties" to JsonArray(emptyList())).status)
        observe("properties.empty.count", read("naring-tom-las").size)

        val twice = patch("naring-dubbel", id, "properties" to JsonArray(listOf(property(energy, 1.0), property(energy, 2.0))))
        observe("properties.sameTypeTwice.status", twice.status)
        observe("properties.sameTypeTwice.count", read("naring-dubbel-las").size)
        val nullAmount = patch("naring-null", id, "properties" to JsonArray(listOf(property(energy, null))))
        observe("properties.nullAmount.status", nullAmount.status)
        patch("naring-stad", id, "properties" to JsonArray(emptyList()))
    }

    @Test
    @Order(90)
    fun compareWithBaseline() {
        for ((key, expected) in EXPECTED) {
            if (facts[key] != Facts.toJson(expected)) mismatches += "$key: expected ${Facts.toJson(expected)}, observed ${facts[key]}"
        }
        assertTrue(mismatches.isEmpty(), "Observed facts differ from the editor baseline:\n" + mismatches.joinToString("\n"))
    }

    private fun type(name: String, type: String, unit: String): JsonObject? {
        val created = api(name, "POST", "/api/property-type/", obj("name" to type, "unit" to unit))
        val saved = created.json as? JsonObject ?: return null
        saved.longAt("id")?.let { types += it }
        return saved
    }

    private fun step(instruction: String, order: Int, id: Long? = null, extra: Array<out Pair<String, Any?>> = emptyArray()): JsonObject {
        val own = listOf(
            "name" to "",
            "instruction" to instruction,
            "time" to 0,
            "order" to order,
            "show_as_header" to false,
            "show_ingredients_table" to true,
            "ingredients" to JsonArray(emptyList()),
        )
        val idField = if (id != null) listOf("id" to id) else emptyList()
        return JsonObject((own + idField + extra).associate { (k, v) -> k to Facts.toJson(v) })
    }

    private fun recipe(slug: String, name: String, steps: List<JsonObject>): Long? {
        val body = obj(
            "name" to name,
            "description" to "",
            "servings" to 2,
            "servings_text" to "",
            "working_time" to 0,
            "waiting_time" to 0,
            "internal" to true,
            "keywords" to JsonArray(emptyList()),
            "steps" to JsonArray(steps),
        )
        val created = api("recept-$slug", "POST", "/api/recipe/", body)
        observe("recipe.$slug.status", created.status)
        val id = (created.json as? JsonObject)?.longAt("id") ?: return null
        recipes += id
        return id
    }

    private fun stepIdOf(recipe: Long, name: String): Long? =
        (api(name, "GET", "/api/recipe/$recipe/").json as? JsonObject)?.arrAt("steps").objects().firstOrNull()?.longAt("id")

    private fun patch(name: String, recipe: Long, vararg fields: Pair<String, Any?>): Exchange =
        api(name, "PATCH", "/api/recipe/$recipe/", obj(*fields))

    private fun upload(name: String, fileName: String, uploadName: String, bytes: ByteArray, type: MediaType): Exchange {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("name", fileName)
            .addFormDataPart("file", uploadName, bytes.toRequestBody(type))
            .build()
        val recordBody = obj("name" to fileName, "file" to "<multipart file $uploadName, $type, ${bytes.size} bytes>")
        return http.call(name, "POST", "/api/user-file/", bearer = env.fas1Token, body = body, recordBody = recordBody)
    }

    private fun api(name: String, method: String, path: String, body: JsonElement? = null, record: Boolean = true): Exchange = http.call(
        name,
        method,
        path,
        bearer = env.fas1Token,
        body = (body ?: JsonObject(emptyMap()).takeIf { method in BODY_METHODS })?.toString()?.toRequestBody(JSON),
        recordBody = body,
        record = record,
    )

    private fun observe(key: String, value: Any?) {
        facts[key] = value
    }

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to Facts.toJson(v) })

    private companion object {
        val JSON = "application/json".toMediaType()
        val PDF = "application/pdf".toMediaType()
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        val TINY_PDF: ByteArray = "%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n".toByteArray()
        const val UNKNOWN_ID = 99_999_999L
        const val LONG_NAME = 200

        val EXPECTED: Map<String, Any?> = linkedMapOf()
    }
}
