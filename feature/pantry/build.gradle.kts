// Fas S (0.4.0 "Skafferi & drycker", plan §8.8): "Hemma" — the pantry by location with expiry
// colours, "Slut" and "−1" (#26), "Packa upp" after shopping (#46), staples ("Basvaror"), and
// "Kan laga nu" with Har allt / Saknar 1 / Saknar 2, "Överraska mig" and "Använd upp" (#23, #28).
// Shown by feature:shell (entry points in docs/BYGGE.md "Fas S – kontrakt"). Talks to core:data's
// PantryRepository, StaplesRepository, MakeNowRepository, ShoppingRepository and
// SpacePermissionsRepository only (checkModuleGraph).
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
    // PantryKeysTest round-trips the @Serializable keys.
    testImplementation(libs.kotlinx.serialization.json)
}
