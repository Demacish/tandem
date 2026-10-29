package casa.moller.tandem.core.network.dto

import casa.moller.tandem.core.network.json.TandoorJson
import casa.moller.tandem.core.network.testing.Fixture
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every DTO against EVERY recorded fixture in `api-schema/fixtures/2.6.13` and `2.7.1` (Phase 0
 * and `fas1/`; Tandoor 2.7 logs in through allauth and sends some numbers as decimals):
 *
 * 1. Each successful JSON response is routed by method and path to its DTO and decoded with
 *    [TandoorJson]; a new fixture without a route (or an explicit [notModelled] reason) fails.
 * 2. The decoded DTO, encoded again, must agree with the fixture on every field the DTO
 *    declares (numbers compared as numbers, raw JsonElement fields exactly), so a wrong type or
 *    a default silently replacing a value is caught.
 * 3. Every declared field must occur in at least one fixture, so a misspelt `@SerialName` (which
 *    the tolerant Json would silently leave at its default) is caught.
 */
@OptIn(ExperimentalSerializationApi::class)
class DtoFixturesTest {
    private class Route(val method: String, path: String, val serializer: KSerializer<*>) {
        val path = Regex(path)
    }

    private val routes = listOf(
        Route("GET", "/api/server-settings/current/", ServerSettingsDto.serializer()),
        Route("POST", "/api-token-auth/", TokenAuthResponseDto.serializer()),
        Route("POST", "/_allauth/app/v1/auth/login", AllauthResponseDto.serializer()),
        Route("POST", "/api/access-token/", AccessTokenDto.serializer()),
        Route("GET", "/api/access-token/", ListSerializer(AccessTokenDto.serializer())),
        Route("GET", "/api/user/\\d+/", UserDto.serializer()),
        Route("GET", "/api/user-preference/", ListSerializer(UserPreferenceDto.serializer())),
        Route("GET", "/api/space/current/", SpaceDto.serializer()),
        Route("GET", "/api/recipe/flat/", ListSerializer(RecipeFlatDto.serializer())),
        Route("GET", "/api/recipe/", PageDto.serializer(RecipeOverviewDto.serializer())),
        Route("POST", "/api/recipe/", RecipeDto.serializer()),
        Route("GET", "/api/recipe/\\d+/", RecipeDto.serializer()),
        Route("PATCH", "/api/recipe/\\d+/", RecipeDto.serializer()),
        Route("PATCH", "/api/step/\\d+/", StepDto.serializer()),
        Route("PATCH", "/api/ingredient/\\d+/", IngredientDto.serializer()),
        Route("GET", "/api/keyword/", PageDto.serializer(KeywordDto.serializer())),
        Route("POST", "/api/keyword/", KeywordDto.serializer()),
        Route("GET", "/api/food/", PageDto.serializer(FoodDto.serializer())),
        Route("POST", "/api/food/", FoodDto.serializer()),
        Route("PATCH", "/api/food/\\d+/", FoodDto.serializer()),
        // 0.3.0 review (fix-data): one food, the same shape as its PATCH answer.
        Route("GET", "/api/food/\\d+/", FoodDto.serializer()),
        Route("GET", "/api/unit/", PageDto.serializer(UnitDto.serializer())),
        Route("POST", "/api/unit/", UnitDto.serializer()),
        Route("PATCH", "/api/unit/\\d+/", UnitDto.serializer()),
        Route("GET", "/api/meal-type/", PageDto.serializer(MealTypeDto.serializer())),
        Route("POST", "/api/meal-type/", MealTypeDto.serializer()),
        Route("GET", "/api/meal-plan/", PageDto.serializer(MealPlanDto.serializer())),
        Route("POST", "/api/meal-plan/", MealPlanDto.serializer()),
        // Phase 2 (matsedel lane): one meal, and its PATCH answer.
        Route("GET", "/api/meal-plan/\\d+/", MealPlanDto.serializer()),
        Route("PATCH", "/api/meal-plan/\\d+/", MealPlanDto.serializer()),
        Route("GET", "/api/recipe-book/", PageDto.serializer(RecipeBookDto.serializer())),
        Route("POST", "/api/recipe-book/", RecipeBookDto.serializer()),
        Route("GET", "/api/recipe-book-entry/", PageDto.serializer(RecipeBookEntryDto.serializer())),
        Route("POST", "/api/recipe-book-entry/", RecipeBookEntryDto.serializer()),
        Route("GET", "/api/cook-log/", PageDto.serializer(CookLogDto.serializer())),
        Route("POST", "/api/cook-log/", CookLogDto.serializer()),
        Route("POST", "/api/view-log/", ViewLogDto.serializer()),
        // --- Phase 2 (inköp-data lane): the shopping list, stores, aisles and the parser. ---
        Route("GET", "/api/shopping-list-entry/", PageDto.serializer(ShoppingListEntryDto.serializer())),
        Route("POST", "/api/shopping-list-entry/", ShoppingListEntryDto.serializer()),
        Route("GET", "/api/shopping-list-entry/\\d+/", ShoppingListEntryDto.serializer()),
        Route("PATCH", "/api/shopping-list-entry/\\d+/", ShoppingListEntryDto.serializer()),
        Route("POST", "/api/shopping-list-entry/bulk/", ShoppingBulkCheckDto.serializer()),
        Route("POST", "/api/shopping-list-recipe/", ShoppingListRecipeDto.serializer()),
        // 0.3.0 review (fix-data): read before a retried CREATE (an answer lost to a timeout).
        Route("GET", "/api/shopping-list-recipe/", PageDto.serializer(ShoppingListRecipeDto.serializer())),
        Route("PATCH", "/api/shopping-list-recipe/\\d+/", ShoppingListRecipeDto.serializer()),
        Route("GET", "/api/shopping-list/", PageDto.serializer(ShoppingListDto.serializer())),
        Route("POST", "/api/shopping-list/", ShoppingListDto.serializer()),
        Route("GET", "/api/supermarket/", PageDto.serializer(SupermarketDto.serializer())),
        Route("POST", "/api/supermarket/", SupermarketDto.serializer()),
        Route("GET", "/api/supermarket-category/", PageDto.serializer(SupermarketCategoryDto.serializer())),
        Route("POST", "/api/supermarket-category/", SupermarketCategoryDto.serializer()),
        Route("GET", "/api/supermarket-category-relation/", PageDto.serializer(SupermarketCategoryRelationDto.serializer())),
        Route("POST", "/api/supermarket-category-relation/", SupermarketCategoryRelationDto.serializer()),
        Route("PATCH", "/api/supermarket-category-relation/\\d+/", SupermarketCategoryRelationDto.serializer()),
        Route("POST", "/api/ingredient-parser/post/", IngredientParserResponseDto.serializer()),
        // --- Phase 2 (böcker lane): saved filters (smart books) and the user's role. ---
        Route("POST", "/api/custom-filter/", CustomFilterDto.serializer()),
        Route("GET", "/api/user-space/all_personal/", ListSerializer(UserSpaceDto.serializer())),
        // --- 0.3.0 komplettering: "Delad med: …" (the household's members, plan 8.6). ---
        Route("GET", "/api/user-space/", PageDto.serializer(UserSpaceDto.serializer())),
        Route("GET", "/api/user/", ListSerializer(UserDto.serializer())),
    )

