package io.github.ytdlpandroid.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.ytdlpandroid.repository.PlaylistRepository
import io.github.ytdlpandroid.repository.TrackRepository
import io.github.ytdlpandroid.repository.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for managing the music library.
 * Handles loading, filtering, sorting, and grouping of audio tracks.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val trackRepository: TrackRepository,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {
    
    // Library state
    private val _libraryTracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val libraryTracks: StateFlow<List<AudioTrack>> = _libraryTracks.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    // Filter and sort state
    private val _groupMode = MutableStateFlow(0) // 0=none, 1=artist, 2=album
    val groupMode: StateFlow<Int> = _groupMode.asStateFlow()
    
    private val _sortMode = MutableStateFlow(0) // 0=default, 1=artist, 2=album, 3=date, 4=duration
    val sortMode: StateFlow<Int> = _sortMode.asStateFlow()
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    
    // Filtered results
    private val _filteredTracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val filteredTracks: StateFlow<List<AudioTrack>> = _filteredTracks.asStateFlow()
    
    private val _isEmpty = MutableStateFlow(true)
    val isEmpty: StateFlow<Boolean> = _isEmpty.asStateFlow()
    
    /**
     * Loads all audio tracks from the device's MediaStore.
     */
    fun loadLibrary() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            try {
                val tracks = trackRepository.loadLibraryTracks()
                _libraryTracks.value = tracks
                _isEmpty.value = tracks.isEmpty()
                applyFiltersAndSort()
            } catch (e: Exception) {
                _error.value = "Failed to load library: ${e.message}"
                _isEmpty.value = true
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Sets the group mode for library display.
     */
    fun setGroupMode(mode: Int) {
        _groupMode.value = mode
        applyFiltersAndSort()
    }
    
    /**
     * Sets the sort mode for library display.
     */
    fun setSortMode(mode: Int) {
        _sortMode.value = mode
        applyFiltersAndSort()
    }
    
    /**
     * Sets the search query for filtering the library.
     */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        applyFiltersAndSort()
    }
    
    /**
     * Applies current filters and sort mode to the library tracks.
     */
    private fun applyFiltersAndSort() {
        val query = _searchQuery.value.trim()
        val sortMode = _sortMode.value
        
        // Filter by search query
        val filtered = if (query.isEmpty()) {
            _libraryTracks.value
        } else {
            _libraryTracks.value.filter { track ->
                track.title.contains(query, ignoreCase = true) ||
                    track.artist.contains(query, ignoreCase = true) ||
                    track.album.contains(query, ignoreCase = true)
            }
        }
        
        // Sort
        val sorted = when (sortMode) {
            1 -> filtered.sortedBy { it.artist.lowercase() }
            2 -> filtered.sortedBy { it.album.lowercase() }
            3 -> filtered.sortedByDescending { it.dateAdded }
            4 -> filtered.sortedByDescending { it.durationMs }
            else -> filtered
        }
        
        _filteredTracks.value = sorted
    }
    
    /**
     * Groups tracks by the specified key function.
     */
    fun groupTracksBy(key: (AudioTrack) -> String): Map<String, List<AudioTrack>> {
        return _filteredTracks.value.groupBy(key)
    }
    
    /**
     * Gets track metadata for a specific URI.
     */
    suspend fun getTrackMetadata(uri: String) = playlistRepository.getTrackMetadata(uri)
    
    /**
     * Saves track metadata.
     */
    suspend fun saveTrackMetadata(
        trackUri: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long?,
        artworkPath: String?,
    ) = playlistRepository.saveTrackMetadata(trackUri, title, artist, album, durationMs, artworkPath)
    
    /**
     * Extracts metadata from a media file.
     */
    suspend fun extractMetadata(uri: Uri) = trackRepository.extractMetadata(uri)
    
    /**
     * Loads artwork for a track.
     */
    suspend fun loadArtwork(uri: Uri) = trackRepository.loadArtwork(uri)
    
    /**
     * Clears the error message.
     */
    fun clearError() {
        _error.value = null
    }
    
    /**
     * Gets the group label for the current group mode.
     */
    fun getGroupLabel(mode: Int): String {
        return when (mode) {
            1 -> "Artist"
            2 -> "Album"
            else -> "None"
        }
    }
    
    /**
     * Gets the sort label for the current sort mode.
     */
    fun getSortLabel(mode: Int): String {
        return when (mode) {
            1 -> "Artist"
            2 -> "Album"
            3 -> "Date Added"
            4 -> "Duration"
            else -> "Default"
        }
    }
}
