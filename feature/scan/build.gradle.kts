// Fas S (0.4.0, plan §8.8 "Skanning", B21): barcode (#27), receipt (#44) and the EXPERIMENTAL
// fridge photo (#45, B22), and the ONE review list all three end in (nothing is saved before the
// user confirms). Settings → AI (provider, model, the user's own key, the experimental warning)
// lives here too, next to the fridge photo. Scanning runs on the device: ML Kit with BUNDLED
// models (in the APK, so the sideload variant works without Play services) in CameraX; every scan
// screen also offers the system photo picker (no storage permission). Talks to core:data only
// (FoodAliasRepository, BarcodeLookup, PantryRepository, AiSettingsRepository, FridgeVision).
plugins {
    id("tandem.android.feature")
    id("tandem.android.roborazzi")
    // @Serializable Navigation 3 keys, so back stacks survive process death.
    alias(libs.plugins.kotlin.serialization)
}

android {
    defaultConfig {
        // ML Kit's component registrars are created by reflection (see the file).
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(libs.bundles.compose.adaptive)
    // skanning lane: the camera permission and the system photo picker (rememberLauncherForActivityResult).
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.camerax)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.mlkit.text.recognition)

    testImplementation(projects.core.testing)
    // ScanKeysTest round-trips the @Serializable keys.
    testImplementation(libs.kotlinx.serialization.json)
}
