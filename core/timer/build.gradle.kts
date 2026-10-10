// Phase 1b placeholder (plan 5.2 "core:timer", features #6/#7): TimerRepository, AlarmScheduler
// and the ringing service for cook mode timers. Declared now so no later lane edits
// settings.gradle.kts. Uses core:domain's TimerDetector for times found in step texts.
plugins {
    id("tandem.android.library")
    id("tandem.android.hilt")
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.domain)
    implementation(libs.kotlinx.coroutines.android)
}
