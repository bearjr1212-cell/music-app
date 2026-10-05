package io.github.ytdlpandroid

/**
 * Central configuration constants for the application.
 * Replaces magic numbers scattered throughout the codebase.
 */
object AppConfig {
    // UI layout
    const val MAX_CONTENT_WIDTH_DP = 600

    // Download queue
    const val MAX_RETAINED_JOBS = 100
    const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 3
    const val MAX_CONCURRENT_DOWNLOADS = 10
    const val MAX_URL_COUNT = 100

    // Search pagination
    const val SEARCH_PAGE_SIZE = 20

    // Playback history pagination
    const val HISTORY_PAGE_SIZE = 50

    // Playback ticker interval
    const val TICK_INTERVAL_MS = 1000L

    // Equalizer defaults
    const val DEFAULT_EQUALIZER_LEVEL = 50
}
