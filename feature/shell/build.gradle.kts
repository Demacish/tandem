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
}
