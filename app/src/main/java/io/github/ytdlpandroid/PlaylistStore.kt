package io.github.ytdlpandroid

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SavedPlaylist(
    val id: Long,
    val name: String,
    val tracks: List<String>,
)

data class SavedTrackMetadata(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long?,
    val artworkPath: String?,
)

data class PlaybackHistoryEntry(
    val id: Long,
    val trackUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val playedAt: Long,
)

class PlaylistStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE playlists (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE playlist_tracks (
                playlist_id INTEGER NOT NULL,
                track_uri TEXT NOT NULL,
                added_at INTEGER NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY (playlist_id, track_uri),
                FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX playlist_tracks_order ON playlist_tracks(playlist_id, position, added_at, track_uri)")
        db.execSQL(
            """CREATE TABLE track_metadata (
                uri TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                duration_ms INTEGER,
                artwork_path TEXT
            )""".trimIndent(),
        )
        createPlaybackHistorySchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion <= newVersion) { "Playlist database downgrade is not supported" }
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE playlist_tracks ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
            val orderedMemberships = mutableListOf<Pair<Long, String>>()
            db.query(
                "playlist_tracks",
                arrayOf("playlist_id", "track_uri"),
                null,
                null,
                null,
                null,
                "playlist_id ASC, added_at ASC, rowid ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    orderedMemberships.add(cursor.getLong(0) to cursor.getString(1))
                }
            }
            val positions = mutableMapOf<Long, Long>()
            orderedMemberships.forEach { (playlistId, trackUri) ->
                val position = positions[playlistId] ?: 0L
                db.execSQL(
                    "UPDATE playlist_tracks SET position = ? WHERE playlist_id = ? AND track_uri = ?",
                    arrayOf<Any>(position, playlistId, trackUri),
                )
                positions[playlistId] = position + 1L
            }
            db.execSQL("DROP INDEX IF EXISTS playlist_tracks_order")
            db.execSQL("CREATE INDEX playlist_tracks_order ON playlist_tracks(playlist_id, position, added_at, track_uri)")
        }
        if (oldVersion < 3) createPlaybackHistorySchema(db)
        if (oldVersion < 1 || newVersion > 3) {
            error("No playlist database migration exists from version $oldVersion")
        }
    }

    private fun createPlaybackHistorySchema(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE playback_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                track_uri TEXT NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                played_at INTEGER NOT NULL
            )""".trimIndent(),
        )
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    @Synchronized
    fun getPlaylists(): List<SavedPlaylist> {
        // Single JOIN query instead of N+1 separate queries.
        val playlistOrder = linkedMapOf<Long, String>()   // id → name
        val trackMap = linkedMapOf<Long, MutableList<String>>() // id → [uri, …]

        readableDatabase.rawQuery(
            """
            SELECT p.id, p.name, pt.track_uri
            FROM playlists p
            LEFT JOIN playlist_tracks pt ON p.id = pt.playlist_id
            ORDER BY p.id ASC, pt.position ASC, pt.added_at ASC, pt.track_uri ASC
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1)
                if (!playlistOrder.containsKey(id)) {
                    playlistOrder[id] = name
                    trackMap[id] = mutableListOf()
                }
                if (!cursor.isNull(2)) {
                    trackMap[id]!!.add(cursor.getString(2))
                }
            }
        }

        return playlistOrder.map { (id, name) ->
            SavedPlaylist(id, name, trackMap[id] ?: emptyList())
        }
    }

    @Synchronized
    fun createPlaylist(name: String): Long {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "Playlist name cannot be empty" }
        return writableDatabase.insertOrThrow(
            "playlists",
            null,
            ContentValues().apply { put("name", normalizedName) },
        )
    }

    @Synchronized
    fun addTrack(playlistId: Long, trackUri: String): Boolean {
        if (trackUri.isBlank()) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val playlistExists = db.query(
                "playlists",
                arrayOf("id"),
                "id = ?",
                arrayOf(playlistId.toString()),
                null,
                null,
                null,
            ).use { it.moveToFirst() }
            if (!playlistExists) return false
            val nextPosition = db.rawQuery(
                "SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlist_id = ?",
                arrayOf(playlistId.toString()),
            ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
            val inserted = db.insertWithOnConflict(
                "playlist_tracks",
                null,
                ContentValues().apply {
                    put("playlist_id", playlistId)
                    put("track_uri", trackUri)
                    put("added_at", System.currentTimeMillis())
                    put("position", nextPosition)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            ) != -1L
            db.setTransactionSuccessful()
            return inserted
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun removeTrack(playlistId: Long, trackUri: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val removed = db.delete(
                "playlist_tracks",
                "playlist_id = ? AND track_uri = ?",
                arrayOf(playlistId.toString(), trackUri),
            ) > 0
            if (!removed) return false
            rewritePositions(db, playlistId, playlistTracks(db, playlistId))
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun moveTrack(playlistId: Long, fromIndex: Int, toIndex: Int): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val playlistExists = db.query(
                "playlists",
                arrayOf("id"),
                "id = ?",
                arrayOf(playlistId.toString()),
                null,
                null,
                null,
            ).use { it.moveToFirst() }
            if (!playlistExists) return false
            val tracks = playlistTracks(db, playlistId)
            if (fromIndex !in tracks.indices || toIndex !in tracks.indices) return false
            if (fromIndex != toIndex) {
                val moved = tracks.removeAt(fromIndex)
                tracks.add(toIndex, moved)
                rewritePositions(db, playlistId, tracks)
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun deletePlaylist(playlistId: Long): Boolean =
        writableDatabase.delete("playlists", "id = ?", arrayOf(playlistId.toString())) > 0

    @Synchronized
    fun getTrackMetadata(trackUri: String): SavedTrackMetadata? =
        readableDatabase.query(
            "track_metadata",
            arrayOf("uri", "title", "artist", "album", "duration_ms", "artwork_path"),
            "uri = ?",
            arrayOf(trackUri),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) null
            else cursor.toTrackMetadata()
        }

    @Synchronized
    fun getAllTrackMetadata(): List<SavedTrackMetadata> =
        readableDatabase.query(
            "track_metadata",
            arrayOf("uri", "title", "artist", "album", "duration_ms", "artwork_path"),
            null,
            null,
            null,
            null,
            "uri ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toTrackMetadata())
            }
        }

    @Synchronized
    fun saveTrackMetadata(
        trackUri: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long?,
        artworkPath: String?,
    ) {
        require(trackUri.isNotBlank()) { "Track URI cannot be empty" }
        val values = ContentValues().apply {
            put("uri", trackUri)
            put("title", title)
            put("artist", artist)
            put("album", album)
            if (durationMs == null) putNull("duration_ms") else put("duration_ms", durationMs)
            if (artworkPath == null) putNull("artwork_path") else put("artwork_path", artworkPath)
        }
        check(writableDatabase.insertWithOnConflict(
            "track_metadata",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        ) != -1L) { "Could not save track metadata" }
    }

    @Synchronized
    fun recordPlayback(
        trackUri: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        playedAt: Long = System.currentTimeMillis(),
    ): Long {
        require(trackUri.isNotBlank()) { "Track URI cannot be empty" }
        return writableDatabase.insertOrThrow(
            "playback_history",
            null,
            ContentValues().apply {
                put("track_uri", trackUri)
                put("title", title)
                put("artist", artist)
                put("album", album)
                put("duration_ms", durationMs.coerceAtLeast(0L))
                put("played_at", playedAt)
            },
        )
    }

    @Synchronized
    fun getPlaybackHistory(
        limit: Int,
        beforeId: Long? = null,
    ): List<PlaybackHistoryEntry> {
        require(limit in 1..200) { "Playback history page size must be between 1 and 200" }
        val selection = if (beforeId == null) null else "id < ?"
        val selectionArgs = beforeId?.let { arrayOf(it.toString()) }
        return readableDatabase.query(
            "playback_history",
            arrayOf("id", "track_uri", "title", "artist", "album", "duration_ms", "played_at"),
            selection,
            selectionArgs,
            null,
            null,
            "id DESC",
            limit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PlaybackHistoryEntry(
                            id = cursor.getLong(0),
                            trackUri = cursor.getString(1),
                            title = cursor.getString(2),
                            artist = cursor.getString(3),
                            album = cursor.getString(4),
                            durationMs = cursor.getLong(5),
                            playedAt = cursor.getLong(6),
                        ),
                    )
                }
            }
        }
    }

    @Synchronized
    fun deletePlaybackHistoryEntry(id: Long): Boolean =
        writableDatabase.delete("playback_history", "id = ?", arrayOf(id.toString())) > 0

    @Synchronized
    fun clearPlaybackHistory(): Int =
        writableDatabase.delete("playback_history", null, null)

    private fun android.database.Cursor.toTrackMetadata() = SavedTrackMetadata(
        uri = getString(0),
        title = getString(1),
        artist = getString(2),
        album = getString(3),
        durationMs = if (isNull(4)) null else getLong(4),
        artworkPath = if (isNull(5)) null else getString(5),
    )

    private fun playlistTracks(db: SQLiteDatabase, playlistId: Long): MutableList<String> =
        db.query(
            "playlist_tracks",
            arrayOf("track_uri"),
            "playlist_id = ?",
            arrayOf(playlistId.toString()),
            null,
            null,
            "position ASC, added_at ASC, track_uri ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }.toMutableList()
        }

    private fun rewritePositions(db: SQLiteDatabase, playlistId: Long, tracks: List<String>) {
        tracks.forEachIndexed { index, trackUri ->
            val values = ContentValues().apply { put("position", index.toLong()) }
            check(
                db.update(
                    "playlist_tracks",
                    values,
                    "playlist_id = ? AND track_uri = ?",
                    arrayOf(playlistId.toString(), trackUri),
                ) == 1,
            ) { "Could not update playlist track position" }
        }
    }

    companion object {
        private const val DATABASE_NAME = "playlists.db"
        private const val DATABASE_VERSION = 3
    }
}
