package io.github.ytdlpandroid

import android.content.Context
import android.net.Uri

/**
 * MediaStore exposes the same file through several collections that share one `_id`
 * (e.g. `content://media/external/downloads/42` and `content://media/external/audio/media/42`).
 * Downloads are published into the Downloads collection while the library scans the Audio
 * collection, so without normalisation every downloaded song appeared twice.
 */
object MediaUris {
    private val ALIAS_COLLECTIONS = setOf("downloads", "file")

    /** Audio-collection form of [raw] when it is a MediaStore alias, else [raw] unchanged. */
    fun canonicalAudio(raw: String): String {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return raw
        if (uri.scheme != "content" || uri.authority != "media") return raw
        val segments = uri.pathSegments
        if (segments.size != 3 || segments[1] !in ALIAS_COLLECTIONS) return raw
        val id = segments[2]
        if (id.isEmpty() || !id.all(Char::isDigit)) return raw
        return "content://media/${segments[0]}/audio/media/$id"
    }

    fun canonicalAudio(uri: Uri): Uri {
        val raw = uri.toString()
        val canonical = canonicalAudio(raw)
        return if (canonical == raw) uri else Uri.parse(canonical)
    }

    /**
     * Like [canonicalAudio] but only rewrites aliases that MediaStore reports as audio, so
     * video downloads keep their Downloads URI. Deleted items (null type) are left alone.
     */
    fun canonicalAudio(context: Context, raw: String): String {
        val canonical = canonicalAudio(raw)
        if (canonical == raw) return raw
        // Ask the audio collection whether it contains this id (deleted files return false).
        val id = Uri.parse(canonical).lastPathSegment ?: return raw
        val volume = Uri.parse(canonical).pathSegments.firstOrNull() ?: return raw
        return if (isAudioId(context, volume, id)) canonical else raw
    }

    private fun isAudioId(context: Context, volume: String, id: String): Boolean = runCatching {
        context.contentResolver.query(
            android.provider.MediaStore.Audio.Media.getContentUri(volume),
            arrayOf(android.provider.MediaStore.Audio.Media._ID),
            "${android.provider.MediaStore.Audio.Media._ID} = ?",
            arrayOf(id),
            null,
        )?.use { cursor -> cursor.moveToFirst() } ?: false
    }.getOrDefault(false)
}
