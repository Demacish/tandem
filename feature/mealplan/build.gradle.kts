// Phase 2 (0.3.0 "handla & planera"): the meal plan (plan 6.2 "Veckan", features #15-#16): the
// week agenda (a week grid on tablets), the plan-meal sheet and "Handla för veckan". Shown by
// feature:shell as the Veckan tab root (docs/BYGGE.md "Fas 2 – kontrakt"). Talks to core:data's
// MealPlanRepository, RecipeRepository, ShoppingRepository, SpacePermissionsRepository and
// ForegroundSync only (checkModuleGraph).
plugins {
    id("tandem.android.feature")
    id("tandem.android.roborazzi")
    // @Serializable Navigation 3 keys, so back stacks survive process death.
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.bundles.compose.adaptive)
    implementation(libs.coil.compose)

    testImplementation(projects.core.testing)
    // MealPlanKeysTest round-trips the @Serializable keys.
    testImplementation(libs.kotlinx.serialization.json)
}
