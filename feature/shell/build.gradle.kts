// App shell: adaptive navigation (bottom bar on phones, rail on tablets) with the five main tabs
// Idag / Recept / Veckan / Handla / Mer (plan 6.1/6.5) and the Phase 0 settings under "Mer".
plugins {
    id("tandem.android.feature")
    id("tandem.android.roborazzi")
    // @Serializable Navigation 3 keys, so the tab back stacks survive process death.
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.bundles.compose.adaptive)
    // --- Phase 1 (grund lane): the shell is the navigation host. It is the ONE feature module
    // allowed to depend on other features (checkModuleGraph): it shows their routes and maps
    // their callbacks to Navigation 3 keys (docs/BYGGE.md "Navigering"). ---
    implementation(projects.feature.recipes)
    implementation(projects.feature.home)
    // --- Phase 1b (kokläge lane): cook mode and the timer list (CookKey, TimersKey). ---
    implementation(projects.feature.cook)
    // --- Phase 1 (recept-ui lane): web pages ("Open on the web", recipe sources) open in a
    // Custom Tab, with the system browser as fallback. ---
    implementation(libs.androidx.browser)

    // --- komplettering-3: core:testing's FakeServerStatusRepository (the in-app version notice). ---
    testImplementation(projects.core.testing)
    // --- Phase 1b (grund lane): SettingsKeysTest round-trips the @Serializable settings keys. ---
    testImplementation(libs.kotlinx.serialization.json)
}
