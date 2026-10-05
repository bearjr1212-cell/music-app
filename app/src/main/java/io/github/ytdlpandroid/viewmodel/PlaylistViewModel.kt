package io.github.ytdlpandroid.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.ytdlpandroid.PlaybackHistoryEntry
import io.github.ytdlpandroid.SavedPlaylist
import io.github.ytdlpandroid.SavedTrackMetadata
import io.github.ytdlpandroid.repository.PlaylistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for managing playlists and playback history.
 * Handles playlist CRUD operations and playback history management.
 */
@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {
    
    // Playlist state
    private val _playlists = MutableStateFlow<List<SavedPlaylist>>(emptyList())
    val playlists: StateFlow<List<SavedPlaylist>> = _playlists.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    // Playback history state
    private val _playbackHistory = MutableStateFlow<List<PlaybackHistoryEntry>>(emptyList())
    val playbackHistory: StateFlow<List<PlaybackHistoryEntry>> = _playbackHistory.asStateFlow()
    
    private val _isLoadingHistory = MutableStateFlow(false)
    val isLoadingHistory: StateFlow<Boolean> = _isLoadingHistory.asStateFlow()
    
    private val _hasMoreHistory = MutableStateFlow(true)
    val hasMoreHistory: StateFlow<Boolean> = _hasMoreHistory.asStateFlow()
    
    private var historyBeforeId: Long? = null
    private const val HISTORY_PAGE_SIZE = 50
    
    /**
     * Loads all playlists from the database.
     */
    fun loadPlaylists() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            try {
                val playlists = playlistRepository.getPlaylists()
                _playlists.value = playlists
            } catch (e: Exception) {
                _error.value = "Failed to load playlists: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Creates a new playlist with the given name.
     */
    fun createPlaylist(name: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            try {
                val id = playlistRepository.createPlaylist(name)
                loadPlaylists() // Refresh the list
            } catch (e: Exception) {
                _error.value = "Failed to create playlist: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Deletes a playlist.
     */
    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            try {
                playlistRepository.deletePlaylist(playlistId)
                loadPlaylists() // Refresh the list
            } catch (e: Exception) {
                _error.value = "Failed to delete playlist: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Adds a track to a playlist.
     */
    fun addTrack(playlistId: Long, trackUri: String) {
        viewModelScope.launch {
            try {
                val added = playlistRepository.addTrack(playlistId, trackUri)
                if (added) {
                    loadPlaylists() // Refresh to show updated track counts
                } else {
                    _error.value = "Track already in playlist or playlist not found"
                }
            } catch (e: Exception) {
                _error.value = "Failed to add track: ${e.message}"
            }
        }
    }
    
    /**
     * Removes a track from a playlist.
     */
    fun removeTrack(playlistId: Long, trackUri: String) {
        viewModelScope.launch {
            try {
                val removed = playlistRepository.removeTrack(playlistId, trackUri)
                if (removed) {
                    loadPlaylists() // Refresh to show updated track counts
                }
            } catch (e: Exception) {
                _error.value = "Failed to remove track: ${e.message}"
            }
        }
    }
    
    /**
     * Moves a track to a new position within a playlist.
     */
    fun moveTrack(playlistId: Long, fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            try {
                playlistRepository.moveTrack(playlistId, fromIndex, toIndex)
                loadPlaylists() // Refresh to show updated order
            } catch (e: Exception) {
                _error.value = "Failed to move track: ${e.message}"
            }
        }
    }
    
    /**
     * Gets metadata for a specific track.
     */
    suspend fun getTrackMetadata(trackUri: String): SavedTrackMetadata? {
        return try {
            playlistRepository.getTrackMetadata(trackUri)
        } catch (e: Exception) {
            _error.value = "Failed to get track metadata: ${e.message}"
            null
        }
    }
    
    /**
     * Saves or updates track metadata.
     */
    fun saveTrackMetadata(
        trackUri: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long?,
        artworkPath: String?,
    ) {
        viewModelScope.launch {
            try {
                playlistRepository.saveTrackMetadata(
                    trackUri,
                    title,
                    artist,
                    album,
                    durationMs,
                    artworkPath,
                )
            } catch (e: Exception) {
                _error.value = "Failed to save metadata: ${e.message}"
            }
        }
    }
    
    /**
     * Records a playback history entry.
     */
    fun recordPlayback(
        trackUri: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        playedAt: Long = System.currentTimeMillis(),
    ) {
        viewModelScope.launch {
            try {
                playlistRepository.recordPlayback(trackUri, title, artist, album, durationMs, playedAt)
            } catch (e: Exception) {
                // Don't show error for history recording failures
                android.util.Log.e("PlaylistViewModel", "Failed to record playback", e)
            }
        }
    }
    
    /**
     * Loads playback history with pagination.
     */
    fun loadPlaybackHistory(loadMore: Boolean = false) {
        viewModelScope.launch {
            if (loadMore && !_hasMoreHistory.value) return
            
            _isLoadingHistory.value = true
            _error.value = null
            
            try {
                val beforeId = if (loadMore) historyBeforeId else null
                val entries = playlistRepository.getPlaybackHistory(HISTORY_PAGE_SIZE, beforeId)
                
                if (loadMore) {
                    _playbackHistory.value = _playbackHistory.value + entries
                } else {
                    _playbackHistory.value = entries
                }
                
                _hasMoreHistory.value = entries.size == HISTORY_PAGE_SIZE
                historyBeforeId = entries.lastOrNull()?.id
            } catch (e: Exception) {
                _error.value = "Failed to load playback history: ${e.message}"
            } finally {
                _isLoadingHistory.value = false
            }
        }
    }
    
    /**
     * Deletes a specific playback history entry.
     */
    fun deletePlaybackHistoryEntry(id: Long) {
        viewModelScope.launch {
            try {
                playlistRepository.deletePlaybackHistoryEntry(id)
                loadPlaybackHistory() // Refresh the list
            } catch (e: Exception) {
                _error.value = "Failed to delete history entry: ${e.message}"
            }
        }
    }
    
    /**
     * Clears all playback history.
     */
    fun clearPlaybackHistory() {
        viewModelScope.launch {
            _isLoadingHistory.value = true
            _error.value = null
            
            try {
                playlistRepository.clearPlaybackHistory()
                _playbackHistory.value = emptyList()
                historyBeforeId = null
                _hasMoreHistory.value = true
            } catch (e: Exception) {
                _error.value = "Failed to clear history: ${e.message}"
            } finally {
                _isLoadingHistory.value = false
            }
        }
    }
    
    /**
     * Finds the "Liked Songs" playlist (case-insensitive).
     */
    fun findLikedPlaylist(likedName: String): SavedPlaylist? {
        return _playlists.value.firstOrNull { it.name.equals(likedName, ignoreCase = true) }
    }
    
    /**
     * Clears the error message.
     */
    fun clearError() {
        _error.value = null
    }
}
