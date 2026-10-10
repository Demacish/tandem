// The Today tab (plan 6.2 "Idag"): tonight and tomorrow from the meal plan, carousels (pinned,
// often cooked, new, recently viewed). Shown by feature:shell as the Today tab root. Talks to
// core:data's HomeRepository only.
plugins {
    id("tandem.android.feature")
    id("tandem.android.roborazzi")
}

dependencies {
    implementation(libs.bundles.compose.adaptive)
    implementation(libs.coil.compose)

    testImplementation(projects.core.testing)
}
