package casa.moller.tandem.core.sync.recipeimport

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.recipe.RecipeImportProblem
import casa.moller.tandem.core.data.recipe.RecipeImportRepository
import casa.moller.tandem.core.data.server.ServerStatusRepository
import casa.moller.tandem.core.data.sync.ImportGate
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.model.recipe.RecipeImportResult
import casa.moller.tandem.core.model.recipe.RecipeImportSource
import casa.moller.tandem.core.model.server.ServerFeature
import casa.moller.tandem.core.network.dto.RecipeFromSourceResponseDto
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.account.identity
import casa.moller.tandem.core.sync.remote.RemoteCaller
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * [RecipeImportRepository] on the server's import (plan §8.9 "Importera recept"; docs/BYGGE.md
 * "Fas 3 – import"): an ONLINE call in the foreground (the user waits for it; the circuit breaker
 * and the login check of [RemoteCaller] apply, a refused write never logs the account out),
 * never retried by itself and never an outbox row.
 *
 * - A link: `POST /api/recipe-from-source/` `{url}`; `error: true`, a 400 or 500 (the server
 *   could not fetch or read the page) or a recipe without a name is [RecipeImportProblem.NOTHING_FOUND].
 * - A text: read on the device by [TextRecipeParser] (the server answers 500 for plain text),
 *   with the stored units; works offline and in demo mode.
 * - A file: `POST /api/ai-import/` multipart with the space's default provider
 *   (`space/current` `ai_default_provider.id`, read fresh: the admin may have just set it);
 *   without one, or when the server refuses (400), [RecipeImportProblem.AI_UNAVAILABLE].
 * - A page a WebView read (#38, Fas 3's leftovers): `{url, data: <html>}` to the same scraper;
 *   the link becomes the recipe's source and is not fetched by the server.
 * - A text for the AI (Fas 4 review, fix-data; plan §8.4 step 3): the same `POST /api/ai-import/`
 *   with `text` instead of a file, the last step of the chain for a link nothing else could read.
 * - Guests: `DataProblem.READ_ONLY` (they could not save the recipe); demo mode:
 *   [RecipeImportProblem.DEMO_MODE].
 *
 * Every server call runs inside [ImportGate.whileImporting], so background sync yields the server
 * to the import (plan §10 Fas 3 "Klart när": "bakgrundssynken pausas under tiden"). An AI import
 * also runs inside [AiImportWatch.whileRunning]: it says when it is done if the app has left the
 * screen (plan §8.7).
 */
@Singleton
internal class RemoteRecipeImportRepository @Inject constructor(
    private val sources: ActiveSources,
    private val caller: RemoteCaller,
    private val permissions: SpacePermissionsRepository,
    private val status: ServerStatusRepository,
    private val databases: TandemDatabaseFactory,
    private val gate: ImportGate,
    private val aiWatch: AiImportWatch,
) : RecipeImportRepository {
    override fun observeAiImportAvailable(): Flow<Boolean> = status.observeStatus()
        .map { !it.isDemo && ServerFeature.AI_IMPORT in it.capabilities }
        .catch { emit(false) }
        .distinctUntilChanged()

    override suspend fun import(source: RecipeImportSource): Outcome<RecipeImportResult> = catchingUnexpected {
        if (permissions.permissions.first().readOnly) return@catchingUnexpected Outcome.Failure(DataProblem.READ_ONLY.toError())
        // A text is read on the device (FACTS "Fas 3 e": the server answers 500 for plain text), also offline and in demo mode.
        if (source is RecipeImportSource.Text) return@catchingUnexpected fromText(source.text)
        val remote = when (val current = sources.current()) {
            is ActiveSource.Remote -> current
            ActiveSource.Demo -> return@catchingUnexpected Outcome.Failure(RecipeImportProblem.DEMO_MODE.toError())
            ActiveSource.Loading, ActiveSource.None -> return@catchingUnexpected Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        }
        if (source is RecipeImportSource.File || source is RecipeImportSource.AiText) {
            aiWatch.whileRunning(stillWanted = { sources.current().identity == remote.identity }) { read(remote, source) }
        } else {
            read(remote, source)
        }
    }

    private suspend fun read(remote: ActiveSource.Remote, source: RecipeImportSource): Outcome<RecipeImportResult> {
        val answer = gate.whileImporting {
            when (source) {
                is RecipeImportSource.Link -> fromLink(remote, source.url)
                is RecipeImportSource.Page -> fromPage(remote, source)
                is RecipeImportSource.File -> fromFile(remote, source)
                is RecipeImportSource.AiText -> fromAiText(remote, source.text)
                is RecipeImportSource.Text -> error("handled above")
            }
        }
        return when (answer) {
            is Outcome.Failure -> answer

            is Outcome.Success -> RecipeImportMapper.toResult(answer.value)
                ?.takeUnless { answer.value.error }
                ?.let { Outcome.Success(it.withPageSource(source)) }
                ?: Outcome.Failure(RecipeImportProblem.NOTHING_FOUND.toError())
        }
    }

    /** The server leaves `source_url` empty for a page sent as data (FACTS "Fas 4: Fas 3:s rester" a): the link is the source. */
    private fun RecipeImportResult.withPageSource(source: RecipeImportSource): RecipeImportResult = when {
        source is RecipeImportSource.Page && draft.sourceUrl.isBlank() -> copy(draft = draft.copy(sourceUrl = source.url.trim()))
        else -> this
    }

    private suspend fun fromText(text: String): Outcome<RecipeImportResult> {
        if (text.isBlank()) return Outcome.Failure(RecipeImportProblem.EMPTY_INPUT.toError())
        val units = (sources.current() as? ActiveSource.Stored)?.let { source ->
            runCatching { databases.get(source.kind).referenceDao().getUnits(source.accountId).map { it.name } }.getOrDefault(emptyList())
        }.orEmpty()
        val draft = TextRecipeParser.parse(text, units) ?: return Outcome.Failure(RecipeImportProblem.NOTHING_FOUND.toError())
        return Outcome.Success(RecipeImportResult(draft = draft))
    }

    /**
     * A page without a recipe answers `error: true` with 200, a link the server cannot fetch 400
     * (`{"error": true, "msg": "Connection Refused."}`) and a page it cannot read 500 (FACTS "Fas
     * 3 e"): all three are "nothing found" for the user.
     */
    private suspend fun fromLink(remote: ActiveSource.Remote, url: String): Outcome<RecipeFromSourceResponseDto> {
        if (url.isBlank()) return Outcome.Failure(RecipeImportProblem.EMPTY_INPUT.toError())
        val body = buildJsonObject { put("url", url.trim()) }
        val sent = caller.call(remote, background = false, confirmForbidden = true) { imports.fromSource(body) }
        return if (sent is Outcome.Failure && (sent.error is TandemError.BadRequest || sent.error is TandemError.ServerError)) {
            Outcome.Failure(RecipeImportProblem.NOTHING_FOUND.toError())
        } else {
            sent
        }
    }

    /**
     * #38: the page as the app's WebView read it, with the shared link (the server does not fetch
     * it when `data` is given, and leaves `source_url` empty: the app sets it; FACTS "Fas 4: Fas
     * 3:s rester" a). A page without a recipe answers 500: "nothing found".
     */
    private suspend fun fromPage(remote: ActiveSource.Remote, page: RecipeImportSource.Page): Outcome<RecipeFromSourceResponseDto> {
        if (page.html.isBlank()) return Outcome.Failure(RecipeImportProblem.EMPTY_INPUT.toError())
        val body = buildJsonObject {
            put("url", page.url.trim())
            put("data", page.html.take(RecipeImportProblem.MAX_PAGE_CHARS))
        }
        val sent = caller.call(remote, background = false, confirmForbidden = true) { imports.fromSource(body) }
        return if (sent is Outcome.Failure && (sent.error is TandemError.BadRequest || sent.error is TandemError.ServerError)) {
            Outcome.Failure(RecipeImportProblem.NOTHING_FOUND.toError())
        } else {
            sent
        }
    }

    override suspend fun sortIngredientsIntoSteps(draft: RecipeDraft): Outcome<RecipeDraft> = catchingUnexpected {
        if (permissions.permissions.first().readOnly) return@catchingUnexpected Outcome.Failure(DataProblem.READ_ONLY.toError())
        val remote = when (val current = sources.current()) {
            is ActiveSource.Remote -> current
            ActiveSource.Demo -> return@catchingUnexpected Outcome.Failure(RecipeImportProblem.DEMO_MODE.toError())
            ActiveSource.Loading, ActiveSource.None -> return@catchingUnexpected Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        }
        gate.whileImporting {
            val provider = when (val found = providerOf(remote)) {
                is Outcome.Failure -> return@whileImporting found
                is Outcome.Success -> found.value ?: return@whileImporting Outcome.Failure(RecipeImportProblem.AI_UNAVAILABLE.toError())
            }
            val body = RecipeImportMapper.toSourceRecipe(draft)
            when (val sent = caller.call(remote, background = false, confirmForbidden = true) { imports.aiStepSort(provider, body) }) {
                is Outcome.Failure ->
                    if (sent.error is TandemError.BadRequest || sent.error is TandemError.ServerError) {
                        Outcome.Failure(RecipeImportProblem.AI_UNAVAILABLE.toError())
                    } else {
                        sent
                    }

                is Outcome.Success -> RecipeImportMapper.sortedDraft(sent.value, draft)
            }
        }
    }

    /** `space/current` `ai_default_provider.id`, read fresh (the admin may have just set it); null without one. */
    private suspend fun providerOf(remote: ActiveSource.Remote): Outcome<Long?> =
        when (val space = caller.call(remote, background = false) { space.currentSpace() }) {
            is Outcome.Failure -> space

            is Outcome.Success -> Outcome.Success(
                (space.value.aiDefaultProvider as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.longOrNull },
            )
        }

    private suspend fun fromFile(remote: ActiveSource.Remote, file: RecipeImportSource.File): Outcome<RecipeFromSourceResponseDto> {
        if (file.bytes.isEmpty()) return Outcome.Failure(RecipeImportProblem.EMPTY_INPUT.toError())
        if (file.bytes.size > RecipeImportProblem.MAX_FILE_BYTES) return Outcome.Failure(RecipeImportProblem.FILE_TOO_LARGE.toError())
        val part = MultipartBody.Part.createFormData("file", file.fileName, file.bytes.toRequestBody(file.mimeType.toMediaType()))
        return aiImport(remote, part, text = null)
    }

    private suspend fun fromAiText(remote: ActiveSource.Remote, text: String): Outcome<RecipeFromSourceResponseDto> {
        if (text.isBlank()) return Outcome.Failure(RecipeImportProblem.EMPTY_INPUT.toError())
        return aiImport(remote, file = null, text = text.trim().take(MAX_AI_TEXT_CHARS))
    }

    private suspend fun aiImport(
        remote: ActiveSource.Remote,
        file: MultipartBody.Part?,
        text: String?,
    ): Outcome<RecipeFromSourceResponseDto> {
        val provider = when (val found = providerOf(remote)) {
            is Outcome.Failure -> return found
            is Outcome.Success -> found.value
        } ?: return Outcome.Failure(RecipeImportProblem.AI_UNAVAILABLE.toError())
        val sent = caller.call(remote, background = false, confirmForbidden = true) {
            imports.aiImport(providerId = provider.toString().toRequestBody(TEXT), file = file, text = text?.toRequestBody(TEXT))
        }
        // Without a working provider the server answers 500 (FACTS "Fas 3 f"), a refused file 400.
        return if (sent is Outcome.Failure && (sent.error is TandemError.BadRequest || sent.error is TandemError.ServerError)) {
            Outcome.Failure(RecipeImportProblem.AI_UNAVAILABLE.toError())
        } else {
            sent
        }
    }

    private companion object {
        val TEXT = "text/plain".toMediaType()

        /** A recipe fits with room to spare; what is sent is what the provider charges for. */
        const val MAX_AI_TEXT_CHARS = 20_000
    }
}
