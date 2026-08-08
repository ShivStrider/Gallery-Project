// Top-level build file
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    id("com.google.devtools.ksp") version "1.9.20-1.0.14" apply false
    // Room's Gradle plugin owns schema export. Without it every variant's KSP
    // task writes to one shared directory and they race, surfacing as
    // `IllegalStateException: Empty schema file` from a truncated read.
    id("androidx.room") version "2.6.1" apply false
    // 1.23.x is the last major line built against the Kotlin 1.9 compiler
    // frontend (2.x moves to K2/Kotlin 2.x and to the `dev.detekt` group).
    // Must stay on 1.23.x while the project is pinned to Kotlin 1.9.20 above —
    // a 2.x detekt here would fail at Gradle configuration time, not just at
    // analysis time, taking every other task (including `test`) down with it.
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
}
