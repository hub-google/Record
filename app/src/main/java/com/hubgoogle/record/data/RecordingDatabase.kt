package com.hubgoogle.record.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.hubgoogle.record.core.QualityResult
import java.io.File

data class RecordingDetails(
    val source: String,
    val status: String,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val rms: Double,
    val peak: Int,
    val zeroRatio: Double,
    val sha256: String,
    val startedElapsedNs: Long
)

/**
 * Local-only SQLite metadata; no phone numbers, caller identities or audio data in the DB.
 * File is the canonical audio payload. Both metadata and audio can be deleted by the user.
 */
class RecordingDatabase(context: Context) : SQLiteOpenHelper(context, "record_index.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE recordings (" +
                "filename TEXT NOT NULL PRIMARY KEY," +
                "source TEXT NOT NULL," +
                "status TEXT NOT NULL," +
                "duration_ms INTEGER NOT NULL," +
                "sample_rate INTEGER NOT NULL," +
                "channels INTEGER NOT NULL," +
                "rms REAL NOT NULL," +
                "peak INTEGER NOT NULL," +
                "zero_ratio REAL NOT NULL," +
                "sha256 TEXT NOT NULL," +
                "started_elapsed_ns INTEGER NOT NULL," +
                "created_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX recordings_created_at ON recordings(created_at DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Database migration required from " + oldVersion + " to " + newVersion)
    }

    fun save(file: File, source: String, rate: Int, channels: Int, quality: QualityResult,
             sha256: String, startedElapsedNs: Long) {
        val status = when {
            quality.samples == 0L -> "EMPTY"
            quality.isLikelySilent -> "SILENT"
            quality.hasClipping -> "CLIPPING"
            else -> "RECORDED_UNVERIFIED" // Both sides must be confirmed by a consenting person.
        }
        val values = ContentValues().apply {
            put("filename", file.name)
            put("source", source)
            put("status", status)
            put("duration_ms", quality.samples * 1000L / (rate * channels))
            put("sample_rate", rate)
            put("channels", channels)
            put("rms", quality.rms)
            put("peak", quality.peak)
            put("zero_ratio", quality.zeroRatio)
            put("sha256", sha256)
            put("started_elapsed_ns", startedElapsedNs)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("recordings", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun details(filename: String): RecordingDetails? {
        readableDatabase.query(
            "recordings",
            arrayOf("source","status","duration_ms","sample_rate","channels","rms",
                "peak","zero_ratio","sha256","started_elapsed_ns"),
            "filename=?", arrayOf(filename), null, null, null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return RecordingDetails(
                source = cursor.getString(0), status = cursor.getString(1),
                durationMs = cursor.getLong(2), sampleRate = cursor.getInt(3),
                channels = cursor.getInt(4), rms = cursor.getDouble(5),
                peak = cursor.getInt(6), zeroRatio = cursor.getDouble(7),
                sha256 = cursor.getString(8), startedElapsedNs = cursor.getLong(9)
            )
        }
    }
    fun duplicateMetadata(originalName: String, newName: String) {
        val source = details(originalName) ?: return
        val values = ContentValues().apply {
            put("filename", newName)
            put("source", source.source)
            put("status", source.status)
            put("duration_ms", source.durationMs)
            put("sample_rate", source.sampleRate)
            put("channels", source.channels)
            put("rms", source.rms)
            put("peak", source.peak)
            put("zero_ratio", source.zeroRatio)
            put("sha256", "")
            put("started_elapsed_ns", source.startedElapsedNs)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("recordings", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun rename(oldName: String, newName: String) {
        val values = ContentValues().apply { put("filename", newName) }
        writableDatabase.update("recordings", values, "filename=?", arrayOf(oldName))
    }
    fun delete(filename: String): Boolean =
        writableDatabase.delete("recordings", "filename=?", arrayOf(filename)) >= 0
}

