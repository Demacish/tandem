package casa.moller.tandem.core.sync.recipeimport

import casa.moller.tandem.core.data.recipe.KeptRecipeImport
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.SyncEntities
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.model.recipe.DraftIngredient
import casa.moller.tandem.core.model.recipe.DraftKeyword
import casa.moller.tandem.core.model.recipe.DraftStep
import casa.moller.tandem.core.model.recipe.ImportDuplicate
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.model.recipe.RecipeImportResult
import casa.moller.tandem.core.sync.account.ActiveSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Forgets what was kept for [ActiveSource.Stored]: the account is left (tests record the call). */
internal fun interface KeptImportCleaner {
    suspend fun clear(source: ActiveSource.Stored)
}

/**
 * Fas 4, komplettering 3: an AI answer that cost credits outlives the process. The notification says
 * "klart att granska", but the answer lived in the import screen's ViewModel, and `SavedStateHandle`
 * cannot hold it: the answer often comes after `onSaveInstanceState`. So it is stored here the moment
 * it arrives, before anyone is told, as JSON in the `hash` column of one `sync_state` row of the
 * account (no schema change; `sync_state` goes with the account). The row holds the draft and the
 * path of the photo beside it, never a key. It is deleted when the review ends, and with the account
 * switch ([AccountNotificationCleanup][casa.moller.tandem.core.sync.platform.AccountNotificationCleanup]).
 *
 * Best effort: storage trouble never fails an import, the answer is then on the screen only. A draft
 * larger than [MAX_CHARS] is not stored either: every reader of `sync_state` would pay for it, and
 * SQLite's cursor window takes about 2 MB per row.
 */
@Singleton
internal class KeptImportStore @Inject constructor(private val databases: TandemDatabaseFactory) : KeptImportCleaner {

    suspend fun save(source: ActiveSource.Stored, kept: KeptRecipeImport) {
        bestEffort {
            val text = KeptImportJson.encode(kept)
            val dao = databases.get(source.kind).syncStateDao()
            if (text.length > MAX_CHARS) {
                dao.delete(source.accountId, SyncEntities.KEPT_AI_IMPORT)
            } else {
                dao.upsert(SyncStateEntity(accountId = source.accountId, entity = SyncEntities.KEPT_AI_IMPORT, hash = text))
            }
        }
    }

    suspend fun read(source: ActiveSource.Stored): KeptRecipeImport? = bestEffort {
        KeptImportJson.decode(databases.get(source.kind).syncStateDao().get(source.accountId, SyncEntities.KEPT_AI_IMPORT)?.hash)
    }

    override suspend fun clear(source: ActiveSource.Stored) {
        bestEffort { databases.get(source.kind).syncStateDao().delete(source.accountId, SyncEntities.KEPT_AI_IMPORT) }
    }

    /** A database that cannot be read or written: the answer stays on the screen. Its content is never logged. */
    private inline fun <T> bestEffort(block: () -> T): T? = (catchingUnexpected { Outcome.Success(block()) } as? Outcome.Success)?.value

    private companion object {
        const val MAX_CHARS = 512 * 1024
    }
}

/**
 * `sync_state` [SyncEntities.KEPT_AI_IMPORT] (core:model has no serialization annotations, decision
 * B2). Only what an import can put in a draft: a local picture, a linked sub-recipe, a step's file and
 * the recipe's own values come from the editor, after the review.
 */
internal object KeptImportJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(kept: KeptRecipeImport): String = json.encodeToString(Kept.serializer(), Kept.of(kept))

    fun decode(text: String?): KeptRecipeImport? = text?.let {
        try {
            json.decodeFromString(Kept.serializer(), it).toModel()
        } catch (e: IllegalArgumentException) {
            // SerializationException is one: a row from another version is no answer to show.
            null
        }
    }

    @Serializable
    private data class Kept(
        val draft: Draft,
        val imageUrls: List<String> = emptyList(),
        val duplicates: List<Duplicate> = emptyList(),
        val sourcePath: String? = null,
    ) {
        fun toModel() = KeptRecipeImport(
            RecipeImportResult(draft.toModel(), imageUrls, duplicates.map { ImportDuplicate(it.id, it.name) }),
            sourcePath,
        )

        companion object {
            fun of(kept: KeptRecipeImport) = Kept(
                Draft.of(kept.result.draft),
                kept.result.imageUrls,
                kept.result.duplicates.map { Duplicate(it.id, it.name) },
                kept.sourcePath,
            )
        }
    }

    @Serializable
    private data class Duplicate(val id: Long, val name: String)

    @Serializable
    private data class Draft(
        val name: String = "",
        val description: String = "",
        val servings: Int = 1,
        val servingsText: String = "",
        val workingMinutes: Int = 0,
        val waitingMinutes: Int = 0,
        val sourceUrl: String = "",
        val keywords: List<Keyword> = emptyList(),
        val steps: List<Step> = emptyList(),
        val imageUrl: String? = null,
    ) {
        fun toModel() = RecipeDraft(
            name = name,
            description = description,
            servings = servings,
            servingsText = servingsText,
            workingMinutes = workingMinutes,
            waitingMinutes = waitingMinutes,
            sourceUrl = sourceUrl,
            keywords = keywords.map { DraftKeyword(it.id, it.name) },
            steps = steps.map { it.toModel() },
            imageUrl = imageUrl,
        )

        companion object {
            fun of(draft: RecipeDraft) = Draft(
                name = draft.name,
                description = draft.description,
                servings = draft.servings,
                servingsText = draft.servingsText,
                workingMinutes = draft.workingMinutes,
                waitingMinutes = draft.waitingMinutes,
                sourceUrl = draft.sourceUrl,
                keywords = draft.keywords.map { Keyword(it.id, it.name) },
                steps = draft.steps.map { Step.of(it) },
                imageUrl = draft.imageUrl,
            )
        }
    }

    @Serializable
    private data class Keyword(val id: Long? = null, val name: String)

    @Serializable
    private data class Step(
        val name: String = "",
        val instruction: String = "",
        val timeMinutes: Int = 0,
        val showAsHeader: Boolean = false,
        val showIngredientsTable: Boolean = true,
        val ingredients: List<Line> = emptyList(),
    ) {
        fun toModel() = DraftStep(
            name = name,
            instruction = instruction,
            timeMinutes = timeMinutes,
            showAsHeader = showAsHeader,
            showIngredientsTable = showIngredientsTable,
            ingredients = ingredients.map { DraftIngredient(null, it.amount, it.unit, it.food, it.note, it.isHeader, it.noAmount) },
        )

        companion object {
            fun of(step: DraftStep) = Step(
                name = step.name,
                instruction = step.instruction,
                timeMinutes = step.timeMinutes,
                showAsHeader = step.showAsHeader,
                showIngredientsTable = step.showIngredientsTable,
                ingredients = step.ingredients.map { Line(it.amount, it.unit, it.food, it.note, it.isHeader, it.noAmount) },
            )
        }
    }

    @Serializable
    private data class Line(
        val amount: Double = 0.0,
        val unit: String? = null,
        val food: String? = null,
        val note: String = "",
        val isHeader: Boolean = false,
        val noAmount: Boolean = false,
    )
}
