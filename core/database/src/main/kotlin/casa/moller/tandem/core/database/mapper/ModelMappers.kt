package casa.moller.tandem.core.database.mapper

import casa.moller.tandem.core.database.dao.RecipeDetailWrite
import casa.moller.tandem.core.database.dao.RecipeOverviewWrite
import casa.moller.tandem.core.database.entity.IngredientEntity
import casa.moller.tandem.core.database.entity.KeywordEntity
import casa.moller.tandem.core.database.entity.MealPlanEntity
import casa.moller.tandem.core.database.entity.MealTypeEntity
import casa.moller.tandem.core.database.entity.RecipeDetailEntity
import casa.moller.tandem.core.database.entity.RecipeOverviewEntity
import casa.moller.tandem.core.database.entity.StepEntity
import casa.moller.tandem.core.database.entity.UserPreferenceEntity
import casa.moller.tandem.core.database.relation.HomeSectionRow
import casa.moller.tandem.core.database.relation.MealPlanRow
import casa.moller.tandem.core.database.relation.RecipeDetailWithSteps
import casa.moller.tandem.core.database.relation.RecipeListItem
import casa.moller.tandem.core.model.home.HomeSection
import casa.moller.tandem.core.model.home.HomeSectionType
import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.mealplan.MealType
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.recipe.Ingredient
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.model.recipe.MeasurementUnit
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.model.recipe.Step
import casa.moller.tandem.core.model.recipe.StepRecipeRef
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

// Mapping between Room rows and core:model types. Entity -> model is used by the repositories
// (core:data); model -> entity lets the sync layer store what it mapped from the API DTOs.

private const val KEYWORD_SEPARATOR = '\u001e'
private const val KEYWORD_ID_SEPARATOR = '\u001f'

private fun Long.toInstant(): Instant = Instant.fromEpochMilliseconds(this)

// ---------------------------------------------------------------- entity -> model

/** The list entry as core:model's [RecipeOverview]. */
fun RecipeListItem.toModel(): RecipeOverview = overview.toModel(
    keywords = decodeKeywordList(keywordList),
    availableOffline = availableOffline,
    pinned = pinned,
)

/** An overview row as [RecipeOverview]; local state and keywords are passed in. */
fun RecipeOverviewEntity.toModel(
    keywords: List<Keyword> = emptyList(),
    availableOffline: Boolean = false,
    pinned: Boolean = false,
): RecipeOverview = RecipeOverview(
    id = serverId,
    name = name,
    description = description,
    imageUrl = imageUrl,
    workingMinutes = workingMinutes,
    waitingMinutes = waitingMinutes,
    rating = rating,
    lastCooked = lastCookedEpochMillis?.toInstant(),
    timesCooked = timesCooked,
    keywords = keywords,
    isNew = isNew,
    isPrivate = isPrivate,
    updatedAt = updatedAtEpochMillis?.toInstant(),
    availableOffline = availableOffline,
    pinned = pinned,
)

/** Unpacks `RecipeListItem.keywordList` (`id<US>name` pairs separated by `<RS>`), sorted by name. */
fun decodeKeywordList(packed: String?): List<Keyword> {
    if (packed.isNullOrEmpty()) return emptyList()
    return packed.split(KEYWORD_SEPARATOR).mapNotNull { pair ->
        val split = pair.indexOf(KEYWORD_ID_SEPARATOR)
        val id = if (split > 0) pair.substring(0, split).toLongOrNull() else null
        id?.let { Keyword(id = it, name = pair.substring(split + 1)) }
    }.sortedBy { it.name.lowercase() }
}

fun KeywordEntity.toModel(): Keyword = Keyword(id = id, name = name, description = description, parentId = parentId)

/** A meal type; an unreadable `time` becomes null. */
fun MealTypeEntity.toModel(): MealType = MealType(
    id = id,
    name = name,
    order = typeOrder,
    time = time?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
    color = color,
)

/**
 * A planned meal. A meal type that is not stored yet becomes a nameless placeholder with the
 * right id; the entry keeps its server id (or its negative local id when created offline).
 */
fun MealPlanRow.toModel(): MealPlanEntry {
    val from = LocalDate.parse(entry.fromDate)
    return MealPlanEntry(
        id = entry.serverId ?: entry.localId,
        title = entry.title,
        recipe = recipe?.toModel(),
        servings = entry.servings,
        note = entry.note,
        fromDate = from,
        toDate = runCatching { LocalDate.parse(entry.toDate) }.getOrDefault(from),
        mealType = mealType?.toModel() ?: MealType(id = entry.mealTypeId, name = ""),
        onShoppingList = entry.shopping,
    )
}

/**
 * The stored detail as core:model's [Recipe], or null for a stub (no detail stored). Name,
 * image, rating and dates come from the [overview] row (stored in the same transaction).
 */
