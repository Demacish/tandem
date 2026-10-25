package casa.moller.tandem.core.sync.shopping

import casa.moller.tandem.core.database.entity.FoodEntity
import casa.moller.tandem.core.database.entity.ShoppingEntryEntity
import casa.moller.tandem.core.database.entity.ShoppingHistoryEntity
import casa.moller.tandem.core.database.entity.ShoppingListEntity
import casa.moller.tandem.core.database.entity.ShoppingListRecipeEntity
import casa.moller.tandem.core.database.entity.SupermarketCategoryEntity
import casa.moller.tandem.core.database.entity.SupermarketCategoryRelationEntity
import casa.moller.tandem.core.database.entity.SupermarketEntity
import casa.moller.tandem.core.model.shopping.Aisle
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingFood
import casa.moller.tandem.core.model.shopping.ShoppingHistoryItem
import casa.moller.tandem.core.model.shopping.ShoppingList
import casa.moller.tandem.core.model.shopping.ShoppingRecipeLabel
import casa.moller.tandem.core.model.shopping.ShoppingUnit
import casa.moller.tandem.core.model.shopping.Supermarket
import casa.moller.tandem.core.model.shopping.SupermarketCategory
import casa.moller.tandem.core.network.dto.ShoppingListDto
import casa.moller.tandem.core.network.dto.ShoppingListEntryDto
import casa.moller.tandem.core.network.dto.ShoppingListRecipeDto
import casa.moller.tandem.core.network.dto.SupermarketCategoryDto
import casa.moller.tandem.core.network.dto.SupermarketDto
import casa.moller.tandem.core.sync.remote.tandoorMillis
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

// Phase 2 (inköp-data lane): the shopping list between Tandoor's JSON, Room and core:model.

/**
 * A server entry as a row: `local_id = server_id` (plan 5.7). Null without an id or a food
 * (Tandoor answers 500 for an entry without a food, so a stored one always has one).
 */
internal fun ShoppingListEntryDto.toEntity(accountId: String, fetchedAt: Long): ShoppingEntryEntity? {
    val entryId = id ?: return null
    val food = food?.takeIf { it.name.isNotBlank() } ?: return null
    val listRecipeData = listRecipeData
    return ShoppingEntryEntity(
        accountId = accountId,
        localId = entryId,
        serverId = entryId,
        foodId = food.id,
        foodName = food.name,
        foodPluralName = food.pluralName,
        categoryId = food.supermarketCategory?.id,
        unitId = unit?.id,
        unitName = unit?.name?.takeIf { it.isNotBlank() },
        unitPluralName = unit?.pluralName,
        amount = amount,
        entryOrder = order,
        checked = checked,
        completedAt = tandoorMillis(completedAt),
        // Tandoor's "not delayed" is 1970-01-01: stored as no delay.
        delayUntil = tandoorMillis(delayUntil)?.takeIf { it > 0 },
        createdAt = tandoorMillis(createdAt),
        updatedAt = tandoorMillis(updatedAt),
        createdBy = createdBy?.id,
        createdByName = createdBy?.let { user -> user.displayName.ifBlank { user.username }.takeIf { it.isNotBlank() } },
        listRecipeId = listRecipe ?: listRecipeData?.id,
        ingredientId = ingredient,
        mealPlanId = listRecipeData?.mealplan,
        recipeId = listRecipeData?.recipe,
        recipeName = listRecipeData?.labelName(),
        shoppingListIds = ShoppingIds.encode(shoppingLists.mapNotNull { it.id }),
        fetchedAt = fetchedAt,
    )
}

/** A list recipe as a row (server ids only: the server's rows have `local_id = server_id`). */
internal fun ShoppingListRecipeDto.toEntity(accountId: String): ShoppingListRecipeEntity? {
    val listRecipeId = id ?: return null
    return ShoppingListRecipeEntity(
        accountId = accountId,
        localId = listRecipeId,
        serverId = listRecipeId,
        name = name,
        recipeId = recipe,
        recipeName = labelName(),
        mealPlanId = mealplan,
        servings = servings,
        createdBy = createdBy?.id,
    )
}

/** The small label of a recipe or meal on the list: the recipe's name, else the list recipe's own name. */
private fun ShoppingListRecipeDto.labelName(): String? = recipeData?.name?.takeIf { it.isNotBlank() } ?: name.takeIf { it.isNotBlank() }

internal fun ShoppingListDto.toEntity(accountId: String): ShoppingListEntity? =
    id?.let { ShoppingListEntity(accountId = accountId, id = it, name = name, description = description, color = color) }

internal fun SupermarketCategoryDto.toEntity(accountId: String): SupermarketCategoryEntity? =
    id?.let { SupermarketCategoryEntity(accountId = accountId, id = it, name = name, description = description) }

