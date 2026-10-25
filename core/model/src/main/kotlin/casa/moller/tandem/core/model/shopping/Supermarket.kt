package casa.moller.tandem.core.model.shopping

/**
 * A store and its aisle order (Tandoor `Supermarket` with `category_to_supermarket`, #10).
 *
 * @property aisles the categories in walking order (the relations sorted by `order`).
 * @property listIds the named shopping lists the store is linked to.
 */
data class Supermarket(
    val id: Long,
    val name: String,
    val description: String? = null,
    val aisles: List<Aisle> = emptyList(),
    val listIds: Set<Long> = emptySet(),
)

/** An aisle / category (Tandoor `SupermarketCategory`), e.g. "Mejeri". */
data class SupermarketCategory(val id: Long, val name: String, val description: String? = null)

/**
 * A category's place in a store (Tandoor `SupermarketCategoryRelation`).
 *
 * @property relationId the relation's server id (`PATCH /api/supermarket-category-relation/{id}/`).
 * @property order the server's `order` (ascending = walking order).
 */
data class Aisle(val relationId: Long, val category: SupermarketCategory, val order: Int)
