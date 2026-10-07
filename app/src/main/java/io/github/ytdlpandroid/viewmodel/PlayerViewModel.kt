package io.github.ytdlpandroid.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.ytdlpandroid.AudioTrack
import io.github.ytdlpandroid.PlaybackSnapshot
import io.github.ytdlpandroid.repository.PlaylistRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the player screen.
 *
 * Design — push model:
 *   MainActivity owns the PlaybackService binding. Whenever the service fires
 *   onPlaybackChanged, MainActivity calls [updatePlaybackSnapshot] to push the
 *   new snapshot here. The ViewModel holds all observable state and exposes
 *   intent methods (play, pause, seekTo, …) that MainActivity forwards to the
 *   service. This keeps the ViewModel free of any direct service reference and
 *   survives configuration changes without leaking the binder.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    // -------------------------------------------------------------------------
    // Playback snapshot (pushed by MainActivity from the service listener)
    // -------------------------------------------------------------------------

    private val _playbackSnapshot = MutableStateFlow<PlaybackSnapshot?>(null)
    val playbackSnapshot: StateFlow<PlaybackSnapshot?> = _playbackSnapshot.asStateFlow()

    // -------------------------------------------------------------------------
    // Derived — now-playing track
    // -------------------------------------------------------------------------

    private val _nowPlayingTrack = MutableStateFlow<AudioTrack?>(null)
    val nowPlayingTrack: StateFlow<AudioTrack?> = _nowPlayingTrack.asStateFlow()

    // -------------------------------------------------------------------------
    // Derived — current queue
    // Queue items are not carried in PlaybackSnapshot (it only has index +
    // queueSize). MainActivity must push the full list via [updateQueue] after
    // playQueue() and after any reorder / removal.
    // -------------------------------------------------------------------------

    private val _currentQueue = MutableStateFlow<List<AudioTrack>>(emptyList())
    val currentQueue: StateFlow<List<AudioTrack>> = _currentQueue.asStateFlow()

    // -------------------------------------------------------------------------
    // Pending playback-control intents
    // Each field stores the last-requested value so MainActivity can read and
    // forward it to PlaybackService. See [PlaybackIntent].
    // -------------------------------------------------------------------------

    private val _pendingIntent = MutableStateFlow<PlaybackIntent?>(null)
    val pendingIntent: StateFlow<PlaybackIntent?> = _pendingIntent.asStateFlow()

    // -------------------------------------------------------------------------
    // Sleep timer — coroutine countdown inside the ViewModel (ms precision)
    // -------------------------------------------------------------------------

    private val _sleepTimerRemaining = MutableStateFlow(0L)
    val sleepTimerRemaining: StateFlow<Long> = _sleepTimerRemaining.asStateFlow()

    private var sleepTimerJob: Job? = null
    private var sleepTimerDeadlineMs = 0L

    // -------------------------------------------------------------------------
    // Equalizer — local state, per-band
    // -------------------------------------------------------------------------

    private val _equalizerEnabled = MutableStateFlow(false)
    val equalizerEnabled: StateFlow<Boolean> = _equalizerEnabled.asStateFlow()

    // band index → level (Short)
    private val _bandLevels = MutableStateFlow<Map<Int, Short>>(emptyMap())
    val bandLevels: StateFlow<Map<Int, Short>> = _bandLevels.asStateFlow()

    // -------------------------------------------------------------------------
    // Error state
    // -------------------------------------------------------------------------

    private val _playerError = MutableStateFlow<String?>(null)
    val playerError: StateFlow<String?> = _playerError.asStateFlow()

    // =========================================================================
    // Public API — snapshot update (called by MainActivity)
    // =========================================================================

    /**
     * Called by MainActivity whenever PlaybackService fires onPlaybackChanged.
     * Updates all derived flows from the new snapshot.
     */
    fun updatePlaybackSnapshot(snapshot: PlaybackSnapshot) {
        _playbackSnapshot.value = snapshot

        // Derive now-playing track from PlaybackTrack → AudioTrack
        _nowPlayingTrack.value = snapshot.track?.let { pt ->
            AudioTrack(
                uri = Uri.parse(pt.uri),
                title = pt.title,
                artist = pt.artist,
                album = pt.album,
                durationMs = pt.durationMs,
                artwork = pt.artwork,
            )
        }

        // Surface service-side error messages as player errors
        snapshot.message?.let { msg ->
            _playerError.value = msg
        }
    }

    /**
     * Called by MainActivity to push the full queue after playQueue() or after
     * any queue mutation that the service has accepted.
     */
    fun updateQueue(tracks: List<AudioTrack>) {
        _currentQueue.value = tracks.toList()
    }

    // =========================================================================
    // Playback-control intents (MainActivity reads and forwards to service)
    // =========================================================================

    /** Request playback to resume / start. */
    fun play() {
        _pendingIntent.value = PlaybackIntent.Play
    }

    /** Request playback to pause. */
    fun pause() {
        _pendingIntent.value = PlaybackIntent.Pause
    }

    /** Request a seek to [positionMs]. */
    fun seekTo(positionMs: Long) {
        _pendingIntent.value = PlaybackIntent.SeekTo(positionMs)
    }

    /** Request skip to the next track. */
    fun skipNext() {
        _pendingIntent.value = PlaybackIntent.SkipNext
    }

    /** Request skip to the previous track. */
    fun skipPrevious() {
        _pendingIntent.value = PlaybackIntent.SkipPrevious
    }

    /** Request shuffle state change. */
    fun setShuffleEnabled(enabled: Boolean) {
        _pendingIntent.value = PlaybackIntent.SetShuffle(enabled)
    }

    /** Request repeat-mode change (0 = off, 1 = all, 2 = one). */
    fun setRepeatMode(mode: Int) {
        _pendingIntent.value = PlaybackIntent.SetRepeatMode(mode)
    }

    /** Request playback-speed change. */
    fun setPlaybackSpeed(speed: Float) {
        _pendingIntent.value = PlaybackIntent.SetSpeed(speed)
    }

    /**
     * Acknowledges the last pending intent. MainActivity calls this after it
     * has forwarded the intent to PlaybackService so it is not replayed.
     */
    fun clearPendingIntent() {
        _pendingIntent.value = null
    }

    // =========================================================================
    // Sleep timer — coroutine-driven countdown (ViewModel-owned)
    // =========================================================================

    /**
     * Starts a sleep-timer countdown of [durationMs] milliseconds.
     * When it reaches zero the ViewModel emits [PlaybackIntent.Pause] so
     * MainActivity can pause playback.
     */
    fun setSleepTimer(durationMs: Long) {
        cancelSleepTimer()
        if (durationMs <= 0L) return

        sleepTimerDeadlineMs = System.currentTimeMillis() + durationMs
        _sleepTimerRemaining.value = durationMs

        sleepTimerJob = viewModelScope.launch {
            while (true) {
                val remaining = sleepTimerDeadlineMs - System.currentTimeMillis()
                if (remaining <= 0L) {
                    _sleepTimerRemaining.value = 0L
                    _pendingIntent.value = PlaybackIntent.Pause
                    break
                }
                _sleepTimerRemaining.value = remaining
                delay(SLEEP_TIMER_TICK_MS)
            }
        }
    }

    /** Cancels any running sleep-timer countdown. */
    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerDeadlineMs = 0L
        _sleepTimerRemaining.value = 0L
    }

    // =========================================================================
    // Equalizer — local state; MainActivity applies changes to AudioEffect
    // =========================================================================

    /**
     * Enables or disables the equalizer. Stored locally; MainActivity is
     * expected to observe [equalizerEnabled] and toggle the AudioEffect.
     */
    fun setEqualizerEnabled(enabled: Boolean) {
        _equalizerEnabled.value = enabled
    }

    /**
     * Sets the level for a single equalizer band. Stored locally; MainActivity
     * observes [bandLevels] and applies changes to the AudioEffect.
     */
    fun setBandLevel(band: Int, level: Short) {
        _bandLevels.value = _bandLevels.value + (band to level)
    }

    // =========================================================================
    // Queue mutations — intent-based, forwarded by MainActivity
    // =========================================================================

    /**
     * Requests moving the queue item at [from] to position [to].
     */
    fun moveQueueItem(from: Int, to: Int) {
        _pendingIntent.value = PlaybackIntent.MoveQueueItem(from, to)
    }

    /**
     * Requests removing the queue item at [index].
     */
    fun removeFromQueue(index: Int) {
        _pendingIntent.value = PlaybackIntent.RemoveFromQueue(index)
    }

    // =========================================================================
    // Playback history
    // =========================================================================

    /**
     * Persists [track] in the playback history via [PlaylistRepository].
     * Errors are surfaced through [playerError].
     */
    fun recordPlaybackHistory(track: AudioTrack) {
        viewModelScope.launch {
            try {
                playlistRepository.recordPlayback(
                    trackUri = track.uri.toString(),
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                )
            } catch (e: Exception) {
                _playerError.value = "Failed to save playback history: ${e.message}"
            }
        }
    }

    // =========================================================================
    // Error handling
    // =========================================================================

    /** Clears the current player error. */
    fun clearError() {
        _playerError.value = null
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onCleared() {
        super.onCleared()
        cancelSleepTimer()
    }

    // =========================================================================
    // Companion
    // =========================================================================

    private companion object {
        private const val SLEEP_TIMER_TICK_MS = 500L
    }
}

/**
 * Sealed hierarchy of playback-control intents the ViewModel exposes.
 * MainActivity observes [PlayerViewModel.pendingIntent], executes the
 * matching PlaybackService call, then calls [PlayerViewModel.clearPendingIntent].
 */
sealed interface PlaybackIntent {
    data object Play : PlaybackIntent
    data object Pause : PlaybackIntent
    data class SeekTo(val positionMs: Long) : PlaybackIntent
    data object SkipNext : PlaybackIntent
    data object SkipPrevious : PlaybackIntent
    data class SetShuffle(val enabled: Boolean) : PlaybackIntent
    data class SetRepeatMode(val mode: Int) : PlaybackIntent
    data class SetSpeed(val speed: Float) : PlaybackIntent
    data class MoveQueueItem(val from: Int, val to: Int) : PlaybackIntent
    data class RemoveFromQueue(val index: Int) : PlaybackIntent
}
