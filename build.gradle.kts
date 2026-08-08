// Top-level build file
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    id("com.google.devtools.ksp") version "1.9.20-1.0.14" apply false
    // Room's Gradle plugin owns schema export. Without it every variant's KSP
    // task writes to one shared directory and they race, surfacing as
    // `IllegalStateException: Empty schema file` from a truncated read.
    id("androidx.room") version "2.6.1" apply false
}
