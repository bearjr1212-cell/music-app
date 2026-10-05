package io.github.ytdlpandroid.repository

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.ytdlpandroid.AudioTrackimport kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data class representing extracted track metadata.
 */
data class TrackMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artwork: Bitmap?,
)

/**
 * Repository for managing audio track data and metadata.
 * Handles loading tracks from MediaStore and extracting metadata.
 */
@Singleton
class TrackRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    
    /**
     * Loads all audio tracks from the device's MediaStore.
     * This operation runs on IO dispatcher to avoid blocking the main thread.
     */
    suspend fun loadLibraryTracks(): List<AudioTrack> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<AudioTrack>()
        val projection = arrayOf(
            android.provider.MediaStore.Audio.Media._ID,
            android.provider.MediaStore.Audio.Media.TITLE,
            android.provider.MediaStore.Audio.Media.ARTIST,
            android.provider.MediaStore.Audio.Media.ALBUM,
            android.provider.MediaStore.Audio.Media.DURATION,
            android.provider.MediaStore.Audio.Media.DATE_ADDED,
        )
        
        val selection = "${android.provider.MediaStore.Audio.Media.IS_MUSIC} = 1"
        val sortOrder = "${android.provider.MediaStore.Audio.Media.DATE_ADDED} DESC"
        
        context.contentResolver.query(
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DURATION)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATE_ADDED)
            
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = Uri.withAppendedPath(
                    android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id.toString(),
                )
                
                tracks.add(
                    AudioTrack(
                        uri = uri,
                        title = cursor.getString(titleColumn) ?: "Unknown",
                        artist = cursor.getString(artistColumn) ?: "Unknown Artist",
                        album = cursor.getString(albumColumn) ?: "",
                        durationMs = cursor.getLong(durationColumn),
                        artwork = null, // Loaded separately
                        dateAdded = cursor.getLong(dateAddedColumn),
                    ),
                )
            }
        }
        
        tracks
    }
    
    /**
     * Extracts metadata from a media file including artwork.
     * This is a blocking operation and should be called with appropriate dispatcher.
     */
    suspend fun extractMetadata(uri: Uri): TrackMetadata? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        return@withContext try {
            retriever.setDataSource(context, uri)
            
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            
            val artwork = try {
                retriever.embeddedPicture?.let { data ->
                    android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                }
            } catch (e: Exception) {
                null
            }
            
            TrackMetadata(
                title = title ?: "Unknown",
                artist = artist ?: "Unknown Artist",
                album = album ?: "",
                durationMs = duration ?: 0L,
                artwork = artwork,
            )
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
    
    /**
     * Loads artwork for a track if not already cached.
     */
    suspend fun loadArtwork(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        return@withContext try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture?.let { data ->
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
            }
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}




