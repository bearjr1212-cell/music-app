package io.github.ytdlpandroid.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.ytdlpandroid.DownloadJob
import io.github.ytdlpandroid.DownloadQueueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing download queue operations.
 * Wraps DownloadQueueStore with coroutine support for async operations.
 */
@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    
    private val queueStore by lazy { DownloadQueueStore(context) }
    
    /**
     * Enqueues a new download job.
     */
    suspend fun enqueue(urlsJson: String, format: String, allowPlaylist: Boolean): Long =
        withContext(Dispatchers.IO) {
            queueStore.enqueue(urlsJson, format, allowPlaylist)
        }
    
    /**
     * Claims the next queued job for processing.
     */
    suspend fun claimNextQueued(): DownloadJob? = withContext(Dispatchers.IO) {
        queueStore.claimNextQueued()
    }
    
    /**
     * Retrieves all download jobs.
     */
    suspend fun getJobs(): List<DownloadJob> = withContext(Dispatchers.IO) {
        queueStore.getJobs()
    }
    
    /**
     * Updates a job's state and message.
     */
    suspend fun updateJob(id: Long, state: String, message: String, percent: Int? = null) =
        withContext(Dispatchers.IO) {
            queueStore.update(id, state, message, percent)
        }
    
    /**
     * Updates a job's state, message, percent, and pause origin.
     */
    suspend fun updateJob(
        id: Long,
        state: String,
        message: String,
        percent: Int?,
        pauseOrigin: String?,
    ) = withContext(Dispatchers.IO) {
        queueStore.update(id, state, message, percent, pauseOrigin)
    }
    
    /**
     * Moves a job to the front of the queue.
     */
    suspend fun moveJobToFront(id: Long): Boolean = withContext(Dispatchers.IO) {
        queueStore.moveToFront(id)
    }
    
    /**
     * Clears all finished jobs from the queue.
     */
    suspend fun clearFinished(): Int = withContext(Dispatchers.IO) {
        queueStore.clearFinished()
    }
    
    /**
     * Cancels a queued or paused job.
     */
    suspend fun cancelJob(id: Long): Boolean = withContext(Dispatchers.IO) {
        queueStore.cancel(id)
    }
    
    /**
     * Retries a failed, partial, or canceled job.
     */
    suspend fun retryJob(id: Long): Boolean = withContext(Dispatchers.IO) {
        queueStore.retry(id)
    }
    
    /**
     * Resumes a paused job.
     */
    suspend fun resumeJob(id: Long): Boolean = withContext(Dispatchers.IO) {
        queueStore.resume(id)
    }
    
    /**
     * Resumes jobs that were paused due to Wi-Fi policy.
     */
    suspend fun resumeNetworkPaused(id: Long? = null): Int = withContext(Dispatchers.IO) {
        queueStore.resumeNetworkPaused(id)
    }
    
    /**
     * Recovers interrupted jobs by moving them back to queued state.
     */
    suspend fun recoverInterrupted(): Int = withContext(Dispatchers.IO) {
        queueStore.recoverInterrupted()
    }
    
    /**
     * Checks if there are any queued jobs.
     */
    suspend fun hasQueued(): Boolean = withContext(Dispatchers.IO) {
        queueStore.hasQueued()
    }
    
    /**
     * Checks if there are any jobs paused due to Wi-Fi policy.
     */
    suspend fun hasNetworkPaused(): Boolean = withContext(Dispatchers.IO) {
        queueStore.hasNetworkPaused()
    }
    
    /**
     * Counts the number of queued jobs.
     */
    suspend fun countQueued(): Int = withContext(Dispatchers.IO) {
        queueStore.countQueued()
    }
    
    /**
     * Counts the number of paused jobs.
     */
    suspend fun countPaused(): Int = withContext(Dispatchers.IO) {
        queueStore.countPaused()
    }
}
