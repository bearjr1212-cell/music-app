package io.github.ytdlpandroid.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.ytdlpandroid.DownloadJob
import io.github.ytdlpandroid.DownloadService
import io.github.ytdlpandroid.repository.DownloadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import javax.inject.Inject

/**
 * ViewModel for managing download queue and settings.
 * Handles download job management, format selection, and queue operations.
 */
@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
) : ViewModel() {
    
    // Download queue state
    private val _downloadJobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val downloadJobs: StateFlow<List<DownloadJob>> = _downloadJobs.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    // Download settings
    private val _formatMode = MutableStateFlow("best")
    val formatMode: StateFlow<String> = _formatMode.asStateFlow()
    
    private val _allowPlaylist = MutableStateFlow(false)
    val allowPlaylist: StateFlow<Boolean> = _allowPlaylist.asStateFlow()
    
    private val _maxConcurrentDownloads = MutableStateFlow(3)
    val maxConcurrentDownloads: StateFlow<Int> = _maxConcurrentDownloads.asStateFlow()
    
    private val _wifiOnly = MutableStateFlow(false)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()
    
    // Service reference
    private var downloadService: DownloadService? = null
    
    /**
     * Sets the download service reference.
     */
    fun setDownloadService(service: DownloadService?) {
        downloadService = service
        service?.setListener(downloadListener)
        // Load initial settings
        _maxConcurrentDownloads.value = service?.getMaxConcurrentDownloads() ?: 3
        _wifiOnly.value = service?.getWifiOnly() ?: false
        loadJobs()
    }
    
    /**
     * Listener for download service updates.
     */
    private val downloadListener = object : DownloadService.Listener {
        override fun onDownloadState(
            message: String,
            active: Boolean,
            paused: Boolean,
            canPause: Boolean,
            canCancel: Boolean,
            percent: Int?,
        ) {
            // Update message could be handled here if needed
        }
        
        override fun onQueueChanged(jobs: List<DownloadJob>) {
            _downloadJobs.value = jobs
        }
    }
    
    /**
     * Loads all download jobs from the queue.
     */
    fun loadJobs() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            try {
                val jobs = downloadRepository.getJobs()
                _downloadJobs.value = jobs
            } catch (e: Exception) {
                _error.value = "Failed to load download jobs: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Enqueues URLs for download.
     */
    fun enqueueUrls(urls: List<String>) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            try {
                val urlsJson = JSONArray(urls).toString()
                val id = downloadRepository.enqueue(
                    urlsJson,
                    _formatMode.value,
                    _allowPlaylist.value,
                )
                
                // Resume queued jobs via service
                downloadService?.resumeQueuedJobs()
                loadJobs()
            } catch (e: Exception) {
                _error.value = "Failed to enqueue download: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Cancels a download job.
     */
    fun cancelJob(id: Long) {
        downloadService?.cancelJob(id)
    }
    
    /**
     * Retries a failed download job.
     */
    fun retryJob(id: Long) {
        downloadService?.retryJob(id)
    }
    
    /**
     * Resumes a paused download job.
     */
    fun resumeJob(id: Long) {
        downloadService?.resumeJob(id)
    }
    
    /**
     * Pauses a download job.
     */
    fun pauseJob(id: Long) {
        downloadService?.pauseJob(id)
    }
    
    /**
     * Moves a job to the front of the queue.
     */
    fun moveJobToFront(id: Long) {
        downloadService?.moveJobToFront(id)
    }
    
    /**
     * Clears all finished jobs from the queue.
     */
    fun clearFinished() {
        viewModelScope.launch {
            try {
                val removed = downloadService?.clearFinished() ?: return@launch
                if (removed > 0) {
                    loadJobs()
                }
            } catch (e: Exception) {
                _error.value = "Failed to clear finished jobs: ${e.message}"
            }
        }
    }
    
    /**
     * Sets the download format mode.
     */
    fun setFormatMode(mode: String) {
        _formatMode.value = mode
    }
    
    /**
     * Sets whether to download playlists.
     */
    fun setAllowPlaylist(allow: Boolean) {
        _allowPlaylist.value = allow
    }
    
    /**
     * Sets the maximum number of concurrent downloads.
     */
    fun setMaxConcurrentDownloads(max: Int) {
        val bounded = max.coerceIn(1, 10)
        _maxConcurrentDownloads.value = bounded
        downloadService?.setMaxConcurrentDownloads(bounded)
    }
    
    /**
     * Sets whether downloads should only occur on Wi-Fi.
     */
    fun setWifiOnly(wifiOnly: Boolean) {
        _wifiOnly.value = wifiOnly
        downloadService?.setWifiOnly(wifiOnly)
    }
    
    /**
     * Resumes all queued jobs.
     */
    fun resumeQueuedJobs() {
        downloadService?.resumeQueuedJobs()
    }
    
    /**
     * Gets the count of queued jobs.
     */
    suspend fun countQueued(): Int {
        return try {
            downloadRepository.countQueued()
        } catch (e: Exception) {
            _error.value = "Failed to count queued jobs: ${e.message}"
            0
        }
    }
    
    /**
     * Gets the count of paused jobs.
     */
    suspend fun countPaused(): Int {
        return try {
            downloadRepository.countPaused()
        } catch (e: Exception) {
            _error.value = "Failed to count paused jobs: ${e.message}"
            0
        }
    }
    
    /**
     * Clears the error message.
     */
    fun clearError() {
        _error.value = null
    }
    
    /**
     * Gets the format label for the current mode.
     */
    fun getFormatLabel(mode: String): String {
        return when (mode) {
            "best" -> "Best"
            "1080p" -> "1080p"
            "720p" -> "720p"
            "480p" -> "480p"
            "audio" -> "Audio"
            "audio_mp3" -> "Audio (MP3)"
            else -> "Best"
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        downloadService?.setListener(null)
        downloadService = null
    }
}
