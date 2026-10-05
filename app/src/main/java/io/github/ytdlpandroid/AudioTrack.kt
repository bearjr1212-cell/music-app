package io.github.ytdlpandroid

import android.graphics.Bitmap
import android.net.Uri

/**
 * Represents an audio track in the user's library or a search result.
 * Shared across MainActivity, TrackRepository, and LibraryViewModel.
 */
data class AudioTrack(
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artwork: Bitmap?,
    val dateAdded: Long = 0L,
    val thumbnailUrl: String? = null,
)
