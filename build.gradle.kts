// Top-level build file
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    // From Kotlin 2.0 the Compose compiler ships with Kotlin itself and is
    // applied as a plugin; `composeOptions.kotlinCompilerExtensionVersion` no
    // longer exists. Version tracks the Kotlin version exactly.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // Room's Gradle plugin owns schema export. Without it every variant's KSP
    // task writes to one shared directory and they race, surfacing as
    // `IllegalStateException: Empty schema file` from a truncated read.
    id("androidx.room") version "2.7.2" apply false
    // Firebase / Google Services plugins are applied in :app; declaring the
    // versions here (not below) satisfies Gradle's plugin resolution without
    // pulling the plugin classpath into every module.
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("com.google.firebase.crashlytics") version "3.0.2" apply false
}
