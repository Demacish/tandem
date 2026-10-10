// Shared app-level composables built on the design system: DemoBanner, status chips, empty /
// error / loading states, AdaptiveContentCard. Later: RecipeCard, IngredientRow, OfflineBanner.
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
}