    /** Successful JSON fixtures that no DTO models, and why (Phase 0 names are numbered per version). */
    private val notModelled = mapOf(
        "fas1/seed-keyword-move" to "seeding only ({msg}); the app never moves keywords",
        "fas1/recipe-image-put" to "image upload belongs to import (Phase 3)",
        "fas1/seed-unit-conversion-create" to "the app reads conversions from the ingredients",
        "fas1/seed-unit-conversion-create-food" to "the app reads conversions from the ingredients",
        "fas1/unit-conversion-list" to "the app reads conversions from the ingredients",
        // 0.3.0 review (fix-data): the Phase 2 recordings the app has no DTO for.
        "fas2/household-list-owner" to "capability probe only (CapabilityProbeService), the body is never parsed",
        "fas2/recipe-shopping-put" to "contract probe; the app uses shopping-list-recipe + entries, not recipe/{id}/shopping/",
        "fas2/recipe-shopping-put-delete" to "contract probe; the app uses shopping-list-recipe + entries, not recipe/{id}/shopping/",
        "fas2/shopping-list-recipe-bulk-create-entries" to "contract probe; the app creates each entry itself (ids for the outbox)",
        "fas2/user-preference-patch" to "contract setup; the app only reads the preferences",
        "fas2/user-preference-patch-autoadd" to "contract setup; the app only reads the preferences",
        // Fas S (grund lane): recordings whose DTO the lane adds with its route (docs/BYGGE.md
        // "Fas S – kontrakt"); the lane removes its lines here in the same commit.
        "fas-s/food-batch-update-off" to FAS_S_HEMMA_DATA,
        "fas-s/food-batch-update-on-hand" to FAS_S_HEMMA_DATA,
        "fas-s/food-batch-update-substitute" to FAS_S_HEMMA_DATA,
        "fas-s/inventory-entry-create" to FAS_S_HEMMA_DATA,
        "fas-s/inventory-entry-create-freezer" to FAS_S_HEMMA_DATA,
        "fas-s/inventory-entry-create-no-date" to FAS_S_HEMMA_DATA,
        "fas-s/inventory-entry-create-zero" to FAS_S_HEMMA_DATA,
        "fas-s/inventory-entry-list" to FAS_S_HEMMA_DATA,
        "fas-s/inventory-entry-list-food" to FAS_S_HEMMA_DATA,
        "fas-s/inventory-entry-list-location" to FAS_S_HEMMA_DATA,
        "fas-s/inventory-entry-patch-amount" to FAS_S_HEMMA_DATA,
        "fas-s/inventory-entry-patch-move" to FAS_S_HEMMA_DATA,
        "fas-s/inventory-entry-patch-note" to FAS_S_HEMMA_DATA,
        "fas-s/inventory-entry-patch-zero" to FAS_S_HEMMA_DATA,
        "fas-s/inventory-location-create" to FAS_S_HEMMA_DATA,
        "fas-s/inventory-location-create-Frys" to FAS_S_HEMMA_DATA,
        "fas-s/inventory-location-list" to FAS_S_HEMMA_DATA,
        "fas-s/inventory-location-list-as-outsider" to FAS_S_HEMMA_DATA,
        "fas-s/inventory-location-list-empty" to FAS_S_HEMMA_DATA,
        "fas-s/inventory-location-patch" to FAS_S_HEMMA_DATA,
        "fas-s/inventory-log-list-entry" to FAS_S_HEMMA_DATA,
        "fas-s/automation-create-barcode" to FAS_S_SKANNING,
        "fas-s/automation-create-receipt" to FAS_S_SKANNING,
        "fas-s/automation-list-food-alias" to FAS_S_SKANNING,
        "fas-s/automation-patch" to FAS_S_SKANNING,
        "fas-s/keyword-get-child" to FAS_S_DRYCKER,
        "fas-s/keyword-move" to FAS_S_DRYCKER,
        "fas-s/recipe-image-put-drink" to FAS_S_DRYCKER,
    )

