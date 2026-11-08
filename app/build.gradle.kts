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
        versionCode = 3
        versionName = "3.0"

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
            // R8: removes unused code and optimises the rest. The single biggest
            // smoothness win for Compose on a low-end phone. Rules in proguard-rules.pro.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // NewPipeExtractor uses java.time, which only exists from API 26. Desugaring
        // back-fills it so minSdk can stay at 24.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // The download and metadata paths log every outcome, and the point of those
            // paths is that they fail soft. Without this, the stub android.jar throws
            // from Log.w and the JVM tests fail on the very branches they exist to
            // prove: "an HTTP failure returns null instead of throwing" would crash in
            // its own error handler. Nothing here asserts on Android behaviour.
            isReturnDefaultValues = true
        }
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

    // Thumbnails / image loading. The okhttp fetcher is what lets Coil load remote
    // URLs (YouTube playlist thumbnails); Coil 3 ships no network stack by default.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // YouTube sync: Google Identity authorization, encrypted token storage, HTTP.
    implementation(libs.play.services.auth)
    implementation(libs.androidx.security.crypto)
    implementation(libs.okhttp)

    // Resolves a video id to a real audio stream URL, in pure Java — no Python
    // runtime, unlike yt-dlp.
    implementation(libs.newpipe.extractor)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Periodic background playlist checks. Only the *trigger* — the downloads
    // themselves need pause/resume, which WorkManager can't express.
    implementation(libs.androidx.work.runtime.ktx)

    // Audio playback + effects (wired up in the playback step).
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    // Trim / cut tool: clips and joins audio, re-encoding to AAC.
    implementation(libs.androidx.media3.transformer)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