fun RecipeDetailWithSteps.toRecipe(overview: RecipeOverviewEntity?, keywords: List<Keyword> = emptyList()): Recipe? {
    if (detail.rawJson == null) return null
    val ingredientsByStep = ingredients.groupBy { it.stepPosition }
    val modelSteps = steps.sortedBy { it.position }.map { step ->
        step.toModel(ingredientsByStep[step.position].orEmpty().sortedBy { it.position }.map { it.toModel() })
    }
    return Recipe(
        id = detail.recipeId,
        name = overview?.name.orEmpty(),
        description = overview?.description,
        imageUrl = overview?.imageUrl,
        servings = detail.servings.coerceAtLeast(1),
        servingsText = detail.servingsText,
        workingMinutes = detail.workingMinutes,
        waitingMinutes = detail.waitingMinutes,
        rating = overview?.rating,
        lastCooked = overview?.lastCookedEpochMillis?.toInstant(),
        keywords = keywords,
        steps = modelSteps,
        sourceUrl = detail.sourceUrl,
        isPrivate = overview?.isPrivate ?: false,
        showIngredientOverview = detail.showIngredientOverview,
        nutrition = detail.nutrition,
        diameter = detail.diameter,
        diameterText = detail.diameterText,
        createdAt = overview?.createdAtEpochMillis?.toInstant(),
        updatedAt = overview?.updatedAtEpochMillis?.toInstant(),
    )
}

fun StepEntity.toModel(ingredients: List<Ingredient>): Step = Step(
    id = stepId,
    name = name,
    instruction = instruction,
    timeMinutes = timeMinutes,
    order = stepOrder,
    ingredients = ingredients,
    showAsHeader = showAsHeader,
    showIngredientsTable = showIngredientsTable,
    stepRecipe = stepRecipeId?.let { StepRecipeRef(recipeId = it, name = stepRecipeName) },
)

fun IngredientEntity.toModel(): Ingredient = Ingredient(
    id = ingredientId,
    food = foodName?.let { Food(id = foodId ?: 0, name = it, pluralName = foodPluralName, onHand = foodOnHand) },
    unit = unitName?.let { MeasurementUnit(id = unitId ?: 0, name = it, pluralName = unitPluralName, baseUnit = unitBaseUnit) },
    amount = amount,
    note = note,
    isHeader = isHeader,
    noAmount = noAmount,
    originalText = originalText,
    order = ingredientOrder,
    conversions = IngredientConversions.decode(conversionsJson),
)

fun UserPreferenceEntity.toModel(): UserPreferences = UserPreferences(
    useFractions = useFractions,
    ingredientDecimals = ingredientDecimals,
    defaultUnit = defaultUnit,
    useKj = useKj,
    showStepIngredients = showStepIngredients,
    defaultMealTypeId = defaultMealTypeId,
    shoppingAutoSyncSeconds = shoppingAutoSyncSeconds,
    mealPlanAutoAddShopping = mealPlanAutoAddShopping,
    mealPlanAutoExcludeOnHand = mealPlanAutoExcludeOnHand,
    mealPlanAutoIncludeRelated = mealPlanAutoIncludeRelated,
    defaultDelayHours = defaultDelayHours,
    filterToSupermarket = filterToSupermarket,
    shoppingRecentDays = shoppingRecentDays,
    shoppingAddOnHand = shoppingAddOnHand,
    commentsEnabled = commentsEnabled,
)

/** Groups carousel rows into sections in [HomeSectionType] order; empty sections are left out. */
fun List<HomeSectionRow>.toHomeSections(): List<HomeSection> {
    val byType = groupBy { it.sectionType }
    return HomeSectionType.entries.mapNotNull { type ->
        val recipes = byType[type].orEmpty().sortedBy { it.position }.map { it.item.toModel() }
        if (recipes.isEmpty()) null else HomeSection(type, recipes)
    }
}

// ---------------------------------------------------------------- model -> entity

fun Keyword.toEntity(accountId: String): KeywordEntity = KeywordEntity(
    accountId = accountId,
    id = id,
    name = name,
    description = description,
    parentId = parentId,
)

fun MealType.toEntity(accountId: String): MealTypeEntity = MealTypeEntity(
    accountId = accountId,
    id = id,
    name = name,
    typeOrder = order,
    time = time?.toString(),
    color = color,
)

/**
 * A meal plan entry from the server (`local_id = server id`). [fromRaw]/[toRaw] are the
 * server's date-times as sent; the dates in the entry are already the server-zone days.
 */
fun MealPlanEntry.toEntity(
    accountId: String,
    fromRaw: String? = null,
    toRaw: String? = null,
    createdBy: Long? = null,
): MealPlanEntity = MealPlanEntity(
    accountId = accountId,
    localId = id,
    serverId = id,
    title = title,
    recipeId = recipe?.id,
    servings = servings,
    note = note,
    fromDate = fromDate.toString(),
    toDate = toDate.toString(),
    fromRaw = fromRaw,
    toRaw = toRaw,
    mealTypeId = mealType.id,
    shopping = onShoppingList,
    createdBy = createdBy,
)