    private companion object {
        const val FAS_S_HEMMA_DATA = "Fas S: the hemma-data lane adds the inventory and food batch DTOs"
        const val FAS_S_SKANNING = "Fas S: the skanning lane adds the automation (FOOD_ALIAS) DTO"
        const val FAS_S_DRYCKER = "Fas S: the drycker lane adds keyword move/detail and the drink image upload"
    }

    @Test
    fun everySuccessfulJsonFixtureOfTandoor2613DecodesWithItsDto() = checkFixtures("2.6.13", notModelled)

    @Test
    fun everySuccessfulJsonFixtureOfTandoor271DecodesWithItsDto() = checkFixtures("2.7.1", notModelled)

    private fun checkFixtures(version: String, notModelled: Map<String, String>) {
        val names = Fixture.allNames(version)
        assertTrue("Fixtures not found: $names", names.size > 100)
        assertTrue("Stale notModelled entries: ${notModelled.keys - names.toSet()}", names.containsAll(notModelled.keys))

        val check = Check()
        var decoded = 0
        for (name in names) {
            val fixture = Fixture.load(name, version)
            val body = fixture.body?.takeUnless { it is JsonNull }
            val path = fixture.path.substringBefore('?')
            val route = routes.firstOrNull { it.method == fixture.method && it.path.matches(path) }
            val skipped = name in notModelled
            if (body == null || fixture.status !in 200..299 || skipped) continue
            assertNotNull("No DTO for $name (${fixture.method} $path): add a route or a notModelled reason", route)
            @Suppress("UNCHECKED_CAST")
            val serializer = route!!.serializer as KSerializer<Any?>
            val value = try {
                TandoorJson.decodeFromJsonElement(serializer, body)
            } catch (e: Exception) {
                throw AssertionError("$name does not decode with ${serializer.descriptor.serialName}", e)
            }
            check.compare(serializer.descriptor, body, TandoorJson.encodeToJsonElement(serializer, value), name)
            decoded++
        }

        assertTrue("Only $decoded fixtures decoded", decoded >= 60)
        assertTrue(check.problems.joinToString("\n"), check.problems.isEmpty())
        val neverSeen = check.declared - check.seen
        assertTrue("Declared fields that no fixture contains (wrong @SerialName?): $neverSeen", neverSeen.isEmpty())
    }

