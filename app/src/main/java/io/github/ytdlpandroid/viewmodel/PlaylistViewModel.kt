package io.github.ytdlpandroid.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.ytdlpandroid.SavedPlaylist
import io.github.ytdlpandroid.repository.PlaylistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for managing playlists, liked songs, and per-playlist track operations.
 * All data access is delegated to PlaylistRepository on Dispatchers.IO (via the repository).
 */
@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    companion object {
        /** Canonical name used to identify the Liked Songs playlist. */
        const val LIKED_SONGS_NAME = "Liked Songs"
    }

    // --- Playlist list ---

    private val _playlists = MutableStateFlow<List<SavedPlaylist>>(emptyList())
    val playlists: StateFlow<List<SavedPlaylist>> = _playlists.asStateFlow()

    // --- Liked Songs (derived: first playlist named "Liked Songs", case-insensitive) ---

    private val _likedPlaylist = MutableStateFlow<SavedPlaylist?>(null)
    val likedPlaylist: StateFlow<SavedPlaylist?> = _likedPlaylist.asStateFlow()

    // --- Selected playlist (for the detail view) ---

    private val _selectedPlaylist = MutableStateFlow<SavedPlaylist?>(null)
    val selectedPlaylist: StateFlow<SavedPlaylist?> = _selectedPlaylist.asStateFlow()

    // --- Loading / error state ---

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _playlistError = MutableStateFlow<String?>(null)
    val playlistError: StateFlow<String?> = _playlistError.asStateFlow()

    init {
        refreshPlaylists()
    }

    // ---------------------------------------------------------------------------
    // Read
    // ---------------------------------------------------------------------------

    /**
     * Reloads all playlists from the repository and updates derived state.
     */
    fun refreshPlaylists() {
        viewModelScope.launch {
            _isLoading.value = true
            _playlistError.value = null
            try {
                val loaded = playlistRepository.getPlaylists()
                _playlists.value = loaded
                _likedPlaylist.value = loaded.firstOrNull {
                    it.name.equals(LIKED_SONGS_NAME, ignoreCase = true)
                }
                // Keep selectedPlaylist in sync with refreshed data so callers see updated tracks.
                val current = _selectedPlaylist.value
                if (current != null) {
                    _selectedPlaylist.value = loaded.firstOrNull { it.id == current.id }
                }
            } catch (e: Exception) {
                _playlistError.value = e.message ?: "Failed to load playlists"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Selection
    // ---------------------------------------------------------------------------

    /**
     * Sets the currently selected playlist for the detail view.
     * Pass null to deselect.
     */
    fun selectPlaylist(playlist: SavedPlaylist?) {
        _selectedPlaylist.value = playlist
    }

    // ---------------------------------------------------------------------------
    // Create / Delete / Rename
    // ---------------------------------------------------------------------------

    /**
     * Creates a new playlist with the given name and refreshes the list.
     */
    fun createPlaylist(name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            _playlistError.value = "Playlist name cannot be empty"
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            _playlistError.value = null
            try {
                playlistRepository.createPlaylist(trimmedName)
                refreshPlaylists()
            } catch (e: Exception) {
                _playlistError.value = e.message ?: "Failed to create playlist"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Deletes a playlist and refreshes the list.
     */
    fun deletePlaylist(playlist: SavedPlaylist) {
        viewModelScope.launch {
            _isLoading.value = true
            _playlistError.value = null
            try {
                playlistRepository.deletePlaylist(playlist.id)
                // Deselect if the deleted playlist was selected.
                if (_selectedPlaylist.value?.id == playlist.id) {
                    _selectedPlaylist.value = null
                }
                refreshPlaylists()
            } catch (e: Exception) {
                _playlistError.value = e.message ?: "Failed to delete playlist"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Renames a playlist to [newName].
     *
     * TODO: Implement once PlaylistRepository/PlaylistStore exposes a renamePlaylist method.
     *       PlaylistStore currently has no UPDATE on the playlists table.
     */
    fun renamePlaylist(playlist: SavedPlaylist, newName: String) {
        val trimmedName = newName.trim()
        if (trimmedName.isEmpty()) {
            _playlistError.value = "Playlist name cannot be empty"
            return
        }
        // TODO: call playlistRepository.renamePlaylist(playlist.id, trimmedName) when available
        _playlistError.value = "Rename not yet supported by the repository"
    }

    // ---------------------------------------------------------------------------
    // Track operations
    // ---------------------------------------------------------------------------

    /**
     * Adds [trackUri] to [playlist] and refreshes the list.
     */
    fun addTrackToPlaylist(playlist: SavedPlaylist, trackUri: String) {
        viewModelScope.launch {
            _playlistError.value = null
            try {
                playlistRepository.addTrack(playlist.id, trackUri)
                refreshPlaylists()
            } catch (e: Exception) {
                _playlistError.value = e.message ?: "Failed to add track to playlist"
            }
        }
    }

    /**
     * Removes [trackUri] from [playlist] and refreshes the list.
     */
    fun removeTrackFromPlaylist(playlist: SavedPlaylist, trackUri: String) {
        viewModelScope.launch {
            _playlistError.value = null
            try {
                playlistRepository.removeTrack(playlist.id, trackUri)
                refreshPlaylists()
            } catch (e: Exception) {
                _playlistError.value = e.message ?: "Failed to remove track from playlist"
            }
        }
    }

    /**
     * Moves the track at [fromIndex] to [toIndex] within [playlist] and refreshes the list.
     */
    fun reorderTrack(playlist: SavedPlaylist, fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        viewModelScope.launch {
            _playlistError.value = null
            try {
                playlistRepository.moveTrack(playlist.id, fromIndex, toIndex)
                refreshPlaylists()
            } catch (e: Exception) {
                _playlistError.value = e.message ?: "Failed to reorder track"
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Toggle like
    // ---------------------------------------------------------------------------

    /**
     * Adds [trackUri] to the Liked Songs playlist, or removes it if already present.
     * If the Liked Songs playlist does not exist yet it is created automatically.
     */
    fun toggleLike(trackUri: String) {
        viewModelScope.launch {
            _playlistError.value = null
            try {
                val currentLiked = _likedPlaylist.value
                if (currentLiked != null && trackUri in currentLiked.tracks) {
                    // Already liked — remove.
                    playlistRepository.removeTrack(currentLiked.id, trackUri)
                } else {
                    // Not liked — ensure the playlist exists, then add.
                    val playlistId = currentLiked?.id
                        ?: playlistRepository.createPlaylist(LIKED_SONGS_NAME)
                    playlistRepository.addTrack(playlistId, trackUri)
                }
                refreshPlaylists()
            } catch (e: Exception) {
                _playlistError.value = e.message ?: "Failed to toggle like"
            }
        }
    }

    // ---------------------------------------------------------------------------
    // M3U import / export
    // ---------------------------------------------------------------------------

    /**
     * Imports a playlist from an M3U file at [uri].
     *
     * TODO: Implement once PlaylistRepository exposes M3U import support.
     *       Currently, M3U parsing lives in MainActivity.importPlaylistM3u() and relies on
     *       ContentResolver access that requires AndroidViewModel or a use-case layer.
     */
    fun importM3U(uri: Uri) {
        // TODO: parse the M3U file via ContentResolver, resolve tracks, and call
        //       playlistRepository.createPlaylist() + playlistRepository.addTrack() for each entry.
        _playlistError.value = "M3U import not yet implemented in the ViewModel layer"
    }

    /**
     * Exports [playlist] as an M3U file to [uri].
     *
     * TODO: Implement once PlaylistRepository exposes M3U export support or a use-case is added.
     *       Export requires resolving track metadata (ContentResolver / file path) and writing to
     *       a Uri via ContentResolver.openOutputStream(), which needs an Application context
     *       (AndroidViewModel) or a dedicated IO use-case.
     */
    fun exportM3U(playlist: SavedPlaylist, uri: Uri) {
        // TODO: resolve each playlist.tracks entry to a file path via PlaylistRepository
        //       .getTrackMetadata(), build "#EXTM3U\n#EXTINF:..." lines, and write to the Uri.
        _playlistError.value = "M3U export not yet implemented in the ViewModel layer"
    }

    // ---------------------------------------------------------------------------
    // Utility
    // ---------------------------------------------------------------------------

    /**
     * Clears the current error message.
     */
    fun clearError() {
        _playlistError.value = null
    }
}
