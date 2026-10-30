package casa.moller.tandem.core.domain.drink

import casa.moller.tandem.core.model.drink.DrinkKeywords
import casa.moller.tandem.core.model.drink.Pairing

// Fas S (drycker lane): drinks and "Passar med" (#43, B20, plan §8.8 "Drycker") worked out from the
// cached keywords and recipe-keyword links, so the drinks list, a recipe's pairings and the drink
// suggestion of tonight's meal work offline. The repository (core:sync) feeds it the stored rows
// and the changes still in the outbox; the server's keyword ids win once they are known.

/** A keyword as the index needs it (`keyword` table: id, name, parent). */
data class DrinkKeywordNode(val id: Long, val name: String, val parentId: Long? = null)

/** What a keyword of a drink created in the app is for (the `drink_draft.keywords` JSON). */
enum class DraftKeywordRole {
    /** "Dryck" itself. */
    DRINK,

    /** A type under "Dryck" ("Vin"). */
    TYPE,

    /** A pairing under "Passar med" (the drink's own, `Passar med › <namn>`). */
    PAIRING,
}

/** A keyword of a drink that is not on the server yet, by name (names are reused by Tandoor, FACTS "Fas S c"). */
data class DraftKeyword(val name: String, val role: DraftKeywordRole)

/** A drink created in the app and not sent yet ([id] negative, the `drink_draft` row). */
data class PendingDrink(val id: Long, val name: String, val keywords: List<DraftKeyword>)

/** One keyword change of a recipe that is still in the outbox (`RECIPE_KEYWORDS`, `RecipeKeywordFields`). */
sealed interface PendingKeywordChange {
    val recipeId: Long
    val add: Boolean

    /** `keyword:<id>`: an existing keyword by id. */
    data class ById(override val recipeId: Long, val keywordId: Long, override val add: Boolean) : PendingKeywordChange

    /** `pairing:<name>`: a pairing under "Passar med" by name (created by the sender when missing). */
    data class PairingByName(override val recipeId: Long, val name: String, override val add: Boolean) : PendingKeywordChange
}

/**
 * The drinks and pairings of one account at one moment. Build it with [build]; every read is a
 * plain lookup.
 *
 * - **A drink** is a recipe with a root keyword "Dryck" ([DrinkKeywords.isDrinkRoot]) or one of its
 *   children (a type, "Dryck > Vin").
 * - **Types** are the children of "Dryck", plus root keywords named like a suggested type (made on
 *   the web without the tree).
 * - **A pairing** is a child of "Passar med" ([DrinkKeywords.isPairsWithRoot]). The drink carries its
 *   own; every dish it goes with carries the same one. One that no drink carries is a pairing with a
 *   kind of drink ("Kraftigt rött", [Pairing.isTypeOnly]), shown as text.
 * - **Pending** keywords (named in the outbox or a drink draft but not stored yet) get a stable
 *   negative id from [pendingKeywordId], so the screens can show and remove them before the sync.
 */