internal fun SupermarketDto.toEntity(accountId: String): SupermarketEntity? = id?.let {
    SupermarketEntity(
        accountId = accountId,
        id = it,
        name = name,
        description = description,
        shoppingListIds = ShoppingIds.encode(shoppingLists.mapNotNull { list -> list.id }),
    )
}

/** The store's `category_to_supermarket` as relation rows. */
internal fun SupermarketDto.relationEntities(accountId: String): List<SupermarketCategoryRelationEntity> {
    val storeId = id ?: return emptyList()
    return categoryToSupermarket.mapNotNull { relation ->
        val relationId = relation.id ?: return@mapNotNull null
        val categoryId = relation.category?.id ?: return@mapNotNull null
        SupermarketCategoryRelationEntity(
            accountId = accountId,
            id = relationId,
            supermarketId = relation.supermarket ?: storeId,
            categoryId = categoryId,
            relationOrder = relation.order,
        )
    }
}

/** A row as the screens see it. */
internal fun ShoppingEntryEntity.toModel(onHand: Boolean, pending: Boolean, listRecipe: ShoppingListRecipeEntity?): ShoppingEntry =
    ShoppingEntry(
        id = localId,
        food = ShoppingFood(id = foodId, name = foodName, pluralName = foodPluralName, categoryId = categoryId, onHand = onHand),
        unit = unitName?.let { ShoppingUnit(id = unitId, name = it, pluralName = unitPluralName) },
        amount = amount,
        order = entryOrder,
        checked = checked,
        completedAt = completedAt?.let { Instant.fromEpochMilliseconds(it) },
        delayUntil = delayUntil?.let { Instant.fromEpochMilliseconds(it) },
        addedBy = createdByName,
        addedById = createdBy,
        recipe = listRecipeId?.let { id ->
            ShoppingRecipeLabel(
                listRecipeId = id,
                recipeId = recipeId ?: listRecipe?.recipeId,
                recipeName = recipeName ?: listRecipe?.recipeName,
                mealPlanId = mealPlanId ?: listRecipe?.mealPlanId,
                servings = listRecipe?.servings ?: 1.0,
            )
        },
        ingredientId = ingredientId,
        listIds = ShoppingIds.decode(shoppingListIds).toSet(),
        pending = pending,
    )

/** An aged-out entry for `shopping_history` (plan 3.5 window rule, 8.2). */
internal fun ShoppingEntryEntity.toHistory(archivedAt: Long): ShoppingHistoryEntity = ShoppingHistoryEntity(
    accountId = accountId,
    entryId = serverId ?: localId,
    foodId = foodId,
    foodName = foodName,
    unitId = unitId,
    unitName = unitName,
    amount = amount,
    categoryId = categoryId,
    recipeName = recipeName,
    completedAt = completedAt,
    archivedAt = archivedAt,
)

internal fun ShoppingHistoryEntity.toModel(): ShoppingHistoryItem = ShoppingHistoryItem(
    entryId = entryId,
    foodName = foodName,
    foodId = foodId,
    amount = amount,
    unitName = unitName,
    completedAt = completedAt?.let { Instant.fromEpochMilliseconds(it) },
)

internal fun ShoppingListEntity.toModel(): ShoppingList = ShoppingList(id = id, name = name, description = description, color = color)

internal fun SupermarketCategoryEntity.toModel(): SupermarketCategory = SupermarketCategory(id = id, name = name, description = description)

/** A store with its aisles in walking order; relations whose category is unknown are left out. */
internal fun SupermarketEntity.toModel(
    relations: List<SupermarketCategoryRelationEntity>,
    categories: Map<Long, SupermarketCategoryEntity>,
): Supermarket = Supermarket(
    id = id,
    name = name,
    description = description,
    aisles = relations.filter { it.supermarketId == id }
        .sortedWith(compareBy({ it.relationOrder }, { it.id }))
        .mapNotNull { relation -> categories[relation.categoryId]?.let { Aisle(relation.id, it.toModel(), relation.relationOrder) } },
    listIds = ShoppingIds.decode(shoppingListIds).toSet(),
)

/** Food names for suggestions: the stored foods (plural name too). */
internal fun FoodEntity.suggestionNames(): List<String> = listOfNotNull(name, pluralName?.takeIf { it.isNotBlank() })

/** The JSON id arrays of `shopping_list_ids` (`[1,2]`). */
internal object ShoppingIds {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(ids: Collection<Long>): String = JsonArray(ids.distinct().sorted().map { JsonPrimitive(it) }).toString()

    fun decode(text: String?): List<Long> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
    }
}
