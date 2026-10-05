package io.github.ytdlpandroid

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class DownloadJob(
    val id: Long,
    val urlsJson: String,
    val format: String,
    val allowPlaylist: Boolean,
    val state: String,
    val message: String,
    val percent: Int?,
    val createdAt: Long,
    val pauseOrigin: String?,
)

internal class DownloadQueueStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) = createSchema(db)

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE jobs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                urls_json TEXT NOT NULL,
                format TEXT NOT NULL,
                allow_playlist INTEGER NOT NULL,
                state TEXT NOT NULL,
                message TEXT NOT NULL,
                percent INTEGER,
                created_at INTEGER NOT NULL,
                queue_order INTEGER NOT NULL DEFAULT 0,
                pause_origin TEXT
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX jobs_state_created ON jobs(state, created_at)")
        db.execSQL("CREATE INDEX jobs_state_queue_order ON jobs(state, queue_order, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion <= newVersion) { "Database downgrade is not supported" }
        when (oldVersion) {
            1 -> {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN queue_order INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN pause_origin TEXT")
                db.execSQL("UPDATE $TABLE SET queue_order = id")
                db.execSQL("CREATE INDEX jobs_state_queue_order ON $TABLE(state, queue_order, id)")
            }
            else -> error("No queue database migration exists from version $oldVersion")
        }
    }

    fun enqueue(urlsJson: String, format: String, allowPlaylist: Boolean): Long {
        val values = ContentValues().apply {
            put("urls_json", urlsJson)
            put("format", format)
            put("allow_playlist", if (allowPlaylist) 1 else 0)
            put("state", STATE_QUEUED)
            put("message", "Waiting")
            put("created_at", System.currentTimeMillis())
            put("queue_order", nextQueueOrder())
        }
        val id = writableDatabase.insertOrThrow(TABLE, null, values)
        trimFinishedJobs()
        return id
    }

    fun claimNextQueued(): DownloadJob? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val job = db.rawQuery(
                "SELECT * FROM $TABLE WHERE state = ? ORDER BY queue_order ASC, id ASC LIMIT 1",
                arrayOf(STATE_QUEUED),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.toJob() else null } ?: return null
            val values = ContentValues().apply {
                put("state", STATE_RUNNING)
                put("message", "Starting…")
                putNull("percent")
                putNull("pause_origin")
            }
            check(db.update(TABLE, values, "id = ? AND state = ?", arrayOf(job.id.toString(), STATE_QUEUED)) == 1) {
                "Queued job ${job.id} could not be claimed"
            }
            db.setTransactionSuccessful()
            return job.copy(state = STATE_RUNNING, message = "Starting…", percent = null)
        } finally {
            db.endTransaction()
        }
    }

    fun getJobs(): List<DownloadJob> {
        val jobs = mutableListOf<DownloadJob>()
        readableDatabase.query(
            TABLE,
            null,
            null,
            null,
            null,
            null,
            "id DESC",
            MAX_RETAINED_JOBS.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) jobs.add(cursor.toJob())
        }
        return jobs
    }

    fun update(id: Long, state: String, message: String, percent: Int? = null) {
        update(id, state, message, percent, null)
    }

    fun update(id: Long, state: String, message: String, percent: Int?, pauseOrigin: String?) {
        val values = ContentValues().apply {
            put("state", state)
            put("message", message)
            if (percent == null) putNull("percent") else put("percent", percent.coerceIn(0, 100))
            if (pauseOrigin == null) putNull("pause_origin") else put("pause_origin", pauseOrigin)
        }
        writableDatabase.update(TABLE, values, "id = ?", arrayOf(id.toString()))
    }

    fun moveToFront(id: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val minimum = db.rawQuery(
                "SELECT MIN(queue_order) FROM $TABLE WHERE state = ?",
                arrayOf(STATE_QUEUED),
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            }
            val values = ContentValues().apply { put("queue_order", minimum - 1L) }
            val changed = db.update(
                TABLE,
                values,
                "id = ? AND state = ?",
                arrayOf(id.toString(), STATE_QUEUED),
            )
            if (changed > 0) db.setTransactionSuccessful()
            return changed > 0
        } finally {
            db.endTransaction()
        }
    }

    fun clearFinished(): Int = writableDatabase.delete(
        TABLE,
        "state IN (?, ?, ?, ?)",
        arrayOf(STATE_COMPLETE, STATE_PARTIAL, STATE_FAILED, STATE_CANCELED),
    )

    fun cancel(id: Long): Boolean {
        val values = ContentValues().apply {
            put("state", STATE_CANCELED)
            put("message", "Canceled")
            putNull("percent")
            putNull("pause_origin")
        }
        val changed = writableDatabase.update(
            TABLE,
            values,
            "id = ? AND state IN (?, ?)",
            arrayOf(id.toString(), STATE_QUEUED, STATE_PAUSED),
        )
        if (changed > 0) trimFinishedJobs()
        return changed > 0
    }

    fun retry(id: Long): Boolean {
        val values = ContentValues().apply {
            put("state", STATE_QUEUED)
            put("message", "Waiting to retry")
            putNull("percent")
            putNull("pause_origin")
        }
        return writableDatabase.update(
            TABLE,
            values,
            "id = ? AND state IN (?, ?, ?)",
            arrayOf(id.toString(), STATE_FAILED, STATE_PARTIAL, STATE_CANCELED),
        ) > 0
    }

    fun resume(id: Long): Boolean {
        val values = ContentValues().apply {
            put("state", STATE_QUEUED)
            put("message", "Waiting to resume")
            putNull("pause_origin")
        }
        return writableDatabase.update(
            TABLE,
            values,
            "id = ? AND state = ? AND (pause_origin IS NULL OR pause_origin = ?)",
            arrayOf(id.toString(), STATE_PAUSED, PAUSE_ORIGIN_USER),
        ) > 0
    }

    fun resumeNetworkPaused(id: Long? = null): Int {
        val values = ContentValues().apply {
            put("state", STATE_QUEUED)
            put("message", "Waiting to resume")
            putNull("pause_origin")
        }
        return writableDatabase.update(
            TABLE,
            values,
            "state = ? AND pause_origin = ?" + if (id == null) "" else " AND id = ?",
            if (id == null) arrayOf(STATE_PAUSED, PAUSE_ORIGIN_WIFI_POLICY)
            else arrayOf(STATE_PAUSED, PAUSE_ORIGIN_WIFI_POLICY, id.toString()),
        )
    }


    fun recoverInterrupted(): Int {
        val values = ContentValues().apply {
            put("state", STATE_QUEUED)
            put("message", "Interrupted; queued to resume")
            putNull("percent")
            putNull("pause_origin")
        }
        return writableDatabase.update(TABLE, values, "state = ?", arrayOf(STATE_RUNNING))
    }

    fun hasQueued(): Boolean = readableDatabase.rawQuery(
        "SELECT id FROM $TABLE WHERE state = ? LIMIT 1",
        arrayOf(STATE_QUEUED),
    ).use { cursor -> cursor.moveToFirst() }

    fun hasNetworkPaused(): Boolean = readableDatabase.rawQuery(
        "SELECT id FROM $TABLE WHERE state = ? AND pause_origin = ? LIMIT 1",
        arrayOf(STATE_PAUSED, PAUSE_ORIGIN_WIFI_POLICY),
    ).use { cursor -> cursor.moveToFirst() }

    fun countQueued(): Int = countByState(STATE_QUEUED)

    fun countPaused(): Int = countByState(STATE_PAUSED)

    private fun countByState(state: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM $TABLE WHERE state = ?",
        arrayOf(state),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun nextQueueOrder(): Long =
        readableDatabase.rawQuery("SELECT COALESCE(MAX(queue_order), 0) + 1 FROM $TABLE", null)
            .use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 1L }


    private fun android.database.Cursor.toJob() = DownloadJob(
        id = getLong(getColumnIndexOrThrow("id")),
        urlsJson = getString(getColumnIndexOrThrow("urls_json")).orEmpty(),
        format = getString(getColumnIndexOrThrow("format")),
        allowPlaylist = getInt(getColumnIndexOrThrow("allow_playlist")) != 0,
        state = getString(getColumnIndexOrThrow("state")),
        message = getString(getColumnIndexOrThrow("message")),
        percent = if (isNull(getColumnIndexOrThrow("percent"))) null else getInt(getColumnIndexOrThrow("percent")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        pauseOrigin = getString(getColumnIndexOrThrow("pause_origin")),
    )

    private fun trimFinishedJobs() {
        writableDatabase.execSQL(
            """DELETE FROM $TABLE
                WHERE state IN (?, ?, ?, ?)
                AND id NOT IN (
                    SELECT id FROM $TABLE
                    WHERE state IN (?, ?, ?, ?)
                    ORDER BY id DESC LIMIT ?
                )""".trimIndent(),
            arrayOf(
                STATE_COMPLETE, STATE_PARTIAL, STATE_FAILED, STATE_CANCELED,
                STATE_COMPLETE, STATE_PARTIAL, STATE_FAILED, STATE_CANCELED,
                MAX_RETAINED_JOBS.toString(),
            ),
        )
    }

    companion object {
        private const val DATABASE_NAME = "download-queue.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE = "jobs"
        private const val MAX_RETAINED_JOBS = 100

        const val STATE_QUEUED = "QUEUED"
        const val STATE_RUNNING = "RUNNING"
        const val STATE_PAUSED = "PAUSED"
        const val STATE_COMPLETE = "COMPLETE"
        const val STATE_PARTIAL = "PARTIAL"
        const val STATE_FAILED = "FAILED"
        const val STATE_CANCELED = "CANCELED"
        const val PAUSE_ORIGIN_USER = "USER"
        const val PAUSE_ORIGIN_WIFI_POLICY = "WIFI_POLICY"
    }
}