class DrinkIndex private constructor(
    private val keywords: Map<Long, DrinkKeywordNode>,
    private val links: Map<Long, Set<Long>>,
    private val pendingDrinkIds: Set<Long>,
) {
    private val drinkRootIds: Set<Long> = keywords.values
        .filter { it.parentId == null && DrinkKeywords.isDrinkRoot(it.name) }
        .map { it.id }
        .toSet()
    private val pairsWithRootIds: Set<Long> =
        keywords.values.filter { it.parentId == null && DrinkKeywords.isPairsWithRoot(it.name) }.map { it.id }.toSet()
    private val typeKeywords: Map<Long, DrinkKeywordNode> = keywords.values
        .filter { it.parentId in drinkRootIds || (it.parentId == null && DrinkKeywords.isSuggestedType(it.name)) }
        .associateBy { it.id }
    private val pairingKeywords: Map<Long, DrinkKeywordNode> =
        keywords.values.filter { it.parentId in pairsWithRootIds }.associateBy { it.id }

    /** The recipes that are drinks (pending ones included). */
    val drinkIds: Set<Long> = links.filter { (_, ids) -> ids.any { it in drinkRootIds || keywords[it]?.parentId in drinkRootIds } }.keys +
        pendingDrinkIds

    private val drinkPairings: Set<Long> = drinkIds.flatMap { links[it].orEmpty() }.filter { it in pairingKeywords }.toSet()

    /** The id of the root keyword "Dryck" (a pending id when only the outbox names it), null without one. */
    val drinkRootId: Long?
        get() = drinkRootIds.minByOrNull { if (it < 0) Long.MAX_VALUE else it }

    /** The id of the root keyword "Passar med", null without one. */
    val pairsWithRootId: Long?
        get() = pairsWithRootIds.minByOrNull { if (it < 0) Long.MAX_VALUE else it }

    fun isDrink(recipeId: Long): Boolean = recipeId in drinkIds

    /**
     * Fas S review (fix-data): whether a keyword a recipe carries makes it a drink, by the same rule
     * as [drinkIds]: a root of the drinks ("Dryck", "Drycker", "Beverages") or one of its children
     * ("Dryck > Vin"). For a keyword the index does not know yet (an overview read before the
     * keywords), its own [parentId] and [name] decide.
     */
    fun isDrinkKeyword(keywordId: Long, name: String, parentId: Long? = null): Boolean {
        val known = keywords[keywordId]
        if (known != null) return keywordId in drinkRootIds || known.parentId in drinkRootIds
        return if (parentId != null) parentId in drinkRootIds else DrinkKeywords.isDrinkRoot(name)
    }

    /** Whether recipe [recipeId] with [keywords] (`id`, `name`, `parentId`) is a drink: [isDrink], or one of them says so. */
    fun isDrink(recipeId: Long, keywords: List<DrinkKeywordNode>): Boolean =
        isDrink(recipeId) || keywords.any { isDrinkKeyword(it.id, it.name, it.parentId) }

    fun isPending(keywordId: Long): Boolean = keywordId <= PENDING_ID_LIMIT

    /** The type names of a drink ("Vin"), by name. */
    fun typesOf(recipeId: Long): List<String> = links[recipeId].orEmpty()
        .mapNotNull { typeKeywords[it]?.name }
        .distinctBy { DrinkKeywords.key(it) }
        .sortedBy { DrinkKeywords.key(it) }

    /** Every type in use, plus the suggested ones, by name (each once, ignoring case). */
    val typeNames: List<String>
        get() = (typeKeywords.values.map { it.name } + DrinkKeywords.TYPES)
            .distinctBy { DrinkKeywords.key(it) }
            .sortedBy { DrinkKeywords.key(it) }

    /** Every pairing keyword, by name. */
    val pairings: List<Pairing>
        get() = pairingKeywords.values.map { it.toPairing() }.sortedBy { DrinkKeywords.key(it.name) }

    /** The pairings a recipe carries (a dish's drinks and types, or a drink's own), by name. */
    fun pairingsOf(recipeId: Long): List<Pairing> =
        links[recipeId].orEmpty().mapNotNull { pairingKeywords[it]?.toPairing() }.sortedBy { DrinkKeywords.key(it.name) }

    /** The drinks that share a pairing with dish [recipeId] (never the recipe itself). */
    fun drinksFor(recipeId: Long): List<Long> {
        val own = links[recipeId].orEmpty().filter { it in pairingKeywords }.toSet()
        if (own.isEmpty()) return emptyList()
        return drinkIds.filter { it != recipeId && links[it].orEmpty().any { id -> id in own } }.sorted()
    }

    /** The dishes (recipes that are not drinks) that share a pairing with drink [recipeId]. */
    fun dishesFor(recipeId: Long): List<Long> {
        val own = links[recipeId].orEmpty().filter { it in pairingKeywords }.toSet()
        if (own.isEmpty()) return emptyList()
        return links.filter { (id, ids) -> id != recipeId && id !in drinkIds && ids.any { it in own } }.keys.sorted()
    }

    /**
     * The drink's own pairing keyword: the one named like the drink, else the lowest id it carries;
     * null when it has none yet (pairing it creates `Passar med › <name>`).
     */
    fun ownPairingOf(recipeId: Long, drinkName: String): Pairing? {
        val carried = pairingsOf(recipeId)
        return carried.firstOrNull { DrinkKeywords.key(it.name) == DrinkKeywords.key(drinkName) } ?: carried.minByOrNull { it.keywordId }
    }

    /** The pairing keyword named [name] (ignoring case), null when there is none. */
    fun pairingNamed(name: String): Pairing? =
        pairingKeywords.values.firstOrNull { DrinkKeywords.key(it.name) == DrinkKeywords.key(name) }?.toPairing()

    /** The pairing with [keywordId], null when it is no pairing keyword. */
    fun pairing(keywordId: Long): Pairing? = pairingKeywords[keywordId]?.toPairing()

    /**
     * Whether [name] is taken by a keyword that is neither a pairing nor a root of the drinks (a
     * dish keyword "Vin" would be reused by Tandoor, FACTS "Fas S c", and moved under "Passar med").
     */
    fun nameTakenOutsidePairings(name: String): Boolean = keywords.values.any { node ->
        DrinkKeywords.key(node.name) == DrinkKeywords.key(name) && node.id !in pairingKeywords
    }

    private fun DrinkKeywordNode.toPairing(): Pairing = Pairing(keywordId = id, name = name, isTypeOnly = id !in drinkPairings)

    companion object {
        /** Ids at or below this are pending keywords ([pendingKeywordId]); real ids and Tandem's temporary ids are far above. */
        const val PENDING_ID_LIMIT: Long = -1_000_000_000_000L
        private const val PENDING_DRINK_ROOT: Long = PENDING_ID_LIMIT - 1
        private const val PENDING_PAIRS_ROOT: Long = PENDING_ID_LIMIT - 2
        private const val PENDING_HASH_BASE: Long = PENDING_ID_LIMIT - 1_000L

        /** A stable id for a keyword only known by [name] (case ignored), below [PENDING_ID_LIMIT]. */
        fun pendingKeywordId(name: String): Long = PENDING_HASH_BASE - (DrinkKeywords.key(name).hashCode().toLong() and 0x7fffffffL)

        /**
         * The index of [keywords] and [links] (recipe id → keyword ids) with the [changes] still in the
         * outbox applied in order and the [pendingDrinks] added.
         */
        fun build(
            keywords: List<DrinkKeywordNode>,
            links: Map<Long, Set<Long>>,
            changes: List<PendingKeywordChange> = emptyList(),
            pendingDrinks: List<PendingDrink> = emptyList(),
        ): DrinkIndex {
            val nodes = LinkedHashMap<Long, DrinkKeywordNode>()
            keywords.forEach { nodes[it.id] = it }
            val linked = HashMap<Long, MutableSet<Long>>()
            links.forEach { (recipe, ids) -> linked[recipe] = ids.toMutableSet() }
            val resolver = PendingResolver(nodes)
            for (change in changes) {
                val ids = linked.getOrPut(change.recipeId) { mutableSetOf() }
                val keywordId = when (change) {
                    is PendingKeywordChange.ById -> change.keywordId

                    is PendingKeywordChange.PairingByName -> if (change.add) {
                        resolver.pairing(change.name)
                    } else {
                        resolver.existingPairing(change.name) ?: continue
                    }
                }
                if (change.add) ids += keywordId else ids -= keywordId
            }
            for (drink in pendingDrinks) {
                val ids = linked.getOrPut(drink.id) { mutableSetOf() }
                ids += resolver.drinkRoot()
                drink.keywords.forEach { keyword ->
                    ids += when (keyword.role) {
                        DraftKeywordRole.DRINK -> resolver.drinkRoot()
                        DraftKeywordRole.TYPE -> resolver.type(keyword.name)
                        DraftKeywordRole.PAIRING -> resolver.pairing(keyword.name)
                    }
                }
            }
            return DrinkIndex(nodes, linked, pendingDrinks.map { it.id }.toSet())
        }
    }

    /** Finds a stored keyword by name or adds a pending one, under the right parent. */
    private class PendingResolver(private val nodes: MutableMap<Long, DrinkKeywordNode>) {
        fun drinkRoot(): Long = nodes.values.firstOrNull { it.parentId == null && DrinkKeywords.isDrinkRoot(it.name) }?.id
            ?: PENDING_DRINK_ROOT.also { nodes[it] = DrinkKeywordNode(it, DrinkKeywords.DRINK) }

        fun pairsWithRoot(): Long = nodes.values.firstOrNull { it.parentId == null && DrinkKeywords.isPairsWithRoot(it.name) }?.id
            ?: PENDING_PAIRS_ROOT.also { nodes[it] = DrinkKeywordNode(it, DrinkKeywords.PAIRS_WITH) }

        fun existingPairing(name: String): Long? {
            val root = nodes.values.filter { it.parentId == null && DrinkKeywords.isPairsWithRoot(it.name) }.map { it.id }.toSet()
            return nodes.values.firstOrNull { it.parentId in root && DrinkKeywords.key(it.name) == DrinkKeywords.key(name) }?.id
        }

        fun pairing(name: String): Long = existingPairing(name) ?: run {
            val parent = pairsWithRoot()
            pendingKeywordId(name).also { nodes[it] = DrinkKeywordNode(it, name.trim(), parent) }
        }

        fun type(name: String): Long {
            val root = drinkRoot()
            return nodes.values.firstOrNull {
                DrinkKeywords.key(it.name) == DrinkKeywords.key(name) && (it.parentId == root || it.parentId == null)
            }?.id ?: pendingKeywordId(name).also { nodes[it] = DrinkKeywordNode(it, name.trim(), root) }
        }
    }
}
