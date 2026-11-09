package com.hubgoogle.record.data

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import androidx.core.content.FileProvider
import java.io.File
import java.text.DateFormat
import java.util.Date

data class RecordingFile(val file: File, val modified: Long, val size: Long, val details: RecordingDetails? = null) {
    val displayDate: String get() = DateFormat.getDateTimeInstance().format(Date(modified))
}
class Recordings(private val context: Context) {
    private val dir = File(context.filesDir, "recordings").apply { mkdirs() }
    private val database by lazy { RecordingDatabase(context) }
    private var currentPlayer: MediaPlayer? = null
    fun stopPlaying() {
        val player = currentPlayer ?: return
        currentPlayer = null
        runCatching { if (player.isPlaying) player.stop() }
        runCatching { player.release() }
    }
    /** Deterministic name for shell session recovery and idempotent fetch. */
    fun sessionFile(prefix: String, sessionId: String): File {
        require(Regex("[0-9a-fA-F-]{36}").matches(sessionId))
        val stem = prefix.replace(Regex("[^a-zA-Z0-9_-]"), "").take(20)
        return File(dir, stem + "_" + sessionId + ".wav")
    }
    fun newFile(prefix: String): File {
        val cleaned = prefix.replace(Regex("[^A-Za-z0-9_-]"), "").take(25).ifEmpty { "recording" }
        return File(dir, cleaned + "_" + System.currentTimeMillis() + "_" + java.util.UUID.randomUUID().toString().take(8) + ".wav")
    }
    fun list(): List<RecordingFile> = dir.listFiles()?.filter { it.isFile && it.extension in setOf("wav", "flac", "m4a", "ogg", "zip", "enc") }
        ?.sortedByDescending { it.lastModified() }?.map { RecordingFile(it, it.lastModified(), it.length(), database.details(it.name)) }.orEmpty()
    /**
     * User opt-in retention; 0 = never delete. Files are removed only inside our
     * private recordings folder, and metadata rows are removed after file deletion.
     */
    fun purgeOlderThan(days: Int): Int {
        require(days == 0 || days in 1..3650)
        if (days == 0) return 0
        val cutoff = System.currentTimeMillis() - days.toLong() * 86_400_000L
        var deleted = 0
        for (file in dir.listFiles().orEmpty()) {
            if (file.isFile && file.extension in setOf("wav", "flac", "zip", "enc")
                && file.lastModified() < cutoff && remove(file)) deleted++
        }
        return deleted
    }

    /**
     * Rename inside the private directory; keep the original suffix so
     * encrypted WAV/FLAC filenames remain round-trip decryptable.
     */
    fun rename(file: File, newBase: String): File {
        check(isOwned(file) && file.isFile) { "Not a library recording" }
        val safe = newBase.trim().replace(Regex("[^\\p{L}\\p{N} _-]"), "_").take(72).trim()
        require(safe.isNotBlank()) { "Filename cannot be empty" }
        val suffix = when {
            file.name.endsWith(".wav.enc") -> ".wav.enc"
            file.name.endsWith(".flac.enc") -> ".flac.enc"
            file.name.endsWith(".zip.enc") -> ".zip.enc"
            file.name.endsWith(".m4a.enc") -> ".m4a.enc"
            file.name.endsWith(".ogg.enc") -> ".ogg.enc"
            else -> "." + file.extension
        }
        val output = File(dir, safe + suffix)
        check(!output.exists()) { "A recording with that filename already exists" }
        check(file.renameTo(output)) { "Could not rename recording" }
        try {
            database.rename(file.name, output.name)
        } catch (e: Exception) {
            output.renameTo(file)
            throw e
        }
        return output
    }

    fun copyTo(file: File, destination: java.io.OutputStream) {
        check(isOwned(file) && file.isFile) { "Not a library recording" }
        file.inputStream().buffered().use { input -> input.copyTo(destination) }
    }

    fun deleteAll(): Int {
        var deleted = 0
        for (entry in list()) if (remove(entry.file)) deleted++
        if (list().isEmpty()) database.clearIndex()
        return deleted
    }

    fun remove(file: File): Boolean {
        if (!isOwned(file) || !file.delete()) return false
        database.delete(file.name)
        return true
    }
    private fun isOwned(file: File) = file.canonicalFile.parentFile == dir.canonicalFile
    fun share(file: File) {
        check(isOwned(file) && !file.name.endsWith(".enc")) { "Encrypted recordings must be decrypted first" }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        context.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = when (file.extension.lowercase()) {
                    "flac" -> "audio/flac"
                    "m4a" -> "audio/mp4"
                    "ogg" -> "audio/ogg"
                    "zip" -> "application/zip"
                    else -> "audio/wav"
                }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "分享錄音"
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun play(file: File, onCompletion: () -> Unit = {}): MediaPlayer {
        check(isOwned(file) && file.isFile && file.extension in setOf("wav", "flac", "m4a", "ogg")) {
            "Only unencrypted audio from the local recording library can be played"
        }
        stopPlaying()
        val player = MediaPlayer()
        try {
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                if (currentPlayer === it) currentPlayer = null
                runCatching { it.release() }
                onCompletion()
            }
            player.setOnErrorListener { source, _, _ ->
                if (currentPlayer === source) currentPlayer = null
                runCatching { source.release() }
                true
            }
            player.prepare()
            currentPlayer = player
            player.start()
            return player
        } catch (error: Exception) {
            if (currentPlayer === player) currentPlayer = null
            runCatching { player.release() }
            throw error
        }
    }
}

