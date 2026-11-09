package com.hubgoogle.record.audio

import com.hubgoogle.record.data.RecordingFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/**
 * Bundles only actual, non-silent original PCM tracks overlapping a reference.
 * This is evidence packaging, not proof that all device sources were capturable.
 */
object SessionBundleExporter {
    val sourceTypes = listOf("CALL_MIX", "CALL_TX", "CALL_RX", "MIC",
        "MEDIA_PLAYBACK", "VOIP_LINE", "VOIP_WHATSAPP", "VOIP_WHATSAPP_BUSINESS",
        "VOIP_TELEGRAM", "VOIP_SIGNAL", "VOIP_MESSENGER", "VOIP_DISCORD", "VOIP_MEET")

    data class Plan(val tracks: List<RecordingFile>, val missing: List<String>,
                    val rejected: Map<String, String>) {
        val sourceCount get() = tracks.mapNotNull { it.details?.source }.distinct().size
    }

    private fun eligible(item: RecordingFile): Boolean {
        val d = item.details ?: return false
        return item.file.isFile && item.file.extension.equals("wav", true) &&
            item.file.length() > 44L && d.source in sourceTypes &&
            d.startedElapsedNs > 0L && d.durationMs > 0L &&
            d.sampleRate > 0 && d.channels in 1..2
    }

    fun plan(reference: RecordingFile, candidates: List<RecordingFile>): Plan {
        require(eligible(reference)) { "Select an original indexed PCM WAV recording" }
        val ref = requireNotNull(reference.details)
        val refStart = ref.startedElapsedNs
        val refEnd = refStart + ref.durationMs * 1_000_000L
        val folder = requireNotNull(reference.file.canonicalFile.parentFile)
        val rejected = linkedMapOf<String, String>()
        val selected = (candidates + reference).distinctBy { it.file.canonicalPath }
            .filter { item ->
                if (!eligible(item) || item.file.canonicalFile.parentFile != folder) return@filter false
                val d = requireNotNull(item.details)
                val start = d.startedElapsedNs
                val end = start + d.durationMs * 1_000_000L
                if (minOf(refEnd, end) <= maxOf(refStart, start)) return@filter false
                if (abs(item.modified - reference.modified) > 6L * 60L * 60L * 1000L) return@filter false
                if (d.status in setOf("SILENT", "EMPTY")) {
                    rejected[item.file.name] = d.status
                    return@filter false
                }
                true
            }
            .sortedWith(compareBy<RecordingFile> { requireNotNull(it.details).startedElapsedNs }
                .thenBy { it.file.name })
            .take(100)
        return Plan(selected, sourceTypes.filterNot { source ->
            selected.any { item -> item.details?.source == source }
        }, rejected)
    }

    fun export(reference: RecordingFile, candidates: List<RecordingFile>): File {
        val plan = plan(reference, candidates)
        check(plan.tracks.isNotEmpty()) { "No non-silent recordings in this session" }
        val directory = requireNotNull(reference.file.canonicalFile.parentFile)
        val totalBytes = plan.tracks.sumOf { it.file.length() }
        require(totalBytes in 45L..2_000_000_000L) { "Recording bundle exceeds 2GB limit" }
        check(directory.usableSpace > totalBytes + 8L * 1024 * 1024) {
            "Not enough free storage for the complete session bundle"
        }
        val file = File(directory, "session_" + UUID.randomUUID().toString().take(12) + ".zip")
        val partial = File(directory, file.name + ".partial")
        val origin = plan.tracks.minOf { requireNotNull(it.details).startedElapsedNs }
        val manifest = JSONObject().apply {
            put("format", "record.session/2")
            put("captureStatus", "UNVERIFIED_ON_DEVICE")
            put("allDeviceAudioCaptured", false)
            put("clock", "android.elapsedRealtimeNanos")
            put("missingSources", JSONArray(plan.missing))
            put("excludedSilentTracks", JSONObject().also { o ->
                plan.rejected.forEach { (key, value) -> o.put(key, value) }
            })
            put("tracks", JSONArray().apply {
                plan.tracks.forEachIndexed { index, item ->
                    val d = requireNotNull(item.details)
                    put(JSONObject().apply {
                        put("path", "tracks/" + index.toString().padStart(3, '0') + "_" + item.file.name)
                        put("source", d.source)
                        put("relativeStartUs", (d.startedElapsedNs - origin) / 1000L)
                        put("durationMs", d.durationMs)
                        put("sampleRate", d.sampleRate)
                        put("channels", d.channels)
                        put("qualityStatus", d.status)
                        put("pcmSha256", d.sha256)
                    })
                }
            })
        }
        try {
            ZipOutputStream(FileOutputStream(partial).buffered()).use { zip ->
                plan.tracks.forEachIndexed { index, item ->
                    zip.putNextEntry(ZipEntry("tracks/" +
                        index.toString().padStart(3, '0') + "_" + item.file.name))
                    FileInputStream(item.file).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("session.json"))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            check(partial.length() > 100L && partial.renameTo(file)) {
                "Could not finalize session archive"
            }
            return file
        } finally { partial.delete() }
    }
}
