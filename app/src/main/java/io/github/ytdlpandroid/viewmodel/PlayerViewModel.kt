package io.github.ytdlpandroid.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.ytdlpandroid.PlaybackService
import io.github.ytdlpandroid.PlaybackSnapshot
import io.github.ytdlpandroid.PlaybackTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for managing audio playback.
 * Handles playback controls, shuffle, repeat, speed, sleep timer, and equalizer.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor() : ViewModel() {
    
    // Playback state
    private val _playbackSnapshot = MutableStateFlow<PlaybackSnapshot?>(null)
    val playbackSnapshot: StateFlow<PlaybackSnapshot?> = _playbackSnapshot.asStateFlow()
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    
    private val _currentTrack = MutableStateFlow<PlaybackTrack?>(null)
    val currentTrack: StateFlow<PlaybackTrack?> = _currentTrack.asStateFlow()
    
    private val _position = MutableStateFlow(0)
    val position: StateFlow<Int> = _position.asStateFlow()
    
    private val _duration = MutableStateFlow(0)
    val duration: StateFlow<Int> = _duration.asStateFlow()
    
    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()
    
    private val _repeatMode = MutableStateFlow(0) // 0=off, 1=all, 2=one
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()
    
    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()
    
    private val _sleepMinutesRemaining = MutableStateFlow<Int?>(null)
    val sleepMinutesRemaining: StateFlow<Int?> = _sleepMinutesRemaining.asStateFlow()
    
    private val _equalizerEnabled = MutableStateFlow(false)
    val equalizerEnabled: StateFlow<Boolean> = _equalizerEnabled.asStateFlow()
    
    private val _equalizerLevel = MutableStateFlow(50)
    val equalizerLevel: StateFlow<Int> = _equalizerLevel.asStateFlow()
    
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    
    // Service reference
    private var playbackService: PlaybackService? = null
    
    /**
     * Sets the playback service reference.
     */
    fun setPlaybackService(service: PlaybackService?) {
        playbackService = service
        service?.setListener(playbackListener)
        // Get initial snapshot
        _playbackSnapshot.value = service?.getSnapshot()
        updateStateFromSnapshot()
    }
    
    /**
     * Listener for playback service updates.
     */
    private val playbackListener = object : PlaybackService.Listener {
        override fun onPlaybackChanged(snapshot: PlaybackSnapshot) {
            _playbackSnapshot.value = snapshot
            updateStateFromSnapshot()
        }
        
        override fun onPlaybackHistoryChanged() {
            // Handled by PlaylistViewModel
        }
        
        override fun onPlaybackHistoryError() {
            _message.value = "Failed to save playback history"
        }
    }
    
    /**
     * Updates state from the playback snapshot.
     */
    private fun updateStateFromSnapshot() {
        val snapshot = _playbackSnapshot.value ?: return
        
        _currentTrack.value = snapshot.track
        _isPlaying.value = snapshot.isPlaying
        _position.value = snapshot.positionMs
        _duration.value = snapshot.durationMs
        _shuffle.value = snapshot.shuffle
        _repeatMode.value = snapshot.repeatMode
        _playbackSpeed.value = snapshot.speed
        _sleepMinutesRemaining.value = snapshot.sleepMinutesRemaining
        _equalizerEnabled.value = snapshot.equalizerEnabled
        _equalizerLevel.value = snapshot.equalizerLevel
        _message.value = snapshot.message
    }
    
    /**
     * Plays a queue of tracks starting at the specified index.
     */
    fun playQueue(tracks: List<PlaybackTrack>, index: Int) {
        playbackService?.playQueue(tracks, index)
    }
    
    /**
     * Toggles playback between play and pause.
     */
    fun togglePlayback() {
        playbackService?.togglePlayback()
    }
    
    /**
     * Plays the previous track.
     */
    fun playPrevious() {
        playbackService?.playPrevious()
    }
    
    /**
     * Plays the next track.
     */
    fun playNext() {
        playbackService?.playNext()
    }
    
    /**
     * Seeks to the specified position in milliseconds.
     */
    fun seekTo(positionMs: Int) {
        playbackService?.seekTo(positionMs)
    }
    
    /**
     * Sets shuffle mode.
     */
    fun setShuffle(enabled: Boolean) {
        playbackService?.setShuffle(enabled)
    }
    
    /**
     * Sets repeat mode (0=off, 1=all, 2=one).
     */
    fun setRepeatMode(mode: Int) {
        playbackService?.setRepeatMode(mode)
    }
    
    /**
     * Sets playback speed.
     */
    fun setPlaybackSpeed(speed: Float) {
        playbackService?.setPlaybackSpeed(speed)
    }
    
    /**
     * Sets the sleep timer in minutes, or null to cancel.
     */
    fun setSleepTimerMinutes(minutes: Int?) {
        playbackService?.setSleepTimerMinutes(minutes)
    }
    
    /**
     * Enables or disables the equalizer.
     */
    fun setEqualizerEnabled(enabled: Boolean) {
        playbackService?.setEqualizerEnabled(enabled)
    }
    
    /**
     * Sets the equalizer level (0-100).
     */
    fun setEqualizerLevel(level: Int) {
        playbackService?.setEqualizerLevel(level)
    }
    
    /**
     * Clears the message.
     */
    fun clearMessage() {
        _message.value = null
    }
    
    /**
     * Gets the repeat mode label.
     */
    fun getRepeatModeLabel(mode: Int): String {
        return when (mode) {
            0 -> "Off"
            1 -> "All"
            2 -> "One"
            else -> "Off"
        }
    }
    
    /**
     * Gets the sleep timer label.
     */
    fun getSleepTimerLabel(minutes: Int?): String {
        return if (minutes == null) "Off" else "${minutes}m"
    }
    
    override fun onCleared() {
        super.onCleared()
        playbackService?.setListener(null)
        playbackService = null
    }
}
