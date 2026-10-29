package casa.moller.tandem.core.sync.drink

import casa.moller.tandem.core.data.demo.DemoLanguage
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.entity.KeywordEntity
import casa.moller.tandem.core.database.entity.RecipeKeywordCrossRef
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.database.mapper.toDetailWrite
import casa.moller.tandem.core.domain.drink.DraftKeyword
import casa.moller.tandem.core.domain.drink.DraftKeywordRole
import casa.moller.tandem.core.model.drink.Drink
import casa.moller.tandem.core.model.drink.DrinkDraft
import casa.moller.tandem.core.model.drink.DrinkKeywords
import casa.moller.tandem.core.network.dto.KeywordDto
import casa.moller.tandem.core.network.dto.RecipeDto
import casa.moller.tandem.core.network.json.TandoorJson
import casa.moller.tandem.core.sync.account.DEMO_ACCOUNT_ID
import casa.moller.tandem.core.sync.remote.ImageResolver
import casa.moller.tandem.core.sync.remote.toRecipe
import java.io.File
import java.util.Locale

/**
 * Fas S (drycker lane): the demo's drinks and pairings (plan 6.2 "Demoläge", §8.8), written into
 * the demo database like a server would answer them: three drinks (a wine, a beer, a sparkling
 * apple juice), each paired with two demo dishes, and a pairing with a kind of drink ("Friskt
 * vitt") on the salmon and the chicken. The keywords keep the server names ("Dryck", "Passar med",
 * the Swedish types) in both languages, like a real household's (docs/BYGGE.md "Drycker"); the
 * drink names and the type-only pairing follow the demo's language. Never the network.
 *
 * The demo content's own seeding replaces the recipe index and the keyword list (each new day),
 * which removes these rows, so they are written again whenever it ran ([ensureSeeded]).
 */
internal object DemoDrinks {
    const val SEED_KEY: String = "demo_drinks_seed"

    private const val DRINK_ROOT = 900L
    private const val WINE = 901L
    private const val BEER = 902L
    private const val ALCOHOL_FREE = 903L
    private const val PAIRS_WITH = 910L

    private class DemoDrink(
        val id: Long,
        val pairingId: Long,
        val type: Long,
        val name: Map<DemoLanguage, String>,
        val note: Map<DemoLanguage, String>,
        val rating: Double?,
        val dishes: List<Long>,
    )

    private val drinks = listOf(
        DemoDrink(
            id = 901,
            pairingId = 911,
            type = WINE,
            name = mapOf(DemoLanguage.SWEDISH to "Rioja Crianza", DemoLanguage.ENGLISH to "Rioja Crianza"),
            note = mapOf(
                DemoLanguage.SWEDISH to "Fruktigt och kryddigt. Ca 120 kr på Systembolaget.",
                DemoLanguage.ENGLISH to "Fruity and spicy. About 120 kr.",
            ),
            rating = 4.0,
            // Spaghetti with meat sauce, meatballs.
            dishes = listOf(9, 8),
        ),
        DemoDrink(
            id = 902,
            pairingId = 912,
            type = BEER,
            name = mapOf(DemoLanguage.SWEDISH to "Ljus lager", DemoLanguage.ENGLISH to "Pale lager"),
            note = mapOf(DemoLanguage.SWEDISH to "Kall och frisk.", DemoLanguage.ENGLISH to "Cold and crisp."),
            rating = null,
            // Friday tacos, stir-fried noodles.
            dishes = listOf(10, 12),
        ),
        DemoDrink(
            id = 903,
            pairingId = 913,
            type = ALCOHOL_FREE,
            name = mapOf(DemoLanguage.SWEDISH to "Äppelmust", DemoLanguage.ENGLISH to "Sparkling apple juice"),
            note = mapOf(DemoLanguage.SWEDISH to "Från gårdsbutiken.", DemoLanguage.ENGLISH to "From the farm shop."),
            rating = 5.0,
            // Friday tacos, pancakes.
            dishes = listOf(10, 7),
        ),
    )

    private const val CRISP_WHITE = 914L
    private val crispWhite = mapOf(DemoLanguage.SWEDISH to "Friskt vitt", DemoLanguage.ENGLISH to "Crisp white")

    /** Salmon, lemon chicken. */
    private val crispWhiteDishes = listOf(1L, 3L)

    /**
     * Writes the demo drinks when the demo content was (re)written ([contentWritten]) or the
     * drinks were never seeded for this content version and language; otherwise the user's demo
     * pairings stay as they are.
     */
    suspend fun ensureSeeded(
        database: TandemDatabase,
        contentVersion: Int,
        locale: Locale,
        nowEpochMillis: Long,
        contentWritten: Boolean,
    ) {
        val language = DemoLanguage.of(locale)
        val key = "v$contentVersion|${language.tag}"
        val state = database.syncStateDao().get(DEMO_ACCOUNT_ID, SEED_KEY)
        val dao = database.drinkDraftDao()
        if (!contentWritten && state?.hash == key && dao.getRecipeName(DEMO_ACCOUNT_ID, drinks.first().id) != null) return
        dao.upsertKeywords(keywords(language))
        for (drink in drinks) {
            val keywords = listOf(
                keyword(DRINK_ROOT, DrinkKeywords.DRINK, null),
                keyword(drink.type, typeName(drink.type), DRINK_ROOT),
                keyword(drink.pairingId, drink.name.getValue(language), PAIRS_WITH),
            )
            val name = drink.name.getValue(language)
            writeRecipe(database, drink.id, name, drink.note.getValue(language), drink.rating, keywords, image = null, nowEpochMillis)
            dao.insertKeywordRefs(drink.dishes.map { RecipeKeywordCrossRef(DEMO_ACCOUNT_ID, it, drink.pairingId) })
        }
        dao.insertKeywordRefs(crispWhiteDishes.map { RecipeKeywordCrossRef(DEMO_ACCOUNT_ID, it, CRISP_WHITE) })
        database.syncStateDao().upsert(SyncStateEntity(accountId = DEMO_ACCOUNT_ID, entity = SEED_KEY, hash = key))
    }

