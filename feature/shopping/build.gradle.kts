// Phase 2 (0.3.0 "handla & planera"): the shopping list (plan 6.2 "Handla", features #9-#14):
// grouped by the store's aisle order, "köp senare", store mode, list choice, sharing as text.
// Shown by feature:shell as the Handla tab root and its full-screen pages (docs/BYGGE.md
// "Fas 2 – kontrakt"). Talks to core:data's ShoppingRepository, SupermarketRepository,
// IngredientParser, SpacePermissionsRepository and ForegroundSync only (checkModuleGraph).
plugins {
    id("tandem.android.feature")
    id("tandem.android.roborazzi")
    // @Serializable Navigation 3 keys, so back stacks survive process death.
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.bundles.compose.adaptive)

    testImplementation(projects.core.testing)
    // ShoppingKeysTest round-trips the @Serializable keys.
    testImplementation(libs.kotlinx.serialization.json)
}
