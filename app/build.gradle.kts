import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("androidx.room")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

val mobileFaceNetAsset = layout.projectDirectory.file("src/main/assets/mobile_face_net.tflite")
tasks.register("verifyFaceModelPresent") {
    group = "verification"
    description = "Verifies MobileFaceNet model asset exists before building installable artifacts."
    doLast {
        if (!mobileFaceNetAsset.asFile.exists()) {
            throw GradleException(
                "Missing required model asset: app/src/main/assets/mobile_face_net.tflite. " +
                    "See INSTALL.md for download + SHA-256 verification steps."
            )
        }
    }
}

android {
    namespace = "com.facealbum"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.facealbum"
        minSdk = 26
        targetSdk = 35
        // Keep versionCode monotonically increasing for every Play upload.
        versionCode = 1
        // Semantic versioning (MAJOR.MINOR.PATCH) for user-facing releases.
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            // Point at the location where `decodeReleaseKeystore` (registered
            // below) will materialise the keystore. AGP only validates that
            // storeFile exists at signing time, not at configuration time, so
            // we don't decode/write the base64 blob during every `./gradlew
            // test`, IDE sync, or lint run — only when an actual release task
            // executes.
            if (System.getenv("ANDROID_KEYSTORE_BASE64") != null) {
                storeFile = layout.buildDirectory.file("keystore.jks").get().asFile
            }
            storePassword = System.getenv("ANDROID_STORE_PASSWORD") ?: ""
            keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: ""
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (System.getenv("ANDROID_KEYSTORE_BASE64") != null)
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // AGP 8+ disables BuildConfig generation by default; FaceAlbumApp uses
        // `BuildConfig.DEBUG` to gate Timber's debug tree + crash-reporter.
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // MigrationTestHelper loads the exported schema JSON through the asset
    // manager, and Robolectric resolves assets from the *app* variant's merged
    // assets (`android_merged_assets` in test_config.properties) — a `test`
    // source set is not consulted. Wiring the schemas into `debug` only keeps
    // them out of the release APK; the migration tests are excluded from the
    // release unit-test variant to match (see testReleaseUnitTest below).
    sourceSets {
        getByName("debug") {
            assets.srcDirs(files("$projectDir/schemas"))
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// The schema JSON files live in debug assets only (see the sourceSets block),
// so the migration tests can only resolve them under the debug variant. They
// assert migration SQL, which is variant-independent — running them once is
// enough, and this keeps the release APK free of schema files.
// `tasks.named("testReleaseUnitTest")` would fail here — AGP registers the
// unit-test tasks after this script is evaluated. configureEach matches lazily.
tasks.withType<Test>().configureEach {
    if (name == "testReleaseUnitTest") {
        filter {
            excludeTestsMatching("com.facealbum.data.db.FaceAlbumDatabaseMigrationTest")
        }
    }
}

// Room schema export. Each KSP task gets its own intermediate output directory
// (build/intermediates/room/schemas/<task>/), so kspDebugKotlin and
// kspReleaseKotlin no longer race on one file; copyRoomSchemas then consolidates
// them here. Committing these JSON files is what makes MigrationTestHelper
// coverage possible.
room {
    schemaDirectory("$projectDir/schemas")
}

// Materialise the release keystore from $ANDROID_KEYSTORE_BASE64 only when an
// actual release task is about to run. Configuration time stays cheap (no
// disk write on test/lint/IDE-sync runs); the decoded keystore is also kept
// out of any non-release artefacts.
tasks.register("decodeReleaseKeystore") {
    val keystoreBase64 = System.getenv("ANDROID_KEYSTORE_BASE64")
    val keystoreFile = layout.buildDirectory.file("keystore.jks")
    onlyIf { keystoreBase64 != null }
    outputs.file(keystoreFile)
    doLast {
        val out = keystoreFile.get().asFile
        out.parentFile.mkdirs()
        out.writeBytes(Base64.getDecoder().decode(keystoreBase64))
    }
}

// Only gate the actual installable/distributable outputs on the model asset —
// not every task whose name happens to contain "Release" (test, lint, etc.).
// The same gate also wires the lazy keystore decode in front of release builds.
tasks.matching { it.name in setOf("assembleRelease", "bundleRelease", "packageRelease") }
    .configureEach {
        dependsOn("verifyFaceModelPresent")
        dependsOn("decodeReleaseKeystore")
    }

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Room persistence
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // WorkManager (background indexing)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // DataStore (user preferences)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ML Kit Face Detection
    implementation("com.google.mlkit:face-detection:16.1.6")

    // TensorFlow Lite
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ExifInterface for rotation handling
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Firebase BOM
    implementation(platform("com.google.firebase:firebase-bom:34.0.0"))
    // KTX side-modules were folded into the main artifacts in Firebase BOM
    // 33+; -ktx variants no longer publish. Use the plain module directly.
    implementation("com.google.firebase:firebase-crashlytics")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("com.google.truth:truth:1.2.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core-ktx:1.5.0")
}