    /** "Ny dryck" in demo mode: the drink is a demo recipe at once (no outbox), its photo the app-private copy. */
    suspend fun create(
        database: TandemDatabase,
        accountId: String,
        draft: DrinkDraft,
        keywords: List<DraftKeyword>,
        photo: File?,
        nowEpochMillis: Long,
    ): Drink {
        val dao = database.drinkDraftDao()
        val stored = dao.getKeywords(accountId)
        var next = dao.getMaxKeywordId(accountId)
        val added = mutableListOf<KeywordEntity>()

        fun find(name: String, parentId: Long?): Long = (stored + added)
            .firstOrNull { DrinkKeywords.key(it.name) == DrinkKeywords.key(name) && it.parentId == parentId }?.id
            ?: (++next).also { added += KeywordEntity(accountId, it, name, parentId = parentId) }

        val root = stored.firstOrNull { it.parentId == null && DrinkKeywords.isDrinkRoot(it.name) }?.id ?: find(DrinkKeywords.DRINK, null)
        val pairsWith = stored.firstOrNull { it.parentId == null && DrinkKeywords.isPairsWithRoot(it.name) }?.id
            ?: find(DrinkKeywords.PAIRS_WITH, null)
        val linked = keywords.map { keyword ->
            when (keyword.role) {
                DraftKeywordRole.DRINK -> keyword(root, DrinkKeywords.DRINK, null)
                DraftKeywordRole.TYPE -> keyword(find(keyword.name, root), keyword.name, root)
                DraftKeywordRole.PAIRING -> keyword(find(keyword.name, pairsWith), keyword.name, pairsWith)
            }
        }.distinctBy { it.id }
        if (added.isNotEmpty()) dao.upsertKeywords(added)
        val id = dao.getMaxRecipeId(accountId).coerceAtLeast(DEMO_CREATED_FIRST_ID - 1) + 1
        val image = photo?.let { "file://${it.path}" }
        writeRecipe(database, id, draft.name, draft.description.trim(), null, linked, image, nowEpochMillis, accountId)
        return Drink(
            id = id,
            name = draft.name,
            imageUrl = image,
            types = keywords.filter { it.role == DraftKeywordRole.TYPE }.map { it.name },
            note = draft.description.trim().ifEmpty { null },
        )
    }

    private const val DEMO_CREATED_FIRST_ID = 1_000L

    private fun keywords(language: DemoLanguage): List<KeywordEntity> = listOf(
        KeywordEntity(DEMO_ACCOUNT_ID, DRINK_ROOT, DrinkKeywords.DRINK),
        KeywordEntity(DEMO_ACCOUNT_ID, WINE, typeName(WINE), parentId = DRINK_ROOT),
        KeywordEntity(DEMO_ACCOUNT_ID, BEER, typeName(BEER), parentId = DRINK_ROOT),
        KeywordEntity(DEMO_ACCOUNT_ID, ALCOHOL_FREE, typeName(ALCOHOL_FREE), parentId = DRINK_ROOT),
        KeywordEntity(DEMO_ACCOUNT_ID, PAIRS_WITH, DrinkKeywords.PAIRS_WITH),
        KeywordEntity(DEMO_ACCOUNT_ID, CRISP_WHITE, crispWhite.getValue(language), parentId = PAIRS_WITH),
    ) + drinks.map { KeywordEntity(DEMO_ACCOUNT_ID, it.pairingId, it.name.getValue(language), parentId = PAIRS_WITH) }

    private fun typeName(id: Long): String = when (id) {
        WINE -> "Vin"
        BEER -> "Öl"
        else -> "Alkoholfritt"
    }

    private fun keyword(id: Long, name: String, parentId: Long?): KeywordDto =
        KeywordDto(id = id, name = name, label = name, parent = parentId, fullName = name)

    /** A drink as `GET /api/recipe/{id}/` answers it (no steps, FACTS "Fas S f"), stored as its detail. */
    private suspend fun writeRecipe(
        database: TandemDatabase,
        id: Long,
        name: String,
        note: String,
        rating: Double?,
        keywords: List<KeywordDto>,
        image: String?,
        nowEpochMillis: Long,
        accountId: String = DEMO_ACCOUNT_ID,
    ) {
        val dto = RecipeDto(
            id = id,
            name = name,
            description = note,
            image = image,
            keywords = keywords,
            steps = emptyList(),
            servings = 1,
            rating = rating,
            isInternal = true,
        )
        val json = TandoorJson.encodeToJsonElement(RecipeDto.serializer(), dto)
        val recipe = dto.toRecipe(ImageResolver.AsIs) ?: return
        val write = recipe.toDetailWrite(accountId, rawJson = json.toString(), fetchedAtEpochMillis = nowEpochMillis)
        database.recipeDao().replaceDetail(accountId, write)
    }
}
