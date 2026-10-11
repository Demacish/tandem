// Recipe list (search offline + server, filters, sort) and recipe detail (servings, scaled
// amounts, fractions, conversions, steps), plan 6.2 / features #3-#5. Shown by feature:shell:
// the Recipes tab root and the full-screen / list-detail recipe detail (docs/BYGGE.md
// "Navigering"). Talks to core:data's RecipeRepository only.
plugins {
    id("tandem.android.feature")
    id("tandem.android.roborazzi")
    // @Serializable Navigation 3 keys (RecipeDetailKey), so back stacks survive process death.
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // List-detail layouts on medium/expanded widths and window size classes.
    implementation(libs.bundles.compose.adaptive)
    // Recipe images (the app configures the shared ImageLoader with Tandem's OkHttp client).
    implementation(libs.coil.compose)
    // The grid/list choice of the recipe list, remembered on the device (RecipeListPreferences).
    // The DataStore library itself, not core:datastore (which holds account data).
    implementation(libs.androidx.datastore.preferences)

    testImplementation(projects.core.testing)
}
