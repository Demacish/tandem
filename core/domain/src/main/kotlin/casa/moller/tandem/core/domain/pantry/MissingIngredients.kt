package casa.moller.tandem.core.domain.pantry

import casa.moller.tandem.core.model.makenow.MissingFood
import casa.moller.tandem.core.model.recipe.Ingredient

/**
 * What the app knows about one food for "Kan laga nu" (plan §8.8; Room `food` + `inventory_entry`).
 *
 * @property onHand Tandoor's `food_onhand` (per household).
 * @property inStock an inventory entry with amount > 0 in the household's locations.
 * @property substituteOnHand Tandoor's `substitute_onhand` (a substitute, sibling or child is on hand).
 * @property substituteIds the direct substitutes; one of them in stock or on hand resolves the food too.
 * @property ignoreShopping Tandoor's `ignore_shopping`: resolved unless the food is a recipe ([isRecipe]).
 * @property parentId Fas S komplettering: the food's parent in Tandoor's food tree (`parent`), null for a root.
 * @property substituteSiblings Tandoor's `substitute_siblings`: a sibling (another food with the same
 *   [parentId]) in stock or on hand resolves the food.
 * @property substituteChildren Tandoor's `substitute_children`: a child or deeper descendant in stock
 *   or on hand resolves the food.
 */
data class FoodAvailability(
    val foodId: Long,
    val onHand: Boolean = false,
    val inStock: Boolean = false,
    val substituteOnHand: Boolean = false,
    val substituteIds: Set<Long> = emptySet(),
    val ignoreShopping: Boolean = false,
    val isRecipe: Boolean = false,
    val parentId: Long? = null,
    val substituteSiblings: Boolean = false,
    val substituteChildren: Boolean = false,
)

/**
 * The missing foods of a recipe, computed on the device the way Tandoor's `makenow` counts them
 * (FACTS "Fas S a", Tandoor `food_availability_helper`): a food is available when it is on hand or
 * has an inventory entry with amount > 0 in the household; it is also resolved by an available
 * substitute (or `substitute_onhand`: siblings/children on hand), or when it is `ignore_shopping`
 * and not itself a recipe. Headers and free-text rows (no food) never count. Each food counts once.
 *
 * Fas S komplettering: substitutes through the food tree count in stock too, as Tandoor's makenow
 * counts them (FACTS "Fas S a", `substituteTree`): with `substitute_children` an available child
 * or deeper descendant, with `substitute_siblings` an available food under the same parent. A
 * parent never stands in for its child. This needs the whole food list in [missing]'s map (the
 * tree is read from [FoodAvailability.parentId]).
 *
 * The server's makenow decides the GROUP (Har allt / Saknar 1 / Saknar 2); this only NAMES the
 * missing foods for the row and for "Lägg det som saknas på listan".
 */
object MissingIngredients {
    /**
     * @param ingredients the recipe's ingredients (all steps), in recipe order.
     * @param foods what is known per food id; a food without an entry counts by its own `onHand`.
     */
    fun missing(ingredients: List<Ingredient>, foods: Map<Long, FoodAvailability>): List<MissingFood> = ingredients.asSequence()
        .filterNot { it.isHeader }
        .mapNotNull { it.food }
        .distinctBy { it.id }
        .filterNot { food -> isResolved(foods[food.id] ?: FoodAvailability(food.id, onHand = food.onHand), foods) }
        .map { food -> MissingFood(food.id, food.name, food.pluralName) }
        .toList()

    /** Whether one food is resolved (available, by a substitute, or ignorable). */
    fun isResolved(food: FoodAvailability, foods: Map<Long, FoodAvailability>): Boolean = isAvailable(food) ||
        food.substituteOnHand ||
        food.substituteIds.any { id -> foods[id]?.let(::isAvailable) == true } ||
        (food.substituteSiblings && siblingsOf(food, foods.values).any(::isAvailable)) ||
        (food.substituteChildren && descendantsOf(food.foodId, foods.values).any(::isAvailable)) ||
        (food.ignoreShopping && !food.isRecipe)

    private fun isAvailable(food: FoodAvailability): Boolean = food.onHand || food.inStock

    /** The other foods under [food]'s parent (a root food has no siblings here). */
    private fun siblingsOf(food: FoodAvailability, foods: Collection<FoodAvailability>): Sequence<FoodAvailability> {
        val parent = food.parentId ?: return emptySequence()
        return foods.asSequence().filter { it.parentId == parent && it.foodId != food.foodId }
    }

    /** Every food below [foodId] in the tree, breadth first; a cycle in broken data ends the walk. */
    private fun descendantsOf(foodId: Long, foods: Collection<FoodAvailability>): Sequence<FoodAvailability> = sequence {
        val children = foods.groupBy { it.parentId }
        val seen = HashSet<Long>().apply { add(foodId) }
        val queue = ArrayDeque(children[foodId].orEmpty())
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next.foodId)) continue
            yield(next)
            queue.addAll(children[next.foodId].orEmpty())
        }
    }
}