fun UserPreferences.toEntity(accountId: String, fetchedAtEpochMillis: Long): UserPreferenceEntity = UserPreferenceEntity(
    accountId = accountId,
    useFractions = useFractions,
    ingredientDecimals = ingredientDecimals,
    defaultUnit = defaultUnit,
    useKj = useKj,
    showStepIngredients = showStepIngredients,
    defaultMealTypeId = defaultMealTypeId,
    shoppingAutoSyncSeconds = shoppingAutoSyncSeconds,
    mealPlanAutoAddShopping = mealPlanAutoAddShopping,
    mealPlanAutoExcludeOnHand = mealPlanAutoExcludeOnHand,
    mealPlanAutoIncludeRelated = mealPlanAutoIncludeRelated,
    defaultDelayHours = defaultDelayHours,
    filterToSupermarket = filterToSupermarket,
    shoppingRecentDays = shoppingRecentDays,
    shoppingAddOnHand = shoppingAddOnHand,
    commentsEnabled = commentsEnabled,
    fetchedAtEpochMillis = fetchedAtEpochMillis,
)

/** An overview from a list response, with its keyword labels (`RecipeDao.upsertOverviews`). */
fun RecipeOverview.toOverviewWrite(accountId: String): RecipeOverviewWrite = RecipeOverviewWrite(
    overview = toOverviewEntity(accountId),
    keywords = keywords.map { it.toEntity(accountId) },
)

private fun RecipeOverview.toOverviewEntity(accountId: String): RecipeOverviewEntity = RecipeOverviewEntity(
    accountId = accountId,
    serverId = id,
    name = name,
    description = description,
    imageUrl = imageUrl,
    workingMinutes = workingMinutes,
    waitingMinutes = waitingMinutes,
    rating = rating,
    lastCookedEpochMillis = lastCooked?.toEpochMilliseconds(),
    timesCooked = timesCooked,
    isNew = isNew,
    isPrivate = isPrivate,
    updatedAtEpochMillis = updatedAt?.toEpochMilliseconds(),
)

/**
 * A fetched recipe for `RecipeDao.replaceDetail`: [rawJson] is the unmodified response body.
 * Steps are stored in the server's `order` (ties keep the response order), ingredients likewise
 * within their step.
 */
fun Recipe.toDetailWrite(accountId: String, rawJson: String, fetchedAtEpochMillis: Long): RecipeDetailWrite {
    val orderedSteps = steps.sortedBy { it.order }
    val stepRows = orderedSteps.mapIndexed { position, step ->
        StepEntity(
            accountId = accountId,
            recipeId = id,
            position = position,
            stepId = step.id,
            name = step.name,
            instruction = step.instruction,
            timeMinutes = step.timeMinutes,
            stepOrder = step.order,
            showAsHeader = step.showAsHeader,
            showIngredientsTable = step.showIngredientsTable,
            stepRecipeId = step.stepRecipe?.recipeId,
            stepRecipeName = step.stepRecipe?.name,
        )
    }
    val ingredientRows = orderedSteps.flatMapIndexed { stepPosition, step ->
        step.ingredients.sortedBy { it.order }.mapIndexed { position, ingredient ->
            ingredient.toEntity(accountId, recipeId = id, stepPosition = stepPosition, position = position)
        }
    }
    return RecipeDetailWrite(
        overview = RecipeOverviewEntity(
            accountId = accountId,
            serverId = id,
            name = name,
            description = description,
            imageUrl = imageUrl,
            workingMinutes = workingMinutes,
            waitingMinutes = waitingMinutes,
            rating = rating,
            lastCookedEpochMillis = lastCooked?.toEpochMilliseconds(),
            isPrivate = isPrivate,
            createdAtEpochMillis = createdAt?.toEpochMilliseconds(),
            updatedAtEpochMillis = updatedAt?.toEpochMilliseconds(),
        ),
        detail = RecipeDetailEntity(
            accountId = accountId,
            recipeId = id,
            rawJson = rawJson,
            servings = servings,
            servingsText = servingsText,
            workingMinutes = workingMinutes,
            waitingMinutes = waitingMinutes,
            diameter = diameter,
            diameterText = diameterText,
            sourceUrl = sourceUrl,
            showIngredientOverview = showIngredientOverview,
            nutrition = nutrition,
            fetchedAtEpochMillis = fetchedAtEpochMillis,
        ),
        steps = stepRows,
        ingredients = ingredientRows,
        keywords = keywords.map { it.toEntity(accountId) },
    )
}

private fun Ingredient.toEntity(accountId: String, recipeId: Long, stepPosition: Int, position: Int): IngredientEntity = IngredientEntity(
    accountId = accountId,
    recipeId = recipeId,
    stepPosition = stepPosition,
    position = position,
    ingredientId = id,
    foodId = food?.id,
    foodName = food?.name,
    foodPluralName = food?.pluralName,
    foodOnHand = food?.onHand ?: false,
    unitId = unit?.id,
    unitName = unit?.name,
    unitPluralName = unit?.pluralName,
    unitBaseUnit = unit?.baseUnit,
    amount = amount,
    note = note,
    isHeader = isHeader,
    noAmount = noAmount,
    originalText = originalText,
    ingredientOrder = order,
    conversionsJson = IngredientConversions.encode(conversions),
)
