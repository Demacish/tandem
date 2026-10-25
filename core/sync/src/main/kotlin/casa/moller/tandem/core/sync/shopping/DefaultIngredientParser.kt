package casa.moller.tandem.core.sync.shopping

import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.shopping.IngredientParser
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.domain.shopping.LocalIngredientParser
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.shopping.ParsedIngredient
import casa.moller.tandem.core.network.dto.IngredientParserRequestDto
import casa.moller.tandem.core.network.dto.ParsedIngredientDto
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.remote.RemoteCaller
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

/**
 * [IngredientParser]: Tandoor's parser online (one `POST /api/ingredient-parser/post/` for all
 * lines), [LocalIngredientParser] offline, in demo mode, for guests (the server's parser creates
 * foods, a write) and when the server fails. The local parser knows the stored units too.
 */
@Singleton
internal class DefaultIngredientParser @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val network: NetworkStatus,
    private val permissions: SpacePermissionsRepository,
) : IngredientParser {
    /** The stored unit names of the last account read, for [parseLocally] (which must not suspend). */
    private val units = AtomicReference<Pair<String, List<String>>?>(null)

    override suspend fun parse(lines: List<String>): Outcome<List<ParsedIngredient>> {
        val wanted = lines.map { it.trim() }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return Outcome.Failure(TandemError.BadRequest())
        val source = sources.current()
        val knownUnits = loadUnits(source)
        val local = { wanted.mapNotNull { LocalIngredientParser.parse(it, knownUnits) } }
        if (source !is ActiveSource.Remote || !network.online.value || permissions.permissions.first().readOnly) {
            return Outcome.Success(local())
        }
        val request = IngredientParserRequestDto(ingredients = wanted)
        return when (val parsed = caller.call(remote = source, background = false) { supermarkets.parseIngredients(request) }) {
            is Outcome.Success -> {
                val results = parsed.value.ingredients
                if (results.size != wanted.size) {
                    Outcome.Success(local())
                } else {
                    Outcome.Success(
                        wanted.zip(results).mapNotNull { (line, dto) ->
                            dto.toModel(line) ?: LocalIngredientParser.parse(line, knownUnits)
                        },
                    )
                }
            }

            is Outcome.Failure -> Outcome.Success(local())
        }
    }

    override fun parseLocally(line: String): ParsedIngredient? {
        val active = (sources.source.value as? ActiveSource.Stored)?.accountId
        val known = units.get()?.takeIf { it.first == active }?.second.orEmpty()
        return LocalIngredientParser.parse(line, known)
    }

    private suspend fun loadUnits(source: ActiveSource): List<String> {
        val stored = source as? ActiveSource.Stored ?: return emptyList()
        return try {
            databases.get(stored.kind).referenceDao().getUnits(stored.accountId).flatMap { listOfNotNull(it.name, it.pluralName) }
                .also { units.set(stored.accountId to it) }
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // The units only help the local parser; without them it knows the built-in ones.
            emptyList()
        }
    }

    private fun ParsedIngredientDto.toModel(line: String): ParsedIngredient? {
        val name = food?.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return ParsedIngredient(
            originalText = originalText.ifBlank { line },
            amount = if (noAmount) 0.0 else amount,
            unitName = unit?.name?.takeIf { it.isNotBlank() },
            foodName = name,
            note = note,
            unitId = unit?.id,
            foodId = food.id,
            fromServer = true,
        )
    }
}
