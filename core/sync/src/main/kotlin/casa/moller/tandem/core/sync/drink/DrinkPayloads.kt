package casa.moller.tandem.core.sync.drink

import casa.moller.tandem.core.database.entity.RecipeKeywordFields
import casa.moller.tandem.core.domain.drink.DraftKeyword
import casa.moller.tandem.core.domain.drink.DraftKeywordRole
import casa.moller.tandem.core.domain.drink.PendingKeywordChange
import casa.moller.tandem.core.model.book.WantToCook
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Fas S (drycker lane): what one `RECIPE_KEYWORDS` row asks for, field by field in payload order
 * (docs/BYGGE.md "Fas S – kontrakt", `RecipeKeywordFields`): "Vill laga" (Phase 2), existing
 * keywords by id (`keyword:<id>`) and pairings by name (`pairing:<name>`).
 */
internal data class RecipeKeywordChanges(
    val wantToCook: Boolean? = null,
    val keywords: List<Pair<Long, Boolean>> = emptyList(),
    val pairings: List<Pair<String, Boolean>> = emptyList(),
) {
    val isEmpty: Boolean
        get() = wantToCook == null && keywords.isEmpty() && pairings.isEmpty()
}

/** The drinks lane's outbox payloads (`RECIPE_KEYWORDS` fields, `drink` CREATE, `RECIPE_IMAGE`, the draft's keywords). */
internal object DrinkPayloads {
    private val json = Json { ignoreUnknownKeys = true }

    /** Every change of a `RECIPE_KEYWORDS` payload; null when it is no JSON object. */
    fun changesOf(payload: String): RecipeKeywordChanges? {
        val body = parse(payload) ?: return null
        var want: Boolean? = null
        val keywords = mutableListOf<Pair<Long, Boolean>>()
        val pairings = mutableListOf<Pair<String, Boolean>>()
        for ((field, value) in body) {
            val flag = (value as? JsonPrimitive)?.booleanOrNull
            when {
                field == RecipeKeywordFields.WANT_TO_COOK ->
                    want = ((value as? JsonObject)?.get(WantToCook.KEYWORD) as? JsonPrimitive)?.booleanOrNull

                flag == null -> Unit

                RecipeKeywordFields.keywordIdOf(field) != null -> keywords += RecipeKeywordFields.keywordIdOf(field)!! to flag

                RecipeKeywordFields.pairingNameOf(field) != null -> pairings += RecipeKeywordFields.pairingNameOf(field)!! to flag
            }
        }
        return RecipeKeywordChanges(want, keywords, pairings)
    }

    /** The pairing changes of a queued row of [recipeId], for the offline overlay ([PendingKeywordChange]). */
    fun pendingChangesOf(recipeId: Long, payload: String): List<PendingKeywordChange> {
        val changes = changesOf(payload) ?: return emptyList()
        return changes.keywords.map { (id, add) -> PendingKeywordChange.ById(recipeId, id, add) } +
            changes.pairings.map { (name, add) -> PendingKeywordChange.PairingByName(recipeId, name, add) }
    }

    /** `RECIPE_KEYWORDS` payload (or base snapshot) of one keyword by id. */
    fun keyword(id: Long, add: Boolean): String = buildJsonObject { put(RecipeKeywordFields.keyword(id), add) }.toString()

    /** `RECIPE_KEYWORDS` payload (or base snapshot) of one pairing by name. */
    fun pairing(name: String, add: Boolean): String = buildJsonObject { put(RecipeKeywordFields.pairing(name.trim()), add) }.toString()

    /** The `drink_draft.keywords` JSON: `[{"name": "Vin", "role": "TYPE"}, ...]`. */
    fun encodeDraftKeywords(keywords: List<DraftKeyword>): String = buildJsonArray {
        keywords.forEach { keyword ->
            add(
                buildJsonObject {
                    put("name", keyword.name)
                    put("role", keyword.role.name)
                },
            )
        }
    }.toString()

    fun decodeDraftKeywords(text: String): List<DraftKeyword> = runCatching {
        json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
            val item = element.jsonObject
            val name = (item["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val stored = (item["role"] as? JsonPrimitive)?.contentOrNull
            val role = DraftKeywordRole.entries.firstOrNull { it.name == stored }
            if (name.isEmpty() || role == null) null else DraftKeyword(name, role)
        }
    }.getOrDefault(emptyList())

    /** A `drink` CREATE row: the sender reads the draft itself (the latest pairings), so the payload only names it. */
    fun createDrink(localId: Long): String = buildJsonObject { put("draft", localId) }.toString()

    /** A `RECIPE_IMAGE` row: the app-private copy of the photo. */
    fun image(path: String): String = buildJsonObject { put("file", path) }.toString()

    fun imagePathOf(payload: String): String? = (parse(payload)?.get("file") as? JsonPrimitive)?.contentOrNull

    fun parse(payload: String): JsonObject? = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject
}
