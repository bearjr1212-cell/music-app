package io.github.ytdlpandroid.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.ytdlpandroid.PlaybackHistoryEntry
import io.github.ytdlpandroid.PlaylistStore
import io.github.ytdlpandroid.SavedPlaylist
import io.github.ytdlpandroid.SavedTrackMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing playlists and playback history.
 * Wraps PlaylistStore with coroutine support for async operations.
 */
@Singleton
class PlaylistRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    
    private val playlistStore by lazy { PlaylistStore(context) }
    
    /**
     * Retrieves all playlists from the database.
     */
    suspend fun getPlaylists(): List<SavedPlaylist> = withContext(Dispatchers.IO) {
        playlistStore.getPlaylists()
    }

    /**
     * Retrieves a single playlist by case-insensitive name, including its tracks.
     */
    suspend fun findPlaylistByName(name: String): SavedPlaylist? = withContext(Dispatchers.IO) {
        playlistStore.findPlaylistByName(name)
    }

    /**
     * Creates a new playlist with the given name.
     */
    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        playlistStore.createPlaylist(name)
    }
    
    /**
     * Adds a track to a playlist.
     */
    suspend fun addTrack(playlistId: Long, trackUri: String): Boolean = withContext(Dispatchers.IO) {
        playlistStore.addTrack(playlistId, trackUri)
    }

    /**
     * Adds many tracks to a playlist inside a single transaction, returning how many were added.
     */
    suspend fun addTracks(playlistId: Long, trackUris: List<String>): Int = withContext(Dispatchers.IO) {
        playlistStore.addTracks(playlistId, trackUris)
    }
    
    /**
     * Removes a track from a playlist.
     */
    suspend fun removeTrack(playlistId: Long, trackUri: String): Boolean = withContext(Dispatchers.IO) {
        playlistStore.removeTrack(playlistId, trackUri)
    }
    
    /**
     * Moves a track to a new position within a playlist.
     */
    suspend fun moveTrack(playlistId: Long, fromIndex: Int, toIndex: Int): Boolean = withContext(Dispatchers.IO) {
        playlistStore.moveTrack(playlistId, fromIndex, toIndex)
    }
    
    /**
     * Deletes a playlist and all its tracks.
     */
    suspend fun deletePlaylist(playlistId: Long): Boolean = withContext(Dispatchers.IO) {
        playlistStore.deletePlaylist(playlistId)
    }
    
    /**
     * Retrieves metadata for a specific track.
     */
    suspend fun getTrackMetadata(trackUri: String): SavedTrackMetadata? = withContext(Dispatchers.IO) {
        playlistStore.getTrackMetadata(trackUri)
    }
    
    /**
     * Retrieves metadata for all tracks.
     */
    suspend fun getAllTrackMetadata(): List<SavedTrackMetadata> = withContext(Dispatchers.IO) {
        playlistStore.getAllTrackMetadata()
    }
    
    /**
     * Saves or updates track metadata.
     */
    suspend fun saveTrackMetadata(
        trackUri: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long?,
        artworkPath: String?,
    ) = withContext(Dispatchers.IO) {
        playlistStore.saveTrackMetadata(trackUri, title, artist, album, durationMs, artworkPath)
    }
    
    /**
     * Records a playback history entry.
     */
    suspend fun recordPlayback(
        trackUri: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        playedAt: Long = System.currentTimeMillis(),
    ): Long = withContext(Dispatchers.IO) {
        playlistStore.recordPlayback(trackUri, title, artist, album, durationMs, playedAt)
    }
    
    /**
     * Retrieves playback history with pagination.
     */
    suspend fun getPlaybackHistory(limit: Int, beforeId: Long? = null): List<PlaybackHistoryEntry> =
        withContext(Dispatchers.IO) {
            playlistStore.getPlaybackHistory(limit, beforeId)
        }
    
    /**
     * Deletes a specific playback history entry.
     */
    suspend fun deletePlaybackHistoryEntry(id: Long): Boolean = withContext(Dispatchers.IO) {
        playlistStore.deletePlaybackHistoryEntry(id)
    }
    
    /**
     * Clears all playback history.
     */
    suspend fun clearPlaybackHistory(): Int = withContext(Dispatchers.IO) {
        playlistStore.clearPlaybackHistory()
    }
}
