package casa.moller.tandem.core.domain.shopping

import casa.moller.tandem.core.model.shopping.ConversionUnit
import casa.moller.tandem.core.model.shopping.ShoppingUnit
import casa.moller.tandem.core.model.shopping.UnitConversionRule
import casa.moller.tandem.core.model.shopping.UnitConversions
import java.util.concurrent.ConcurrentHashMap

/**
 * Converts amounts between units with Tandoor's own data (plan §6.7 "Enhetskonvertering via
 * Tandoors conversions", §8.2 "Sammanslagning i vyn"; FACTS "Fas 5"):
 *
 * - **The space's conversions** ([UnitConversionRule], `/api/unit-conversion/`), in both directions;
 *   a food-specific one only for its food, and before a general one.
 * - **Tandoor's base table** (`cookbook/helper/unit_conversion_helper.py`, `CONVERSION_TABLE`, the
 *   same in 2.6.13 and 2.7.x): weight `g`, `kg`, `ounce`, `pound` and volume `ml`, `l`, `tbsp`,
 *   `tsp`, `us_cup` …, keyed by a unit's `base_unit`, or by its NAME when it has none (as Tandoor
 *   does for the source unit). There is no `dl` in it: "1 dl = 100 ml" is a conversion of the space.
 *
 * Every conversion is a factor, so the converter searches a path between the two units over all
 * of them (breadth first: food-specific, then general, then base steps), shortest first. Where
 * Tandoor computes a conversion for a recipe the result is the same; Tandem also finds the ones
 * Tandoor's one-way order misses (a base step followed by a custom one, `l` → `ml` → `dl`, and a
 * target unit without `base_unit` matched by its name, FACTS "Fas 5": the server leaves those out).
 */
class UnitConverter(conversions: UnitConversions) {
    private val units: Map<Long, ConversionUnit> = conversions.units.associateBy { it.id }
    private val unitsByName: Map<String, ConversionUnit> = conversions.units.associateBy { normalize(it.name) }
    private val rules: List<UnitConversionRule> = conversions.rules.filter { it.baseAmount > 0.0 && it.convertedAmount > 0.0 }

    /** The rules touching each unit id (either side). */
    private val rulesByUnit: Map<Long, List<UnitConversionRule>> =
        rules.flatMap { listOf(it.baseUnitId to it, it.convertedUnitId to it) }.groupBy({ it.first }, { it.second })

    /** Every unit the conversions know, as nodes (a base step can pass through any of them). */
    private val nodes: Set<Node> = buildSet {
        conversions.units.forEach { add(Node.Known(it.id)) }
        rules.forEach {
            add(Node.Known(it.baseUnitId))
            add(Node.Known(it.convertedUnitId))
        }
    }

    /** The nodes with a key in each system of the base table. */
    private val nodesBySystem: Map<Map<String, Double>, List<Node>> =
        SYSTEMS.associateWith { system -> nodes.filter { baseKey(it) in system } }

    /** Factors found so far ([NO_PATH] for none); thread-safe, the converter may be shared. */
    private val cache = ConcurrentHashMap<Triple<Node, Node, Long?>, Double>()

    /**
     * How much of [to] one [from] of [foodId] is (`1 dl` grädde → `100.0` g), or null when no path
     * exists. A unit without an id (a list entry made offline) is found by name. Null units ("2
     * ägg") only "convert" to each other.
     */
    fun factor(from: ShoppingUnit?, to: ShoppingUnit?, foodId: Long?): Double? {
        if (from == null || to == null) return if (from == null && to == null) 1.0 else null
        val start = node(from)
        val goal = node(to)
        if (start == goal) return 1.0
        val factor = cache.getOrPut(Triple(start, goal, foodId)) { search(start, goal, foodId) ?: NO_PATH }
        return factor.takeUnless { it.isNaN() }
    }

    /** True when [a] and [b] are the same unit (by id, else by name). */
    fun sameUnit(a: ShoppingUnit?, b: ShoppingUnit?): Boolean = if (a == null || b == null) a == null && b == null else node(a) == node(b)

