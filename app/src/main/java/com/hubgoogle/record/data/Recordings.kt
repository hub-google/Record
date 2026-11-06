package com.hubgoogle.record.data

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import androidx.core.content.FileProvider
import java.io.File
import java.text.DateFormat
import java.util.Date

data class RecordingFile(val file: File, val modified: Long, val size: Long) {
    val displayDate: String get() = DateFormat.getDateTimeInstance().format(Date(modified))
}
class Recordings(private val context: Context) {
    private val dir = File(context.filesDir, "recordings").apply { mkdirs() }
    fun newFile(prefix: String): File {
        val cleaned = prefix.replace(Regex("[^A-Za-z0-9_-]"), "").take(25).ifEmpty { "recording" }
        return File(dir, cleaned + "_" + System.currentTimeMillis() + "_" + java.util.UUID.randomUUID().toString().take(8) + ".wav")
    }
    fun list(): List<RecordingFile> = dir.listFiles()?.filter { it.isFile && it.extension == "wav" }
        ?.sortedByDescending { it.lastModified() }?.map { RecordingFile(it, it.lastModified(), it.length()) }.orEmpty()
    fun remove(file: File): Boolean = isOwned(file) && file.delete()
    private fun isOwned(file: File) = file.canonicalFile.parentFile == dir.canonicalFile
    fun share(file: File) {
        check(isOwned(file))
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        context.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "分享錄音"
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun play(file: File, onCompletion: () -> Unit = {}): MediaPlayer {
        check(isOwned(file))
        return MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { onCompletion(); it.release() }
            prepare()
            start()
        }
    }
}

