// Phase 1b placeholder (plan 6.2 "Kokläge", features #6-#8): cook mode with one step per page,
// timers and "Done!" with the cook log. Declared now so no later lane edits settings.gradle.kts.
// Phase 1b: it talks to timers through core:data's TimerController (implemented in core:timer,
// bound in :app) and never depends on core:timer, core:network, core:database or core:sync
// (checkModuleGraph).
plugins {
    id("tandem.android.feature")
    id("tandem.android.roborazzi")
    // @Serializable Navigation 3 key (CookKey).
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.bundles.compose.adaptive)
    implementation(libs.coil.compose)
    // The in-context notification question (rememberLauncherForActivityResult, plan 8.7).
    implementation(libs.androidx.activity.compose)

    testImplementation(projects.core.testing)
    // --- Phase 1b (grund lane): CookKeyTest round-trips the @Serializable keys. ---
    testImplementation(libs.kotlinx.serialization.json)
}
