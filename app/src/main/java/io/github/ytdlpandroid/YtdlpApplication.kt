package io.github.ytdlpandroid

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class YtdlpApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // Must precede any load(): setSafe throws once the singleton has been materialised.
        SingletonImageLoader.setSafe(this)
    }

    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        // Requirement 6.8, expressed against the runtime max heap rather than Coil's
        // ActivityManager.memoryClass-based maxSizePercent helper.
        .memoryCache {
            MemoryCache.Builder()
                .maxSizeBytes { (MEMORY_CACHE_MAX_PERCENT * Runtime.getRuntime().maxMemory()).toLong() }
                .build()
        }
        // Replaces Coil's auto-registered, uncapped OkHttp fetcher; user-registered
        // factories are consulted first.
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { thumbnailCallFactory() }))
        }
        .build()

    private companion object {
        const val MEMORY_CACHE_MAX_PERCENT = 0.25
    }
}
