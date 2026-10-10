// Pure Kotlin domain logic (ScaleIngredients, FormatAmount, TimerDetector, ...). No Android types.
plugins {
    id("tandem.jvm.library")
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.kotlinx.coroutines.core)
}
