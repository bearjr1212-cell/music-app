package io.github.ytdlpandroid.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.ytdlpandroid.AppConfig
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
 * ViewModel for managing the download queue.
 * Holds observable download state and delegates data operations to DownloadRepository.
 * MainActivity remains the DownloadService binding point and pushes state in via setDownloadService / updateJobs.
 */
@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
) : ViewModel() {

    // Allowed format keys — mirrors the array in MainActivity
    private val allowedFormats = setOf("best", "1080p", "720p", "480p", "audio", "audio_mp3")

    // ── Download jobs ─────────────────────────────────────────────────────────

    private val _downloadJobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val downloadJobs: StateFlow<List<DownloadJob>> = _downloadJobs.asStateFlow()

    // ── Loading / error ───────────────────────────────────────────────────────

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

    // ── Concurrency setting ───────────────────────────────────────────────────

    private val _concurrentDownloads = MutableStateFlow(AppConfig.DEFAULT_MAX_CONCURRENT_DOWNLOADS)
    val concurrentDownloads: StateFlow<Int> = _concurrentDownloads.asStateFlow()

    // ── Wi-Fi only ────────────────────────────────────────────────────────────

    private val _wifiOnly = MutableStateFlow(false)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    // ── Selected format ───────────────────────────────────────────────────────

    private val _selectedFormat = MutableStateFlow("best")
    val selectedFormat: StateFlow<String> = _selectedFormat.asStateFlow()

    // ── DownloadService reference (set by MainActivity) ───────────────────────

    private var downloadService: DownloadService? = null

    /**
     * Sets the DownloadService reference so the ViewModel can delegate service-only
     * operations (pause, wifiOnly, concurrency) without binding directly.
     * Call from MainActivity's ServiceConnection callbacks.
     */
    fun setDownloadService(service: DownloadService?) {
        downloadService = service
        if (service != null) {
            // Sync observable state from the service's persisted values on connect
            _concurrentDownloads.value = service.getMaxConcurrentDownloads()
            _wifiOnly.value = service.getWifiOnly()
        }
    }

    // ── Jobs list sync ────────────────────────────────────────────────────────

    /**
     * Pushes a fresh job list received from service callbacks into observable state.
     * MainActivity calls this whenever the service reports a queue change.
     */
    fun updateJobs(jobs: List<DownloadJob>) {
        _downloadJobs.value = jobs
    }

    /**
     * Reloads the job list from DownloadRepository on IO.
     */
    fun refreshJobs() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _downloadJobs.value = downloadRepository.getJobs()
            } catch (e: Exception) {
                _downloadError.value = e.message ?: "Failed to load jobs"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── Enqueue ───────────────────────────────────────────────────────────────

    /**
     * Validates [urls] then enqueues them via DownloadRepository.
     *
     * Validation rules:
     *  - List must not be empty
     *  - At most [AppConfig.MAX_URL_COUNT] URLs (after deduplication by lowercase)
     *  - Every URL must start with "http://" or "https://"
     *
     * @return [Result.success] with the new job id, or [Result.failure] with a descriptive error.
     */
    suspend fun enqueueUrls(
        urls: List<String>,
        formatMode: String = _selectedFormat.value,
        allowPlaylist: Boolean = false,
    ): Result<Long> {
        val deduped = urls.distinctBy { it.lowercase() }

        if (deduped.isEmpty()) {
            return Result.failure(IllegalArgumentException("No URLs provided"))
        }
        if (deduped.size > AppConfig.MAX_URL_COUNT) {
            return Result.failure(
                IllegalArgumentException(
                    "Too many URLs: ${deduped.size} exceeds the limit of ${AppConfig.MAX_URL_COUNT}",
                ),
            )
        }
        val invalid = deduped.filterNot { it.startsWith("http://") || it.startsWith("https://") }
        if (invalid.isNotEmpty()) {
            return Result.failure(
                IllegalArgumentException(
                    "Invalid URL(s): ${invalid.take(3).joinToString()}" +
                        if (invalid.size > 3) " … and ${invalid.size - 3} more" else "",
                ),
            )
        }

        return try {
            val jobId = downloadRepository.enqueue(
                urlsJson = JSONArray(deduped).toString(),
                format = formatMode,
                allowPlaylist = allowPlaylist,
            )
            Result.success(jobId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Job controls ──────────────────────────────────────────────────────────

    /**
     * Pauses a running job. Delegates to DownloadService (no repository method for pause).
     * No-ops silently when the service is not yet connected.
     */
    fun pauseJob(jobId: Long) {
        downloadService?.pauseJob(jobId) ?: run {
            _downloadError.value = "Download service unavailable"
        }
    }

    /**
     * Resumes a paused job via DownloadRepository.
     */
    fun resumeJob(jobId: Long) {
        viewModelScope.launch {
            try {
                downloadRepository.resumeJob(jobId)
            } catch (e: Exception) {
                _downloadError.value = e.message ?: "Failed to resume job"
            }
        }
    }

    /**
     * Cancels a queued or paused job via DownloadRepository.
     */
    fun cancelJob(jobId: Long) {
        viewModelScope.launch {
            try {
                downloadRepository.cancelJob(jobId)
            } catch (e: Exception) {
                _downloadError.value = e.message ?: "Failed to cancel job"
            }
        }
    }

    /**
     * Retries a failed, partial, or canceled job via DownloadRepository.
     */
    fun retryJob(jobId: Long) {
        viewModelScope.launch {
            try {
                downloadRepository.retryJob(jobId)
            } catch (e: Exception) {
                _downloadError.value = e.message ?: "Failed to retry job"
            }
        }
    }

    // ── Concurrency setting ───────────────────────────────────────────────────

    /**
     * Sets the maximum concurrent downloads (clamped to 1–[AppConfig.MAX_CONCURRENT_DOWNLOADS]).
     * Persisted via DownloadService, which owns the preference.
     */
    fun setConcurrentDownloads(n: Int) {
        val clamped = n.coerceIn(1, AppConfig.MAX_CONCURRENT_DOWNLOADS)
        _concurrentDownloads.value = clamped
        downloadService?.setMaxConcurrentDownloads(clamped) ?: run {
            _downloadError.value = "Download service unavailable"
        }
    }

    // ── Wi-Fi only ────────────────────────────────────────────────────────────

    /**
     * Enables or disables the Wi-Fi-only download policy.
     * Persisted via DownloadService, which owns the preference.
     */
    fun setWifiOnly(enabled: Boolean) {
        _wifiOnly.value = enabled
        downloadService?.setWifiOnly(enabled) ?: run {
            _downloadError.value = "Download service unavailable"
        }
    }

    // ── Format selection ──────────────────────────────────────────────────────

    /**
     * Sets the active download format.
     * Silently ignores values not in [allowedFormats].
     */
    fun setFormat(format: String) {
        if (format !in allowedFormats) {
            _downloadError.value = "Unknown format: $format"
            return
        }
        _selectedFormat.value = format
    }

    // ── History ───────────────────────────────────────────────────────────────

    /**
     * Clears all completed, failed, partial, and canceled jobs via DownloadRepository.
     */
    fun clearCompletedJobs() {
        viewModelScope.launch {
            try {
                downloadRepository.clearFinished()
                refreshJobs()
            } catch (e: Exception) {
                _downloadError.value = e.message ?: "Failed to clear jobs"
            }
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /**
     * Clears the current error message.
     */
    fun clearError() {
        _downloadError.value = null
    }

    override fun onCleared() {
        super.onCleared()
        downloadService = null
    }
}
