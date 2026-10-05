plugins {
    id("com.android.application")
    id("com.chaquo.python")
    id("com.google.dagger.hilt.android")
    kotlin("kapt")
}

android {
    namespace = "io.github.ytdlpandroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ytdlpandroid"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9")
    
    // Hilt for dependency injection
    implementation("com.google.dagger:hilt-android:2.48")
    kapt("com.google.dagger:hilt-android-compiler:2.48")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
}

chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
            install("yt-dlp==2026.08.19")
        }
    }
}
