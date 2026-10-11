package casa.moller.tandem.core.sync.remote

import casa.moller.tandem.core.database.dao.RecipeIndexEntry
import casa.moller.tandem.core.database.dao.RecipeOverviewWrite
import casa.moller.tandem.core.database.entity.FoodEntity
import casa.moller.tandem.core.database.entity.KeywordEntity
import casa.moller.tandem.core.database.entity.MealPlanEntity
import casa.moller.tandem.core.database.entity.MealTypeEntity
import casa.moller.tandem.core.database.entity.RecipeOverviewEntity
import casa.moller.tandem.core.database.entity.UnitEntity
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.recipe.Ingredient
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.model.recipe.MeasurementUnit
import casa.moller.tandem.core.model.recipe.NutritionPresence
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.recipe.RecipeSort
import casa.moller.tandem.core.model.recipe.Step
import casa.moller.tandem.core.model.recipe.StepRecipeRef
import casa.moller.tandem.core.model.recipe.UnitConversion
import casa.moller.tandem.core.network.api.RecipeListQuery
import casa.moller.tandem.core.network.api.RecipeSortOrder
import casa.moller.tandem.core.network.dto.CookLogDto
import casa.moller.tandem.core.network.dto.FoodDto
import casa.moller.tandem.core.network.dto.IngredientDto
import casa.moller.tandem.core.network.dto.KeywordDto
import casa.moller.tandem.core.network.dto.MealPlanDto
import casa.moller.tandem.core.network.dto.MealTypeDto
import casa.moller.tandem.core.network.dto.RecipeDto
import casa.moller.tandem.core.network.dto.RecipeFlatDto
import casa.moller.tandem.core.network.dto.RecipeOverviewDto
import casa.moller.tandem.core.network.dto.StepDto
import casa.moller.tandem.core.network.dto.UnitDto
import casa.moller.tandem.core.network.dto.UserPreferenceDto
import casa.moller.tandem.core.network.json.TandoorDates
import kotlin.math.floor
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

// Tandoor DTOs (core:network) -> core:model and Room rows (core:database), plan 5.2 "mappers
// DTO <-> Entity <-> Model". Tolerant like the DTOs (plan 3.6): an object without an id is
// skipped, never a crash. Entity -> model mapping lives in core:database's mapper package.

private const val DAY_LENGTH = 10

/** Epoch milliseconds of a Tandoor date-time in any of its ISO-8601 shapes, or null. */
internal fun tandoorMillis(value: String?): Long? = TandoorDates.parseInstantOrNull(value)?.toEpochMilli()

/** A Tandoor date-time as [Instant], or null. */
internal fun tandoorInstant(value: String?): Instant? = tandoorMillis(value)?.let { Instant.fromEpochMilliseconds(it) }

/**
 * The calendar day of a Tandoor date-time in the offset it was sent with, the day the household
 * planned for (FACTS "Fas 1 g"): `2000-01-15T18:00:00+02:00` -> 2000-01-15. Null when unreadable.
 */
internal fun tandoorDay(value: String?): LocalDate? {
    val text = value?.trim() ?: return null
    if (text.length < DAY_LENGTH) return null
    return try {
        LocalDate.parse(text.substring(0, DAY_LENGTH))
    } catch (e: IllegalArgumentException) {
        null
    }
}

// ---------------------------------------------------------------- recipes

/** One `recipe/flat` element as an index entry (relative image paths resolved). */
internal fun RecipeFlatDto.toIndexEntry(images: ImageResolver): RecipeIndexEntry? {
    val recipeId = id ?: return null
    return RecipeIndexEntry(serverId = recipeId, name = name, imageUrl = images.resolve(image))
}

/** One `recipe/` list result as an overview row plus its keyword labels. */
internal fun RecipeOverviewDto.toOverviewWrite(accountId: String, images: ImageResolver): RecipeOverviewWrite? {
    val recipeId = id ?: return null
    return RecipeOverviewWrite(
        overview = RecipeOverviewEntity(
            accountId = accountId,
            serverId = recipeId,
            name = name,
            description = description?.takeIf { it.isNotBlank() },
            imageUrl = images.resolve(image),
            workingMinutes = workingTime.coerceAtLeast(0),
            waitingMinutes = waitingTime.coerceAtLeast(0),
            rating = rating,
            lastCookedEpochMillis = tandoorMillis(lastCooked),
            isNew = isNew,
            isPrivate = isPrivate,
            createdAtEpochMillis = tandoorMillis(createdAt),
            updatedAtEpochMillis = tandoorMillis(updatedAt),
        ),
        keywords = keywords.mapNotNull { keyword ->
            keyword.id?.let { KeywordEntity(accountId = accountId, id = it, name = keyword.label) }
        },
    )
}

