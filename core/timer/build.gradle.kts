// Cook-mode timers (plan 5.2 "core:timer", features #6/#7, plan 8.1): the real TimerController of
// core:data with persisted timers (DataStore), exact alarms, the ongoing countdown notification
// (Live Update on Android 16), the alarm notification with a lock-screen activity, a short
// foreground service only while an alarm rings, and the boot/clock-change receivers. Features
// never depend on this module (checkModuleGraph); the app gets the bindings through Hilt
// (docs/BYGGE.md "Timers").
plugins {
    id("tandem.android.library")
    id("tandem.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    // Implements core:data's TimerController; CookTimer and friends come from core:model.
    api(projects.core.data)
    implementation(projects.core.model)

    implementation(libs.androidx.core.ktx)
    // The timers file (files/datastore/cook_timers.json), see store/TimerStore.kt.
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(projects.core.testing)
}
