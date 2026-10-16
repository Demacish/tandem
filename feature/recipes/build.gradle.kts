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
    // BackHandler for the keyword side panel on expanded windows (plan 6.5).
    implementation(libs.androidx.activity.compose)

    testImplementation(projects.core.testing)
    // Web parity of the detail (RecipeDetailWebParityTest) reads the recorded recipes and scaling-golden.json.
    testImplementation(libs.kotlinx.serialization.json)
}

// The committed Tandoor fixtures (api-schema/fixtures/), read by RecipeDetailWebParityTest like
// core:domain's golden test. Declared as an input so a changed fixture reruns the tests.
val fixturesDir: Directory = layout.projectDirectory.dir("../../api-schema/fixtures")

tasks.withType<Test>().configureEach {
    systemProperty("tandem.fixturesDir", fixturesDir.asFile.absolutePath)
    inputs.dir(fixturesDir).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("fixtures")
}