/**
 * `GET /api/recipe/{id}/` as core:model's [Recipe]. Steps and ingredients keep the server's
 * values unchanged (notes, original text, every `conversions` entry: core:domain's web parity
 * depends on them, see `RecipeDisplay`).
 */
internal fun RecipeDto.toRecipe(images: ImageResolver): Recipe? {
    val recipeId = id ?: return null
    return Recipe(
        id = recipeId,
        name = name,
        description = description?.takeIf { it.isNotBlank() },
        imageUrl = images.resolve(image),
        servings = servings.coerceAtLeast(1),
        servingsText = servingsText,
        workingMinutes = workingTime.coerceAtLeast(0),
        waitingMinutes = waitingTime.coerceAtLeast(0),
        rating = rating,
        lastCooked = tandoorInstant(lastCooked),
        keywords = keywords.mapNotNull { it.toKeyword() },
        steps = steps.mapIndexed { index, step -> step.toStep(index) },
        sourceUrl = sourceUrl?.takeIf { it.isNotBlank() },
        isPrivate = isPrivate,
        showIngredientOverview = showIngredientOverview,
        nutrition = nutritionPresence(),
        diameter = diameter,
        diameterText = diameterText,
        createdAt = tandoorInstant(createdAt),
        updatedAt = tandoorInstant(updatedAt),
    )
}

private fun RecipeDto.nutritionPresence(): NutritionPresence = when {
    hasProperties -> NutritionPresence.PROPERTIES
    hasLegacyNutrition -> NutritionPresence.LEGACY
    else -> NutritionPresence.NONE
}

private fun StepDto.toStep(index: Int): Step = Step(
    id = id ?: -(index + 1L),
    name = name,
    instruction = instruction,
    timeMinutes = time.coerceAtLeast(0),
    order = order,
    ingredients = ingredients.mapIndexed { position, ingredient -> ingredient.toIngredient(position) },
    showAsHeader = showAsHeader,
    showIngredientsTable = showIngredientsTable,
    stepRecipe = stepRecipe?.let { StepRecipeRef(recipeId = it, name = stepRecipeName()) },
)

private fun IngredientDto.toIngredient(position: Int): Ingredient = Ingredient(
    id = id ?: -(position + 1L),
    food = food?.toFood(),
    unit = unit?.toUnit(),
    amount = amount,
    note = note,
    isHeader = isHeader,
    noAmount = noAmount,
    originalText = originalText,
    order = order,
    conversions = conversionList().map { UnitConversion(amount = it.amount, unitName = it.unit.orEmpty()) },
)

private fun FoodDto.toFood(): Food = Food(id = id ?: 0, name = name, pluralName = pluralName, onHand = onHand ?: false)

private fun UnitDto.toUnit(): MeasurementUnit = MeasurementUnit(id = id ?: 0, name = name, pluralName = pluralName, baseUnit = baseUnit)

internal fun KeywordDto.toKeyword(): Keyword? {
    val keywordId = id ?: return null
    return Keyword(id = keywordId, name = name.ifBlank { label }, description = description, parentId = parent)
}

// ---------------------------------------------------------------- small tables

internal fun KeywordDto.toEntity(accountId: String): KeywordEntity? {
    val keywordId = id ?: return null
    return KeywordEntity(
        accountId = accountId,
        id = keywordId,
        name = name.ifBlank { label },
        description = description,
        parentId = parent,
        fullName = fullName.takeIf { it.isNotBlank() },
    )
}

internal fun FoodDto.toEntity(accountId: String): FoodEntity? {
    val foodId = id ?: return null
    return FoodEntity(
        accountId = accountId,
        id = foodId,
        name = name,
        pluralName = pluralName,
        onHand = onHand ?: false,
        ignoreShopping = ignoreShopping,
        supermarketCategoryId = ((supermarketCategory as? JsonObject)?.get("id") as? JsonPrimitive)?.longOrNull,
    )
}

internal fun UnitDto.toEntity(accountId: String): UnitEntity? {
    val unitId = id ?: return null
    return UnitEntity(
        accountId = accountId,
        id = unitId,
        name = name,
        pluralName = pluralName,
        description = description,
        baseUnit = baseUnit,
    )
}

