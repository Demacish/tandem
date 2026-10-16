// Shared app-level composables built on the design system: DemoBanner, status chips, empty /
// error / loading states, AdaptiveContentCard, and the recipe components (RecipeCard,
// IngredientRow, StepCard, RatingStars, MarkdownText, ...).
plugins {
    id("tandem.android.library")
    id("tandem.android.compose")
    id("tandem.android.roborazzi")
}

dependencies {
    // api: screens that use core:ui components also need TandemTheme, TandemIcons and the
    // primitives (feature modules depend on both anyway).
    api(projects.core.designsystem)
    // api: ErrorState(error: TandemError) exposes the core:model error type.
    api(projects.core.model)

    // --- Phase 1 (recept-ui lane): recipe components (core:ui recipe/, markdown/). ---
    // RecipeImage loads photos with Coil's singleton ImageLoader (configured by the app).
    implementation(libs.coil.compose)

    // --- Phase 1b (inställningar lane): ReportContentReady (reportFullyDrawn for the cold start
    // timing in Settings → Diagnostics). ---
    implementation(libs.androidx.activity.compose)

    // --- fix-bygg (0.1.0 review): ATF accessibility checks (assertAccessible) in the UI tests. ---
    testImplementation(projects.core.testing)
}
