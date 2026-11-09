package com.hubgoogle.record.audio

import com.hubgoogle.record.data.RecordingFile
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Offline 48 kHz stereo WAV mix from every actually available overlapping track.
 * No source is synthesized; no unsupported capture is silently substituted.
 * Source clocks are Android elapsedRealtimeNanos and are not drift-corrected.
 */
object SessionMixer {
    const val RATE = 48_000
    private const val MAX_SECONDS = 3_600

    fun mix(plan: SessionBundleExporter.Plan): File {
        val inputs = plan.tracks.filter { it.file.extension.equals("wav", true) }
        require(inputs.size >= 2) { "At least two real audio sources are required" }
        val separatelyCaptured = inputs.any { it.details?.source == "CALL_TX" } &&
            inputs.any { it.details?.source == "CALL_RX" }
        // Avoid double-counting CALL_MIX when the same telephone audio has
        // separate TX and RX tracks in this particular session.
        val selected = if (separatelyCaptured) inputs.filterNot {
            it.details?.source == "CALL_MIX"
        } else inputs
        require(selected.size >= 2)
        val folder = requireNotNull(selected.first().file.canonicalFile.parentFile)
        check(selected.all { it.file.canonicalFile.parentFile == folder })
        val startNs = selected.minOf { requireNotNull(it.details).startedElapsedNs }
        val endNs = selected.maxOf { item ->
            val d = requireNotNull(item.details)
            d.startedElapsedNs + d.durationMs * 1_000_000L
        }
        val durationNs = endNs - startNs
        require(durationNs in 1..(MAX_SECONDS * 1_000_000_000L)) {
            "Mix duration must not exceed one hour"
        }
        val frames = durationNs * RATE / 1_000_000_000L
        val totalBytes = frames * 4L + 44L
        check(folder.usableSpace > totalBytes + 16L * 1024 * 1024) { "Insufficient storage for lossless mix" }
        val output = File(folder, "all_sources_" + UUID.randomUUID().toString().take(12) + ".wav")
        val readers = mutableListOf<Reader>()
        try {
            selected.forEach { readers += Reader(it.file,
                requireNotNull(it.details).startedElapsedNs - startNs) }
            WavPcmFile(output, RATE, 2).use { wav ->
                val bytes = ByteArray(4096 * 4)
                var frame = 0L
                while (frame < frames) {
                    val count = minOf(4096L, frames - frame).toInt()
                    for (i in 0 until count) {
                        val nowNs = (frame + i) * 1_000_000_000L / RATE
                        for (channel in 0..1) {
                            var sum = 0L
                            var active = 0
                            for (reader in readers) {
                                val sample = reader.sample(nowNs, channel)
                                if (sample != null) {
                                    sum += sample
                                    active++
                                }
                            }
                            val value = if (active == 0) 0 else
                                (sum / active).toInt().coerceIn(-32768, 32767)
                            val at = i * 4 + channel * 2
                            bytes[at] = value.toByte()
                            bytes[at + 1] = (value shr 8).toByte()
                        }
                    }
                    wav.append(bytes, 0, count * 4)
                    frame += count
                }
                wav.commit()
            }
            return output
        } finally {
            readers.forEach { runCatching { it.close() } }
        }
    }

    private class Reader(file: File, private val startNs: Long) : AutoCloseable {
        private val wav = RandomAccessFile(file, "r")
        private val channels: Int
        private val rate: Int
        private val lengthFrames: Long
        private val cache = ByteArray(65536)
        private var cacheStart = -1L
        private var cacheFrames = 0

        init {
            try {
                val header = ByteArray(44)
                wav.readFully(header)
                val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(header, 8, 4, Charsets.US_ASCII) == "WAVE" &&
                    String(header, 36, 4, Charsets.US_ASCII) == "data" &&
                    h.getShort(20).toInt() == 1 && h.getShort(34).toInt() == 16)
                val channelCount = h.getShort(22).toInt()
                val sampleRate = h.getInt(24)
                require(channelCount in 1..2 && sampleRate in 8000..192000)
                val size = h.getInt(40).toLong() and 0xffffffffL
                require(size == wav.length() - 44L && size % (channelCount * 2) == 0L)
                channels = channelCount
                rate = sampleRate
                lengthFrames = size / (channels * 2)
            } catch (e: Throwable) {
                wav.close()
                throw e
            }
        }
        fun sample(outputNs: Long, channel: Int): Int? {
            val relativeNs = outputNs - startNs
            if (relativeNs < 0) return null
            val index = relativeNs * rate / 1_000_000_000L
            if (index >= lengthFrames) return null
            if (index < cacheStart || index >= cacheStart + cacheFrames) {
                val capacity = cache.size / (channels * 2)
                cacheStart = (index / capacity) * capacity
                cacheFrames = minOf(lengthFrames - cacheStart, capacity.toLong()).toInt()
                wav.seek(44L + cacheStart * channels * 2)
                wav.readFully(cache, 0, cacheFrames * channels * 2)
            }
            val offset = ((index - cacheStart).toInt() * channels +
                if (channels == 1) 0 else channel) * 2
            return ((cache[offset + 1].toInt() shl 8) or
                (cache[offset].toInt() and 255)).toShort().toInt()
        }
        override fun close() { wav.close() }
    }
}