internal fun MealTypeDto.toEntity(accountId: String): MealTypeEntity? {
    val typeId = id ?: return null
    return MealTypeEntity(accountId = accountId, id = typeId, name = name, typeOrder = order, time = time, color = color)
}

/**
 * A meal plan entry, keyed by its server id. Days are taken in the offset the server sent
 * (FACTS "Fas 1 g"); entries without a readable `from_date` are skipped.
 */
internal fun MealPlanDto.toEntity(accountId: String): MealPlanEntity? {
    val entryId = id ?: return null
    val from = tandoorDay(fromDate) ?: return null
    val to = tandoorDay(toDate)?.takeIf { it >= from } ?: from
    return MealPlanEntity(
        accountId = accountId,
        localId = entryId,
        serverId = entryId,
        title = title,
        recipeId = recipe?.id,
        servings = servings,
        note = note,
        fromDate = from.toString(),
        toDate = to.toString(),
        fromRaw = fromDate,
        toRaw = toDate,
        mealTypeId = mealType?.id ?: 0,
        shopping = shopping,
        createdBy = createdBy,
    )
}

/** The preferences the app follows (plan 3.2). */
internal fun UserPreferenceDto.toModel(): UserPreferences = UserPreferences(
    useFractions = useFractions,
    ingredientDecimals = ingredientDecimals,
    defaultUnit = defaultUnit,
    useKj = useKj,
    showStepIngredients = showStepIngredients,
    defaultMealTypeId = defaultMealType?.id,
    shoppingAutoSyncSeconds = shoppingAutoSync,
    mealPlanAutoAddShopping = mealplanAutoaddShopping,
    mealPlanAutoExcludeOnHand = mealplanAutoexcludeOnhand,
    mealPlanAutoIncludeRelated = mealplanAutoincludeRelated,
    defaultDelayHours = defaultDelay,
    filterToSupermarket = filterToSupermarket,
    shoppingRecentDays = shoppingRecentDays,
    shoppingAddOnHand = shoppingAddOnhand,
    commentsEnabled = comments,
)

// ---------------------------------------------------------------- cook log

/**
 * "Times cooked" and "last cooked" of one recipe, counted from the cook log (FACTS "Fas 1 c":
 * `last_cooked` is always null in 2.6.13 and `-times_cooked` answers 500).
 */
internal data class CookStats(val recipeId: Long, val timesCooked: Int, val lastCookedEpochMillis: Long?)

internal fun List<CookLogDto>.toCookStats(): List<CookStats> = mapNotNull { log -> log.recipe?.let { recipeId -> recipeId to log } }
    .groupBy({ it.first }, { it.second })
    .map { (recipeId, logs) ->
        CookStats(
            recipeId = recipeId,
            timesCooked = logs.size,
            lastCookedEpochMillis = logs.mapNotNull { tandoorMillis(it.createdAt) }.maxOrNull(),
        )
    }

// ---------------------------------------------------------------- recipe search

/**
 * The server side of a recipe list query (plan 3.3): text, keywords (all must match), rating and
 * sort map to `GET /api/recipe/` filters; [RecipeQuery.maxTotalMinutes] and
 * [RecipeQuery.onlyOffline] have no server filter and are applied locally afterwards. Sorting by
 * times cooked uses `-lastcooked`, because `-times_cooked` answers 500 on 2.6 (FACTS "Fas 1 c").
 */
internal fun RecipeQuery.toListQuery(): RecipeListQuery = RecipeListQuery(
    text = text.trim().takeIf { it.isNotEmpty() },
    keywordsAnd = keywordIds,
    ratingAtLeast = minRating?.let { floor(it).toInt() }?.coerceIn(0, MAX_RATING),
    sortOrder = when (sort) {
        RecipeSort.NAME -> RecipeSortOrder.NAME
        RecipeSort.RATING -> RecipeSortOrder.RATING_DESC
        RecipeSort.LAST_COOKED, RecipeSort.TIMES_COOKED -> RecipeSortOrder.LAST_COOKED_DESC
        RecipeSort.NEWEST -> RecipeSortOrder.CREATED_DESC
        RecipeSort.TOTAL_TIME -> null
    },
    pageSize = RecipeListQuery.MAX_PAGE_SIZE,
)

private const val MAX_RATING = 5
