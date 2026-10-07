plugins {
    id("com.android.application")
    id("com.chaquo.python")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
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

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9")
    
    // Timber logging
    implementation("com.jakewharton.timber:timber:5.0.1")
    
    // Hilt for dependency injection
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-android-compiler:2.60.1")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")

    // ComponentActivity: ViewModelStoreOwner + LifecycleOwner, and the viewModels() delegate
    implementation("androidx.activity:activity:1.13.0")

    // RecyclerView + ListAdapter/DiffUtil for the migrated lists
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    // Coil: sole image loader. Network fetching is a separate artifact in Coil 3.
    // 3.3.0 is the newest release that still compiles against SDK 36 (3.4+ require 37)
    // and the newest that targets Kotlin metadata readable by AGP 9.2.1's bundled compiler.
    implementation("io.coil-kt.coil3:coil:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    // Declared directly because ImageLoading.kt writes an okhttp Interceptor to enforce
    // the 5 MB thumbnail cap; matches the version coil-network-okhttp pulls in.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
            install("yt-dlp==2026.08.19")
        }
    }
}
