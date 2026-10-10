// Phase 1b placeholder (plan 6.2 "Kokläge", features #6-#8): cook mode with one step per page,
// timers and "Done!" with the cook log. Declared now so no later lane edits settings.gradle.kts.
// It may add core:timer (timers, alarms) to the usual core modules; never core:network,
// core:database or core:sync (checkModuleGraph).
plugins {
    id("tandem.android.feature")
    id("tandem.android.roborazzi")
    // @Serializable Navigation 3 key (CookKey).
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.bundles.compose.adaptive)
    implementation(libs.coil.compose)

    testImplementation(projects.core.testing)
}
