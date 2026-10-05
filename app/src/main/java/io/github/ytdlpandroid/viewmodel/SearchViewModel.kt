package io.github.ytdlpandroid.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.ytdlpandroid.DownloadJob
import io.github.ytdlpandroid.DownloadService
import io.github.ytdlpandroid.MusicSearchResult
import io.github.ytdlpandroid.repository.PlaylistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for managing music search functionality.
 * Handles search queries, results pagination, and download indicators.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {
    
    // Search state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    
    private val _searchResults = MutableStateFlow<List<MusicSearchResult>>(emptyList())
    val searchResults: StateFlow<List<MusicSearchResult>> = _searchResults.asStateFlow()
    
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()
    
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()
    
    private val _hasMoreResults = MutableStateFlow(false)
    val hasMoreResults: StateFlow<Boolean> = _hasMoreResults.asStateFlow()
    
    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()
    
    // Pagination state
    private var searchOffset = 0
    private var searchRequestId = 0
    private const val SEARCH_PAGE_SIZE = 20
    
    // Download state for search results
    private val _downloadJobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val downloadJobs: StateFlow<List<DownloadJob>> = _downloadJobs.asStateFlow()
    
    private var downloadService: DownloadService? = null
    
    /**
     * Sets the download service reference for queueing downloads.
     */
    fun setDownloadService(service: DownloadService?) {
        downloadService = service
    }
    
    /**
     * Updates the download jobs list from the service.
     */
    fun updateDownloadJobs(jobs: List<DownloadJob>) {
        _downloadJobs.value = jobs
    }
    
    /**
     * Performs a music search with the given query.
     */
    fun search(query: String) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            _searchError.value = "Search query required"
            return
        }
        
        _searchQuery.value = trimmedQuery
        searchOffset = 0
        searchRequestId++
        _searchResults.value = emptyList()
        _hasMoreResults.value = true
        _searchError.value = null
        
        loadNextPage(initial = true)
    }
    
    /**
     * Loads the next page of search results.
     */
    fun loadNextPage(initial: Boolean = false) {
        val service = downloadService
        if (service == null) {
            if (initial) {
                _searchError.value = "Search service unavailable"
                _hasMoreResults.value = false
            }
            return
        }
        
        val requestId = searchRequestId
        val offset = searchOffset
        
        if (initial) {
            _isSearching.value = true
        } else {
            _isLoadingMore.value = true
        }
        
        viewModelScope.launch {
            try {
                service.searchMusic(_searchQuery.value, offset, SEARCH_PAGE_SIZE) { result ->
                    if (requestId != searchRequestId) return@searchMusic
                    
                    if (initial) {
                        _isSearching.value = false
                    } else {
                        _isLoadingMore.value = false
                    }
                    
                    result.fold(
                        onSuccess = { items ->
                            if (initial) {
                                _searchResults.value = items
                            } else {
                                _searchResults.value = _searchResults.value + items
                            }
                            
                            searchOffset += items.size
                            _hasMoreResults.value = items.size == SEARCH_PAGE_SIZE
                            
                            if (items.isEmpty() && _searchResults.value.isEmpty()) {
                                _searchError.value = "No results found"
                            }
                        },
                        onFailure = { error ->
                            _searchError.value = error.message ?: "Search failed"
                            _hasMoreResults.value = false
                        },
                    )
                }
            } catch (e: Exception) {
                _isSearching.value = false
                _isLoadingMore.value = false
                _searchError.value = e.message ?: "Search failed"
            }
        }
    }
    
    /**
     * Clears the current search results.
     */
    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _searchError.value = null
        _hasMoreResults.value = false
        searchOffset = 0
        searchRequestId++
    }
    
    /**
     * Saves metadata for a search result track.
     */
    fun saveSearchResultMetadata(item: MusicSearchResult) {
        viewModelScope.launch {
            try {
                playlistRepository.saveTrackMetadata(
                    trackUri = item.url,
                    title = item.title,
                    artist = item.uploader,
                    album = "",
                    durationMs = item.durationSeconds?.times(1000L),
                    artworkPath = item.thumbnailUrl,
                )
            } catch (e: Exception) {
                _searchError.value = "Failed to save metadata: ${e.message}"
            }
        }
    }
    
    /**
     * Queues a download for a search result.
     */
    fun queueDownload(item: MusicSearchResult) {
        val service = downloadService ?: run {
            _searchError.value = "Download service unavailable"
            return
        }
        
        try {
            service.startServiceIntent(
                android.content.Intent(service, DownloadService::class.java).apply {
                    action = DownloadService.ACTION_ENQUEUE
                    putExtra(DownloadService.EXTRA_URLS, org.json.JSONArray(listOf(item.url)).toString())
                    putExtra(DownloadService.EXTRA_FORMAT, "best")
                    putExtra(DownloadService.EXTRA_PLAYLIST, false)
                },
            )
        } catch (e: Exception) {
            _searchError.value = "Failed to queue download: ${e.message}"
        }
    }
    
    /**
     * Resumes a paused download job.
     */
    fun resumeJob(jobId: Long) {
        downloadService?.resumeJob(jobId)
    }
    
    /**
     * Retries a failed download job.
     */
    fun retryJob(jobId: Long) {
        downloadService?.retryJob(jobId)
    }
    
    /**
     * Cancels a download job.
     */
    fun cancelJob(jobId: Long) {
        downloadService?.cancelJob(jobId)
    }
    
    /**
     * Clears the error message.
     */
    fun clearError() {
        _searchError.value = null
    }
    
    override fun onCleared() {
        super.onCleared()
        downloadService = null
    }
}
