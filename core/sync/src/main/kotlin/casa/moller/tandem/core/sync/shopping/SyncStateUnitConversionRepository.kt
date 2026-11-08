package casa.moller.tandem.core.sync.shopping

import casa.moller.tandem.core.data.shopping.UnitConversionRepository
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.model.shopping.ConversionUnit
import casa.moller.tandem.core.model.shopping.UnitConversionRule
import casa.moller.tandem.core.model.shopping.UnitConversions
import casa.moller.tandem.core.network.dto.UnitConversionDto
import casa.moller.tandem.core.network.dto.UnitDto
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * [UnitConversionRepository] (Fas 5, lane handla; plan §8.2 "Sammanslagning i vyn"): the space's
 * units (`unit`, read with the small tables) and its own conversions, cached in one `sync_state`
 * row per account ([KEY]; JSON in `hash`), so no schema change is needed and the cache goes with the
 * account (`AccountDao.deleteAccountData`). [ShoppingTables] refreshes it with the other small
 * tables, never for a guest (Tandoor answers 403, FACTS "Fas 5"). Demo mode reads the demo
 * database: its units, no conversions. A broken row reads as no conversions (never a crash).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class SyncStateUnitConversionRepository @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
) : UnitConversionRepository {

    override fun observeConversions(): Flow<UnitConversions> = sources.source
        .flatMapLatest { source ->
            if (source !is ActiveSource.Stored) return@flatMapLatest flowOf(UnitConversions.NONE)
            val database = databases.get(source.kind)
            combine(
                database.referenceDao().observeUnits(source.accountId),
                database.syncStateDao().observeOne(source.accountId, KEY),
            ) { units, row ->
                val cached = decode(row?.hash)
                val table = units.map { ConversionUnit(it.id, it.name, it.baseUnit) }
                val known = table.mapTo(HashSet()) { it.id }
                UnitConversions(units = table + cached.units.filter { it.id !in known }, rules = cached.rules)
            }
        }
        .catch { emit(UnitConversions.NONE) }
        .distinctUntilChanged()

    companion object {
        /** The `sync_state.entity` of the cached conversions. */
        const val KEY: String = "unit_conversions"

        /** Replaces the cached conversions of [accountId] with a complete list read from Tandoor. */
        suspend fun store(database: TandemDatabase, accountId: String, items: List<UnitConversionDto>) {
            val text = encode(of(items))
            val states = database.syncStateDao()
            if (states.get(accountId, KEY)?.hash != text) states.upsert(SyncStateEntity(accountId = accountId, entity = KEY, hash = text))
        }

        /** The rules and the units they name (with their base unit keys) in [items]; incomplete items are skipped. */
        internal fun of(items: List<UnitConversionDto>): UnitConversions {
            val rules = items.mapNotNull { item ->
                val id = item.id ?: return@mapNotNull null
                val base = item.baseUnit?.id ?: return@mapNotNull null
                val converted = item.convertedUnit?.id ?: return@mapNotNull null
                UnitConversionRule(id, base, item.baseAmount, converted, item.convertedAmount, item.food?.id)
            }.sortedBy { it.id }
            val units = items.flatMap { listOfNotNull(it.baseUnit, it.convertedUnit) }
                .mapNotNull { it.toConversionUnit() }
                .distinctBy { it.id }
                .sortedBy { it.id }
            return UnitConversions(units, rules)
        }

        private fun UnitDto.toConversionUnit(): ConversionUnit? = id?.let { ConversionUnit(it, name, baseUnit?.takeIf(String::isNotBlank)) }

        internal fun encode(conversions: UnitConversions): String = JsonObject(
            mapOf(
                "u" to JsonArray(
                    conversions.units.map { unit ->
                        JsonObject(
                            mapOf("i" to JsonPrimitive(unit.id), "n" to JsonPrimitive(unit.name), "k" to JsonPrimitive(unit.baseUnit)),
                        )
                    },
                ),
                "r" to JsonArray(
                    conversions.rules.map { rule ->
                        JsonObject(
                            mapOf(
                                "i" to JsonPrimitive(rule.id),
                                "b" to JsonPrimitive(rule.baseUnitId),
                                "ba" to JsonPrimitive(rule.baseAmount),
                                "c" to JsonPrimitive(rule.convertedUnitId),
                                "ca" to JsonPrimitive(rule.convertedAmount),
                                "f" to JsonPrimitive(rule.foodId),
                            ),
                        )
                    },
                ),
            ),
        ).toString()

        /** The conversions in [text]; nothing for null, blank or unreadable text. */
        internal fun decode(text: String?): UnitConversions {
            if (text.isNullOrBlank()) return UnitConversions.NONE
            return runCatching {
                val json = Json.parseToJsonElement(text).jsonObject
                UnitConversions(
                    units = json.getValue("u").jsonArray.map { element ->
                        val unit = element.jsonObject
                        ConversionUnit(
                            id = unit.getValue("i").jsonPrimitive.long,
                            name = unit.getValue("n").jsonPrimitive.content,
                            baseUnit = unit["k"]?.jsonPrimitive?.contentOrNull,
                        )
                    },
                    rules = json.getValue("r").jsonArray.map { element ->
                        val rule = element.jsonObject
                        UnitConversionRule(
                            id = rule.getValue("i").jsonPrimitive.long,
                            baseUnitId = rule.getValue("b").jsonPrimitive.long,
                            baseAmount = rule.getValue("ba").jsonPrimitive.double,
                            convertedUnitId = rule.getValue("c").jsonPrimitive.long,
                            convertedAmount = rule.getValue("ca").jsonPrimitive.double,
                            foodId = rule["f"]?.jsonPrimitive?.longOrNull,
                        )
                    },
                )
            }.getOrDefault(UnitConversions.NONE)
        }
    }
}
