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
 *
 * Reads go through [DownloadRepository]; every operation that can start, stop, or reorder
 * work goes through the bound `DownloadService`, because only the service pumps the worker
 * pool and refreshes the foreground notification.
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
     * Sets the DownloadService reference so the ViewModel can delegate queue operations
     * without binding the service itself.
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
     * Validates [urls] then hands them to `DownloadService`, which owns the enqueue intent
     * and the foreground-service start that wakes the worker pool.
     *
     * The repository is deliberately not used here: `DownloadRepository.enqueue` writes the
     * row without starting a worker, so the job would sit queued until some unrelated event
     * pumped the queue.
     *
     * Validation rules:
     *  - List must not be empty
     *  - At most [AppConfig.MAX_URL_COUNT] URLs (after deduplication by lowercase)
     *  - Every URL must start with "http://" or "https://"
     *
     * @return [Result.success], or [Result.failure] with a descriptive error.
     */
    fun enqueueUrls(
        urls: List<String>,
        formatMode: String = _selectedFormat.value,
        allowPlaylist: Boolean = false,
    ): Result<Unit> {
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

        val service = downloadService ?: return Result.failure(serviceUnavailable())

        return try {
            service.enqueueUrls(
                urlsJson = JSONArray(deduped).toString(),
                format = formatMode,
                allowPlaylist = allowPlaylist,
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Job controls ──────────────────────────────────────────────────────────
    //
    // Every control below delegates to the bound DownloadService rather than the
    // repository. The service versions also pump the worker queue and refresh the
    // foreground notification; the repository versions only write the row, which
    // would leave a resumed or retried job stranded until the next unrelated event.

    /**
     * Pauses a running job.
     */
    fun pauseJob(jobId: Long) {
        requireService()?.pauseJob(jobId)
    }

    /**
     * Resumes a paused job.
     */
    fun resumeJob(jobId: Long) {
        requireService()?.resumeJob(jobId)
    }

    /**
     * Cancels a queued, running, or paused job.
     */
    fun cancelJob(jobId: Long) {
        requireService()?.cancelJob(jobId)
    }

    /**
     * Retries a failed, partial, or canceled job.
     */
    fun retryJob(jobId: Long) {
        requireService()?.retryJob(jobId)
    }

    /**
     * Moves a queued job to the front of the queue.
     *
     * @return `false` when the service is not connected or the store refuses the move
     *   (only queued jobs are eligible); the caller owns the user-facing message.
     */
    fun moveJobToFront(jobId: Long): Boolean =
        requireService()?.moveJobToFront(jobId) ?: false

    /**
     * Clears all completed, failed, partial, and canceled jobs.
     *
     * @return the number of jobs removed, or `-1` when the service is not connected.
     */
    fun clearFinishedJobs(): Int =
        requireService()?.clearFinishedJobs() ?: -1

    private fun requireService(): DownloadService? =
        downloadService ?: run {
            _downloadError.value = SERVICE_UNAVAILABLE
            null
        }

    private fun serviceUnavailable() = IllegalStateException(SERVICE_UNAVAILABLE)

    // ── Concurrency setting ───────────────────────────────────────────────────

    /**
     * Sets the maximum concurrent downloads (clamped to 1–[AppConfig.MAX_CONCURRENT_DOWNLOADS]).
     * Persisted via DownloadService, which owns the preference.
     */
    fun setConcurrentDownloads(n: Int) {
        val clamped = n.coerceIn(1, AppConfig.MAX_CONCURRENT_DOWNLOADS)
        _concurrentDownloads.value = clamped
        requireService()?.setMaxConcurrentDownloads(clamped)
    }

    // ── Wi-Fi only ────────────────────────────────────────────────────────────

    /**
     * Enables or disables the Wi-Fi-only download policy.
     * Persisted via DownloadService, which owns the preference.
     */
    fun setWifiOnly(enabled: Boolean) {
        _wifiOnly.value = enabled
        requireService()?.setWifiOnly(enabled)
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

    private companion object {
        const val SERVICE_UNAVAILABLE = "Download service unavailable"
    }
}
