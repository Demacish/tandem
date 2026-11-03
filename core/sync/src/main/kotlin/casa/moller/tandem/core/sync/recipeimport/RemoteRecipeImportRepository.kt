package casa.moller.tandem.core.sync.recipeimport

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.recipe.RecipeImportProblem
import casa.moller.tandem.core.data.recipe.RecipeImportRepository
import casa.moller.tandem.core.data.server.ServerStatusRepository
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.model.recipe.RecipeImportResult
import casa.moller.tandem.core.model.recipe.RecipeImportSource
import casa.moller.tandem.core.model.server.ServerFeature
import casa.moller.tandem.core.network.dto.RecipeFromSourceResponseDto
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
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
 * - Guests: `DataProblem.READ_ONLY` (they could not save the recipe); demo mode:
 *   [RecipeImportProblem.DEMO_MODE].
 */
@Singleton
internal class RemoteRecipeImportRepository @Inject constructor(
    private val sources: ActiveSources,
    private val caller: RemoteCaller,
    private val permissions: SpacePermissionsRepository,
    private val status: ServerStatusRepository,
    private val databases: TandemDatabaseFactory,
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
        val answer = when (source) {
            is RecipeImportSource.Link -> fromLink(remote, source.url)
            is RecipeImportSource.File -> fromFile(remote, source)
            is RecipeImportSource.Text -> error("handled above")
        }
        when (answer) {
            is Outcome.Failure -> answer
            is Outcome.Success -> RecipeImportMapper.toResult(answer.value)
                ?.takeUnless { answer.value.error }
                ?.let { Outcome.Success(it) }
                ?: Outcome.Failure(RecipeImportProblem.NOTHING_FOUND.toError())
        }
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

    private suspend fun fromFile(remote: ActiveSource.Remote, file: RecipeImportSource.File): Outcome<RecipeFromSourceResponseDto> {
        if (file.bytes.isEmpty()) return Outcome.Failure(RecipeImportProblem.EMPTY_INPUT.toError())
        if (file.bytes.size > RecipeImportProblem.MAX_FILE_BYTES) return Outcome.Failure(RecipeImportProblem.FILE_TOO_LARGE.toError())
        val provider = when (val space = caller.call(remote, background = false) { space.currentSpace() }) {
            is Outcome.Failure -> return space
            is Outcome.Success -> (space.value.aiDefaultProvider as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.longOrNull }
        } ?: return Outcome.Failure(RecipeImportProblem.AI_UNAVAILABLE.toError())
        val part = MultipartBody.Part.createFormData("file", file.fileName, file.bytes.toRequestBody(file.mimeType.toMediaType()))
        val sent = caller.call(remote, background = false, confirmForbidden = true) {
            imports.aiImport(providerId = provider.toString().toRequestBody(TEXT), file = part, text = null)
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
    }
}
