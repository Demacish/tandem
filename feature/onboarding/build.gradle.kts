// Intro / onboarding: welcome, server address, login, connected, demo mode entry (plan 6.2).
plugins {
    id("tandem.android.feature")
    id("tandem.android.roborazzi")
}

dependencies {
    // BackHandler for the steps inside the intro.
    implementation(libs.androidx.activity.compose)

    // --- Phase 1 (idag-ui lane): the first sync step runs on core:data's SyncRepository; tests use
    // core:testing's FakeSyncRepository. ---
    testImplementation(projects.core.testing)
}
