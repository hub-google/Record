package com.hubgoogle.record.audio

import android.content.Context
import android.os.SystemClock
import com.hubgoogle.record.data.RecordingDetails
import com.hubgoogle.record.data.RecordingFile
import com.hubgoogle.record.data.Recordings
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Preserves independent audio recordings in a ZIP with a monotonic-clock
 * synchronization manifest. Does NOT pretend to have separated call uplink/downlink.
 * A pair is valid only when its captured elapsed-time intervals overlap.
 */
object MultiTrackExporter {
    private const val MAX_PAIR_GAP_NS = 5L * 60 * 1_000_000_000
    data class TrackPair(val call: RecordingFile, val media: RecordingFile, val overlapMs: Long)

    fun findBestPair(call: RecordingFile, candidates: List<RecordingFile>): TrackPair? {
        val callInfo = call.details ?: return null
        if (callInfo.source != "CALL_MIX") return null
        return candidates.asSequence().filter { it.details?.source == "MEDIA_PLAYBACK" }
            .mapNotNull { media ->
                val info = media.details ?: return@mapNotNull null
                val callStart = callInfo.startedElapsedNs
                val callEnd = callStart + callInfo.durationMs * 1_000_000L
                val mediaStart = info.startedElapsedNs
                val mediaEnd = mediaStart + info.durationMs * 1_000_000L
                // If the monotonic clock reset on a different boot, the overlap is
                // not trustworthy; use same-day time sanity as an additional guard.
                val wallDifference = kotlin.math.abs(call.modified - media.modified)
                if (wallDifference > 2 * 60 * 60 * 1000L) return@mapNotNull null
                if (kotlin.math.abs(callStart - mediaStart) > MAX_PAIR_GAP_NS) return@mapNotNull null
                val overlap = minOf(callEnd, mediaEnd) - maxOf(callStart, mediaStart)
                if (overlap <= 0L) null else TrackPair(call, media, overlap / 1_000_000L)
            }
            .maxByOrNull { it.overlapMs }
    }

    fun export(context: Context, pair: TrackPair): File {
        require(pair.overlapMs > 0)
        val library = Recordings(context)
        val folder = pair.call.file.parentFile!!.canonicalFile
        check(pair.media.file.parentFile!!.canonicalFile == folder)
        val dest = File(folder, "multitrack_" + UUID.randomUUID().toString().take(12) + ".zip")
        val partial = File(folder, dest.name + ".partial")
        val start = minOf(pair.call.details!!.startedElapsedNs, pair.media.details!!.startedElapsedNs)
        val manifest = JSONObject().apply {
            put("format", "record.multitrack/1")
            put("timestampBasis", "android.elapsedRealtimeNanos")
            put("captureStatus", "UNVERIFIED_ON_DEVICE")
            put("limitations", "Each source is an independent capture; CALL_MIX is NOT split into TX/RX. No drift correction.")
            put("overlapMs", pair.overlapMs)
            put("tracks", JSONArray().apply {
                put(trackJson(pair.call, "call", start))
                put(trackJson(pair.media, "media", start))
            })
        }
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(partial))).use { zip ->
                for ((index, item) in listOf(pair.call, pair.media).withIndex()) {
                    val path = if (index == 0) "call/" else "media/"
                    zip.putNextEntry(ZipEntry(path + item.file.name))
                    FileInputStream(item.file).use { src -> src.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("session.json"))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            check(partial.length() > 100L)
            check(partial.renameTo(dest)) { "Cannot publish multitrack package" }
            return dest
        } finally {
            partial.delete()
        }
    }

    private fun trackJson(item: RecordingFile, category: String, origin: Long): JSONObject {
        val d = requireNotNull(item.details)
        return JSONObject().apply {
            put("category", category)
            put("file", category + "/" + item.file.name)
            put("source", d.source)
            put("format", item.file.extension.lowercase())
            put("sampleRateHz", d.sampleRate)
            put("channels", d.channels)
            put("durationMs", d.durationMs)
            put("relativeStartUs", (d.startedElapsedNs - origin) / 1000L)
            put("qualityStatus", d.status)
            put("pcmSha256", d.sha256)
        }
    }
}