    private fun search(start: Node, goal: Node, foodId: Long?): Double? {
        val factors = linkedMapOf(start to 1.0)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val here = factors.getValue(current)
            for ((next, step) in neighbours(current, goal, foodId)) {
                if (next in factors) continue
                val total = here * step
                if (next == goal) return total
                factors[next] = total
                queue.addLast(next)
            }
        }
        return null
    }

    /** The steps out of [node]: its food-specific conversions, its general ones, then base steps (to [goal] first). */
    private fun neighbours(node: Node, goal: Node, foodId: Long?): List<Pair<Node, Double>> {
        val touching = (node as? Node.Known)?.let { rulesByUnit[it.id] }.orEmpty()
        val custom = (touching.filter { it.foodId != null && it.foodId == foodId } + touching.filter { it.foodId == null })
            .mapNotNull { rule -> step(rule, (node as Node.Known).id) }
        val key = baseKey(node) ?: return custom
        val system = SYSTEMS.firstOrNull { key in it } ?: return custom
        val base = (listOf(goal) + nodesBySystem.getValue(system)).distinct()
            .filter { it != node }
            .mapNotNull { other -> baseKey(other)?.takeIf { it in system }?.let { other to system.getValue(it) / system.getValue(key) } }
        return custom + base
    }

    /** A rule seen from [unitId]: forwards from its base unit, backwards from its converted unit. */
    private fun step(rule: UnitConversionRule, unitId: Long): Pair<Node, Double>? = when (unitId) {
        rule.baseUnitId -> Node.Known(rule.convertedUnitId) to rule.convertedAmount / rule.baseAmount
        rule.convertedUnitId -> Node.Known(rule.baseUnitId) to rule.baseAmount / rule.convertedAmount
        else -> null
    }

    /** The key in Tandoor's base table: `base_unit`, or the name when the unit has none. */
    private fun baseKey(node: Node): String? = when (node) {
        is Node.Known -> units[node.id]?.let { it.baseUnit?.takeIf(String::isNotBlank)?.let(::normalize) ?: normalize(it.name) }
        is Node.Named -> node.name
    }

    /**
     * The node of [unit]: its id when the space has that unit under the same name, else the unit
     * with its name, else its id (a unit renamed since the units were read), else only its name.
     */
    private fun node(unit: ShoppingUnit): Node {
        val id = unit.id
        val name = normalize(unit.name)
        if (id != null && units[id]?.let { normalize(it.name) == name } == true) return Node.Known(id)
        unitsByName[name]?.let { return Node.Known(it.id) }
        if (id != null && (id in units || Node.Known(id) in nodes)) return Node.Known(id)
        // A unit the conversions do not know (none cached yet, a guest, made offline): by its name.
        return Node.Named(name)
    }

    /** A unit: one the space knows (by id), or only a name (a unit typed offline that no table has). */
    private sealed interface Node {
        data class Known(val id: Long) : Node

        data class Named(val name: String) : Node
    }

    companion object {
        /** How many of each unit make one kg or one litre: Tandoor's `CONVERSION_TABLE` (2.6.13–2.7.x). */
        val WEIGHT: Map<String, Double> = mapOf("g" to 1000.0, "kg" to 1.0, "ounce" to 35.274, "pound" to 2.20462)

        val VOLUME: Map<String, Double> = mapOf(
            "ml" to 1000.0,
            "l" to 1.0,
            "fluid_ounce" to 33.814,
            "pint" to 2.11338,
            "quart" to 1.05669,
            "gallon" to 0.264172,
            "tbsp" to 67.628,
            "tsp" to 202.884,
            "us_cup" to 4.22675,
            "imperial_fluid_ounce" to 35.1951,
            "imperial_pint" to 1.75975,
            "imperial_quart" to 0.879877,
            "imperial_gallon" to 0.219969,
            "imperial_tbsp" to 56.3121,
            "imperial_tsp" to 168.936,
        )

        private val SYSTEMS = listOf(WEIGHT, VOLUME)

        private const val NO_PATH = Double.NaN

        private fun normalize(name: String): String = name.trim().lowercase()
    }
}