    private class Check {
        val declared = sortedSetOf<String>()
        val seen = sortedSetOf<String>()
        val problems = mutableListOf<String>()

        fun compare(descriptor: SerialDescriptor, fixture: JsonElement?, encoded: JsonElement?, path: String) {
            // null in a fixture decodes to null or to the default; nothing to compare.
            if (fixture == null || fixture is JsonNull) return
            if (descriptor.serialName.startsWith("kotlinx.serialization.json.")) {
                if (fixture != encoded) problems += "$path: raw JSON differs"
                return
            }
            when (descriptor.kind) {
                StructureKind.CLASS -> compareObject(descriptor, fixture, encoded, path)

                StructureKind.LIST -> {
                    val items = fixture as? JsonArray ?: return fail("$path: expected an array")
                    val encodedItems = encoded as? JsonArray
                    if (encodedItems == null || encodedItems.size != items.size) {
                        return fail("$path: ${items.size} items, DTO has ${encodedItems?.size}")
                    }
                    items.forEachIndexed { i, item -> compare(descriptor.getElementDescriptor(0), item, encodedItems[i], "$path[$i]") }
                }

                is PrimitiveKind -> {
                    val value = fixture as? JsonPrimitive ?: return fail("$path: expected a primitive, got $fixture")
                    if (!samePrimitive(value, encoded as? JsonPrimitive)) fail("$path: fixture $value, DTO $encoded")
                }

                else -> Unit
            }
        }

        private fun compareObject(descriptor: SerialDescriptor, fixture: JsonElement, encoded: JsonElement?, path: String) {
            val obj = fixture as? JsonObject ?: return fail("$path: expected an object")
            val encodedObj = encoded as? JsonObject ?: JsonObject(emptyMap())
            val className = descriptor.serialName.removeSuffix("?").substringAfterLast('.')
            for (i in 0 until descriptor.elementsCount) {
                val key = descriptor.getElementName(i)
                declared += "$className.$key"
                if (key in obj) {
                    seen += "$className.$key"
                    compare(descriptor.getElementDescriptor(i), obj[key], encodedObj[key], "$path.$key")
                }
            }
        }

        private fun samePrimitive(fixture: JsonPrimitive, encoded: JsonPrimitive?): Boolean = when {
            encoded == null -> false
            fixture.isString -> encoded.isString && encoded.content == fixture.content
            fixture.booleanOrNull != null -> !encoded.isString && encoded.booleanOrNull == fixture.booleanOrNull
            else -> !encoded.isString && encoded.doubleOrNull != null && encoded.doubleOrNull == fixture.doubleOrNull
        }

        private fun fail(message: String) {
            problems += message
        }
    }
}
