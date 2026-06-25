plugins {
    // AGP 9 has built-in Kotlin support, so no separate kotlin-android plugin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.andres.musicplayer"
    // Compile against API 37 (required by core-ktx 1.19 / lifecycle 2.11).
    // targetSdk stays at 36 so runtime behavior is unchanged.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.andres.musicplayer"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Self-signed key for sharing the app directly (not via Play Store).
            val keystoreFile = rootProject.file("release-key.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "andres123"
                keyAlias = "andres"
                keyPassword = "andres123"
            }
        }
    }
    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// Export Room schemas so migrations can be verified/tracked.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Room (local database). room-compiler runs via KSP.
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    // SAF folder traversal (Phase 2 scanning).
    implementation(libs.androidx.documentfile)

    // Multi-screen navigation (bottom nav + now playing).
    implementation(libs.androidx.navigation.compose)

    // Thumbnails / image loading.
    implementation(libs.coil.compose)

    // Audio playback + effects (wired up in the playback step).
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
